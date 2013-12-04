package org.eigengo.cm.core

import akka.actor._
import scala.concurrent.{ ExecutionContext, Future }
import com.github.sstone.amqp.{ ConnectionOwner, RpcClient }
import akka.util.Timeout
import com.github.sstone.amqp.Amqp.Publish
import com.github.sstone.amqp.RpcClient.Response
import scala.Some
import com.github.sstone.amqp.RpcClient.Request
import com.github.sstone.amqp.Amqp.Delivery
import spray.json.{ JsonParser, JsonReader, DefaultJsonProtocol }

private[core] object RecogSessionActor {

  // receive image to be processed
  private[core] case class Image(image: Array[Byte]) extends AnyVal
  // receive chunk of a frame to be processed
  private[core] case class Frame(frameChunk: Array[Byte]) extends AnyVal

  // FSM states
  private[core] sealed trait State
  private[core] case object Idle extends State
  private[core] case object Completed extends State
  private[core] case object Aborted extends State
  private[core] case object Active extends State

  // FSM data
  private[core] sealed trait Data
  private[core] case object Empty extends Data
  private[core] case class Starting(minCoins: Int) extends Data
  private[core] case class Running(decoder: DecoderContext) extends Data

  // CV responses
  private[core] case class Point(x: Int, y: Int)
  private[core] case class Coin(center: Point, radius: Double)
  private[core] case class CoinResponse(coins: List[Coin], succeeded: Boolean)

}


/**
 * This actor deals with the states of the recognition session. We use FSM here--even though
 * we only have a handful of states, recognising things sometimes needs many more states
 * and using ``become`` and ``unbecome`` of the ``akka.actor.ActorDSL._`` would be cumbersome.
 *
 * @param amqpConnection the AMQP connection we will use to create individual 'channels'
 * @param jabberActor the actor that will receive our output
 */
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends
  Actor with FSM[RecogSessionActor.State, RecogSessionActor.Data] {
  import RecogSessionActor._
  import CoordinatorActor._
  import scala.concurrent.duration._
  import context.dispatcher

  // default timeout for all states
  val stateTimeout = 60.seconds

  val emptyBehaviour: StateFunction = { case _ => stay() }

  val amqp = ConnectionOwner.createChildActor(amqpConnection, Props(new RpcClient()))

  startWith(Idle, Empty)

  when(Idle, stateTimeout) {
    case Event(Begin(minCoins), _) =>
      sender ! self.path.name
      goto(Active)
  }

  when(Active, stateTimeout) {
    case Event(Frame(frame), Starting(minCoins)) =>
      // Frame with no decoder yet. We will be needing the H264DecoderContext.
      val decoder = new H264DecoderContext(countCoins(minCoins))
      decoder.decode(frame, true)
      stay() using Running(decoder)
    case Event(Frame(frame), Running(decoder)) if frame.length > 0 =>
      // Frame with existing decoder. Just decode. (Teehee--I said ``Just``.)
      decoder.decode(frame, true)
      stay()
    case Event(Frame(_), Running(decoder)) =>
      // Last frame
      decoder.close()
      goto(Completed)

    case Event(Image(image), Starting(minCoins)) =>
      // Image with no decoder yet. We will be needing the ChunkingDecoderContext.
      val decoder = new ChunkingDecoderContext(countCoins(minCoins))
      decoder.decode(image, false)
      stay() using Running(decoder)
    case Event(Image(image), Running(decoder)) if image.length > 0 =>
      // Image with existing decoder. Shut up and apply.
      decoder.decode(image, false)
      stay()
    case Event(Image(_), Running(decoder)) =>
      // Empty image (following the previous case)
      decoder.close()
      goto(Completed)
  }

  when(Aborted)(emptyBehaviour)
  when(Completed)(emptyBehaviour)

  whenUnhandled {
    case Event(StateTimeout, _) => goto(Aborted)
  }

  onTransition {
    case _ -> Aborted => context.stop(self)
    case _ -> Completed => context.stop(self)
  }

  initialize()

  override def postStop(): Unit = {
    context.stop(amqp)
  }

  def countCoins(minCoins: Int)(f: Array[Byte]): Unit = ()
}

private[core] trait AmqpOperations {

  def amqpAsk(amqp: ActorRef)(exchange: String, routingKey: String, payload: Array[Byte])
             (implicit ctx: ExecutionContext): Future[String] = {
    import scala.concurrent.duration._
    import akka.pattern.ask

    implicit val timeout = Timeout(2.seconds)

    (amqp ? Request(Publish(exchange, routingKey, payload) :: Nil)).map {
      case Response(Delivery(_, _, _, body) :: _) =>
        new String(body)
    }
  }

}
