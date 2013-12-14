package org.eigengo.cm.api

import akka.actor.{ActorContext, Props, Actor, ActorRef}
import spray.http._
import spray.http.HttpResponse
import spray.can.Http
import spray.can.Http.RegisterChunkHandler
import org.eigengo.cm.core.CoordinatorActor
import spray.routing.{RequestContext, Directives}
import spray.httpx.marshalling.{MetaMarshallers, BasicToResponseMarshallers}
import scala.concurrent.ExecutionContext

object RecogService {
  val Recog   = "recog"
  val MJPEG   = "mjpeg"
  val H264    = "h264"
}

trait BasicRecogService extends Directives with MetaMarshallers with BasicToResponseMarshallers {
  import scala.concurrent.duration._
  import akka.pattern.ask
  import CoordinatorActor._
  import RecogService._

  implicit val timeout = akka.util.Timeout(2.seconds)

  def normalRoute(coordinator: ActorRef)(implicit ec: ExecutionContext) =
    path(Recog) {
      post {
        complete((coordinator ? Begin(1)).mapTo[String])
      }
    } ~
    path(Recog / Rest) { sessionId =>
      post {
        entity(as[Array[Byte]]) { entity =>
          coordinator ! SingleImage(sessionId, entity)
          complete("{}")
        }
      }
    }
}

trait StreamingRecogService extends Directives with MetaMarshallers with BasicToResponseMarshallers {
  this: Actor =>

  import CoordinatorActor._
  import RecogService._

  def chunkedRoute(coordinator: ActorRef) = {
    def handleChunksWith(creator: => Actor): RequestContext => Unit = {
      val handler = context.actorOf(Props(creator))
      sender ! RegisterChunkHandler(handler)

      {_ => ()}
    }

    path(Recog / MJPEG / Rest) { sessionId =>
      post {
        handleChunksWith(new StreamingRecogServiceActor(coordinator, sessionId, SingleImage))
      }
    } ~
    path(Recog / H264 / Rest)  { sessionId =>
      post {
        handleChunksWith(new StreamingRecogServiceActor(coordinator, sessionId, FrameChunk))
      }
    }
  }

}

class RecogServiceActor(coordinator: ActorRef) extends BasicRecogService with StreamingRecogService with Actor {
  import context.dispatcher
  val normal = normalRoute(coordinator)
  val chunked = chunkedRoute(coordinator)

  def receive: Receive = {
    // clients get connected to self (singleton handler)
    case _: Http.Connected => sender ! Http.Register(self)
    // POST to /recog/...
    case request: HttpRequest => normal(RequestContext(request, sender, request.uri.path).withDefaultSender(sender))
    // stream begin to /recog/[h264|mjpeg]/:id
    case ChunkedRequestStart(request) => chunked(RequestContext(request, sender, request.uri.path).withDefaultSender(sender))
  }

}

class StreamingRecogServiceActor[A](coordinator: ActorRef, sessionId: String, message: (String, Array[Byte]) => A) extends Actor {

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

