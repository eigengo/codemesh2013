package org.eigengo.cm.api

import akka.actor.Props
import spray.can.Http
import akka.io.IO
import org.eigengo.cm.core.Core

trait Api {
  this: Core =>
}