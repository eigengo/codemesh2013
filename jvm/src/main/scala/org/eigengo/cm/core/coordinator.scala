package org.eigengo.cm.core

import akka.actor.{ActorSelection, ActorRef, Props, Actor}
import java.util.UUID
import akka.routing.FromConfig

object CoordinatorActor {
  // Begin the recognition session
  case class Begin(minCoins: Int)

  // Single ``image`` to session ``id``
  case class SingleImage(id: String, image: Array[Byte])

  // Chunk of H.264 stream to session ``id``
  case class FrameChunk(id: String, chunk: Array[Byte])

  // list ids of all sessions
  case object GetSessions

  // get information about a session ``id``
  case class GetInfo(id: String)
}

class CoordinatorActor(amqpConnection: ActorRef) extends Actor {
  import CoordinatorActor._

  // sends the messages out
  private val jabber = context.actorOf(Props[JabberActor])

  def receive = {
    case b @ Begin(_) =>
      val rsa = context.actorOf(Props(new RecogSessionActor(amqpConnection, jabber)), UUID.randomUUID().toString)
      rsa.forward(b)
    case SingleImage(id, image) =>
      sessionActorFor(id).tell(RecogSessionActor.Image(image), sender)
    case FrameChunk(id, chunk) =>
      sessionActorFor(id).tell(RecogSessionActor.Frame(chunk), sender)
    case GetSessions =>
      sender ! context.children.filter(jabber !=).map(_.path.name).toList
  }

  // finds an ``ActorRef`` for the given session.
  private def sessionActorFor(id: String): ActorSelection = context.actorSelection(id)


}
