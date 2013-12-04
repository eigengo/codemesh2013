package org.eigengo.cm.core

import akka.actor.{ Props, ActorSystem }
import com.rabbitmq.client.ConnectionFactory
import com.github.sstone.amqp.ConnectionOwner

trait CoreConfiguration {

  def amqpConnectionFactory: ConnectionFactory

}

trait ConfigCoreConfiguration extends CoreConfiguration {
  def system: ActorSystem

  // connection factory
  lazy val amqpConnectionFactory = {
    val amqpHost = system.settings.config.getString("cm.amqp.host")
    val cf = new ConnectionFactory()
    cf.setHost(amqpHost)
    cf
  }

}

trait Core {
  this: CoreConfiguration =>

  // start the actor system
  implicit lazy val system = ActorSystem("recog")

  // create a "connection owner" actor, which will try and reconnect automatically if the connection ins lost
  lazy val amqpConnection = system.actorOf(Props(new ConnectionOwner(amqpConnectionFactory)))

  // create the coordinator actor
  lazy val coordinator = system.actorOf(Props(new CoordinatorActor(amqpConnection)), "coordinator")

}
