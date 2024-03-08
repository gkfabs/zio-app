package zio.app

import zio.http.Routes
import zio.app.internal.macros.Macros

import scala.language.experimental.macros

object DeriveRoutes {
  def gen[Service]: Routes[Service, Nothing] = macro Macros.routes_impl[Service]
}
