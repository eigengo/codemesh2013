package org.eigengo.cm.api

import akka.actor.Props
import spray.can.Http
import akka.io.IO
import org.eigengo.cm.core.Core

trait Api {
  this: Core =>

  // our endpoints
  val recogService = system.actorOf(Props(new RecogServiceActor(coordinator)))

  IO(Http)(system) ! Http.Bind(recogService, "0.0.0.0", port = 8080)
}