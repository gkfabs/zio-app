package zio.app.internal

import boopickle.CompositePickler
import boopickle.Default._
import io.netty.handler.codec.http.{HttpHeaderNames, HttpHeaderValues}
import zio.http._
import zio._
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec
import zio.stream.{UStream, ZStream}

import java.nio.ByteBuffer
import java.time.Instant

object BackendUtils {
  implicit val exPickler: CompositePickler[Throwable] = exceptionPickler

  private val bytesContent = (HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.BYTES)

  private def urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8")

  def makeRoute[R, E: Pickler, A: Pickler, B: Pickler](
    service: String,
    method: String,
    call: A => ZIO[R, E, B]
  ): Routes[R, Nothing] = {
    val service0 = urlEncode(service)
    val method0  = method
    Routes((Method.POST / `service0` / `method0`) -> handler { (request: Request) =>
      request.body.asArray
        .map(body => ByteBuffer.wrap(body))
        .map(byteBuffer => Unpickle[A].fromBytes(byteBuffer))
        .flatMap(unpickled => call(unpickled))
        .map(ZioResponse.succeed)
        .catchAll { case throwable: Throwable =>
          ZIO.succeed(ZioResponse.die(throwable))
        }
        .catchAllCause(causeToResponseZio[E](_))
        .map(pickle[ZioResponse[E, B]](_))
    })
  }

  def makeRouteNullary[R, E: Pickler, A: Pickler](
    service: String,
    method: String,
    call: ZIO[R, E, A]
  ): Routes[R, Nothing] = {
    val service0 = urlEncode(service)
    val method0  = method
    Routes((Method.GET / `service0` / `method0`) -> handler { (request: Request) =>
      call
        .map(ZioResponse.succeed)
        .catchAllCause(causeToResponseZio[E](_))
        .map(pickle[ZioResponse[E, A]](_))
    })
  }

  def makeRouteStream[R, E: Pickler, A: Pickler, B: Pickler](
    service: String,
    method: String,
    call: A => ZStream[R, E, B]
  ): Routes[R, Nothing] = {
    val service0 = service
    val method0  = method
    Routes((Method.POST / `service0` / `method0`) -> handler { (request: Request) =>
      request.body.asArray
        .map(body => ByteBuffer.wrap(body))
        .map(byteBuffer => Unpickle[A].fromBytes(byteBuffer))
        .flatMap(unpickled =>
          ZIO.environment[R].map { env =>
            makeStreamResponse(call(unpickled), env)
          }
        )
        .catchAll { case throwable: Throwable =>
          ZIO.succeed(Response.status(Status.InternalServerError))
        }
    })
  }

  def makeRouteNullaryStream[R, E: Pickler, A: Pickler](
    service: String,
    method: String,
    call: ZStream[R, E, A]
  ): Routes[R, Nothing] = {
    val service0 = service
    val method0  = method
    Routes((Method.GET / `service0` / `method0`) -> handler { (request: Request) =>
      ZIO.environment[R].map { env =>
        makeStreamResponse(call, env)
      }
    })
  }

  private def pickle[A: Pickler](value: A): Response = {
    val bytes: ByteBuffer = Pickle.intoBytes(value)
    val body              = Body.fromChunk(Chunk.fromByteBuffer(bytes))

    Response(status = Status.Ok, headers = Headers(bytesContent), body = body)
  }

  private def makeStreamResponse[A: Pickler, E: Pickler, R](
    stream: ZStream[R, E, A],
    env: ZEnvironment[R]
  ): Response = {
    val responseStream: ZStream[Any, Nothing, Byte] =
      stream
        .map(ZioResponse.succeed)
        .catchAllCause(causeToResponseStream(_))
        .mapConcatChunk { a =>
          Chunk.fromByteBuffer(Pickle.intoBytes(a))
        }
        .provideEnvironment(env)

    Response(body = Body.fromStream(responseStream))
  }

  private def causeToResponseStream[E: Pickler](cause: Cause[E]): UStream[ZioResponse[E, Nothing]] =
    cause.find {
      case Cause.Fail(failure, _) => ZStream(ZioResponse.fail(failure))
      case Cause.Die(die, _)      => ZStream(ZioResponse.die(die))
      // TODO: Fix ids.head
      case Cause.Interrupt(fiberId, _) => ZStream(ZioResponse.interrupt(fiberId.ids.head))
    }.get

  private def causeToResponseZio[E: Pickler](cause: Cause[E]): UIO[ZioResponse[E, Nothing]] =
    cause.find {
      case Cause.Fail(failure, _)      => ZIO.succeed(ZioResponse.fail(failure))
      case Cause.Die(die, _)           => ZIO.succeed(ZioResponse.die(die))
      case Cause.Interrupt(fiberId, _) => ZIO.succeed(ZioResponse.interrupt(fiberId.ids.head))
    }.get
}

object CustomPicklers {
  implicit val nothingPickler: Pickler[Nothing] = new Pickler[Nothing] {
    override def pickle(obj: Nothing)(implicit state: PickleState): Unit = throw new Error("IMPOSSIBLE")
    override def unpickle(implicit state: UnpickleState): Nothing        = throw new Error("IMPOSSIBLE")
  }

  implicit val datePickler: Pickler[Instant] =
    transformPickler((t: Long) => Instant.ofEpochMilli(t))(_.toEpochMilli)

  // local date time
  implicit val localDateTimePickler: Pickler[java.time.LocalDateTime] =
    transformPickler((t: Long) =>
      java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(t), java.time.ZoneId.of("UTC"))
    )(_.toInstant(java.time.ZoneOffset.UTC).toEpochMilli)

}
