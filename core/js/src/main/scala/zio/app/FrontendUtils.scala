package zio.app

import boopickle.Default._
import boopickle.{CompositePickler, UnpickleState}
import org.scalajs.dom.RequestMode
import zio._
import zio.app.internal.ZioResponse
import zio.http._
import zio.stream._

import java.net.URLEncoder
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import scala.util.Try

object FrontendUtils {
  implicit val exPickler: CompositePickler[Throwable] = exceptionPickler

  def apiPath(config: ClientConfig, service: String, method: String): Path =
    config.root / "api" / URLEncoder.encode(service, StandardCharsets.UTF_8) / URLEncoder.encode(
      method,
      StandardCharsets.UTF_8
    )

  def fetch[E: Pickler, A: Pickler](service: String, method: String, config: ClientConfig): IO[E, A] =
    fetchRequest[E, A](Request.get(apiPath(config, service, method).toString), config)

  def fetch[E: Pickler, A: Pickler](
    service: String,
    method: String,
    value: ByteBuffer,
    config: ClientConfig
  ): IO[E, A] =
    fetchRequest[E, A](
      Request.post(
        apiPath(config, service, method).toString,
        Body.fromChunk(Chunk.fromByteBuffer(value))
      ),
      config
    )

  def fetchRequest[E: Pickler, A: Pickler](
    request: Request,
    config: ClientConfig
  ): IO[E, A] =
    ZIO
      .scoped(Client.batched(config.authToken match {
        case None            => request
        case Some(authToken) => request.addHeader(Header.Authorization.Bearer(authToken))
      }))
      .provideSomeLayer(Client.default)
      .flatMap(_.body.asArray)
      .orDie
      .flatMap { response =>
        Unpickle[ZioResponse[E, A]].fromBytes(ByteBuffer.wrap(response)) match {
          case ZioResponse.Succeed(value) =>
            ZIO.succeed(value)
          case ZioResponse.Fail(value) =>
            ZIO.fail(value)
          case ZioResponse.Die(throwable) =>
            ZIO.die(throwable)
          case ZioResponse.Interrupt(fiberId) =>
            // TODO: Fix constructor
            ZIO.interruptAs(FiberId(0, 0, Trace.empty))
        }
      }

  def fetchStream[E: Pickler, A: Pickler](service: String, method: String, config: ClientConfig): Stream[E, A] =
    fetchStreamRequest[E, A](Request.get(apiPath(config, service, method).toString))

  def fetchStream[E: Pickler, A: Pickler](
    service: String,
    method: String,
    value: ByteBuffer,
    config: ClientConfig
  ): Stream[E, A] =
    fetchStreamRequest[E, A](
      Request.post(apiPath(config, service, method).toString, Body.fromChunk(Chunk.fromByteBuffer(value)))
    )

  def fetchStreamRequest[E: Pickler, A: Pickler](request: Request): Stream[E, A] =
    Client
      .streamingWith(request) { response =>
        ZStream.fromZIO(response.body.asArray)
      }
      .provideSomeLayer(Client.default)
      .catchAll(ZStream.die(_))
      .mapConcatChunk(a => unpickleMany[E, A](a))
      .flatMap {
        case ZioResponse.Succeed(value) => ZStream.succeed(value)
        case ZioResponse.Fail(value)    => ZStream.fail(value)
        case ZioResponse.Die(throwable) => ZStream.die(throwable)
        case ZioResponse.Interrupt(_)   => ZStream.fromZIO(ZIO.interruptAs(FiberId(0, 0, Trace.empty)))
      }

  def unpickleMany[E: Pickler, A: Pickler](bytes: Array[Byte]): Chunk[ZioResponse[E, A]] = {
    val unpickleState                       = UnpickleState(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
    def unpickle: Option[ZioResponse[E, A]] = Try(Unpickle[ZioResponse[E, A]].fromState(unpickleState)).toOption
    Chunk.unfold(unpickle)(_.map(_ -> unpickle))
  }

}
