package zio.app

import zio.http.Path

final case class ClientConfig(
  authToken: Option[String],
  root: Path
)

object ClientConfig {
  val empty: ClientConfig =
    ClientConfig(None, Path.empty)
}
