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

  def receive: Receive = ???
}
