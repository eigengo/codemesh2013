package org.eigengo.cm.api

import akka.actor.{Props, Actor, ActorRef}
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

  def receive: Receive = {
    // clients get connected to self (singleton handler)
    case _: Http.Connected =>
      sender ! Http.Register(self)

    // POST to /recog/...
    case HttpRequest(HttpMethods.POST, uri, _, entity, _) =>
      val client = sender
      uri.path.toString() match {
        case RootUri =>
          (coordinator ? Begin(1)).mapTo[String].onComplete {
            case Success(sessionId) => client ! HttpResponse(entity = sessionId)
            case Failure(ex) => client ! HttpResponse(entity = ex.getMessage, status = StatusCodes.InternalServerError)
          }
        case StaticUri(sessionId) =>
          coordinator ! SingleImage(sessionId, entity.data.toByteArray)
      }

    // stream begin to /recog/[h264|mjpeg]/:id
    case ChunkedRequestStart(HttpRequest(HttpMethods.POST, uri, _, entity, _)) =>
      val streamer = uri.path.toString() match {
        case MJPEGUri(sessionId) => context.actorOf(Props(new StreamingRecogService(coordinator, sessionId, SingleImage)))
        case H264Uri(sessionId)  => context.actorOf(Props(new StreamingRecogService(coordinator, sessionId, FrameChunk)))
      }
      sender ! RegisterChunkHandler(streamer)

    // all other requests
    case HttpRequest(method, uri, _, _, _) =>
      sender ! HttpResponse(entity = s"No such endpoint $method at $uri. That's all we know.", status = StatusCodes.NotFound)
  }

}

class StreamingRecogService[A](coordinator: ActorRef, sessionId: String, message: (String, Array[Byte]) => A) extends Actor {

  def receive = {
    // stream mid to /recog/[h264|mjpeg]/:id; see above ^
    case MessageChunk(data, _) =>
      // our work is done: bang it to the coordinator.
      coordinator ! message(sessionId, data.toByteArray)

    // stream end to /recog/[h264|mjpeg]/:id; see above ^^
    case ChunkedMessageEnd(_, _) =>
      // we say nothing back
      sender ! HttpResponse(entity = "{}")
      context.stop(self)
  }

}

