package org.eigengo.cm.api

import akka.actor.{ Actor, ActorRef }
import spray.http._
import spray.http.HttpResponse
import scala.util.Success
import scala.util.Failure
import spray.can.Http
import spray.can.Http.RegisterChunkHandler
import org.eigengo.cm.core.CoordinatorActor

object StreamingRecogService {
  def makePattern(start: String) = (start + """(.*)""").r

  val RootUri   = "/recog"
  val MJPEGUri  = makePattern("/recog/mjpeg/")
  val H264Uri   = makePattern("/recog/h264/")
  val StaticUri = makePattern("/recog/static/")
}

class RecogService(coordinator: ActorRef) extends Actor {
  import akka.pattern.ask
  import scala.concurrent.duration._
  import CoordinatorActor._
  import StreamingRecogService._

  import context.dispatcher

  implicit val timeout = akka.util.Timeout(2.seconds)

  def receive: Receive = ???

}

class StreamingRecogService[A](coordinator: ActorRef, sessionId: String, message: (String, Array[Byte]) => A) extends Actor {
  def receive: Receive = ???
}

