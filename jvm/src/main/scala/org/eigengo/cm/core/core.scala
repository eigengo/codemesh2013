package org.eigengo.cm.core

import akka.actor.{ Props, ActorSystem }
import com.rabbitmq.client.ConnectionFactory
import com.github.sstone.amqp.ConnectionOwner

trait Core {

  implicit lazy val system = ActorSystem("recog")

  val amqpConnectionFactory = new ConnectionFactory()
  amqpConnectionFactory.setHost("localhost")

  lazy val amqpConnection = system.actorOf(Props(new ConnectionOwner(amqpConnectionFactory)))

  lazy val coordinator = system.actorOf(Props(new CoordinatorActor(amqpConnection)), "coordinator")

}
