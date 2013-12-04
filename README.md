#Akka in heterogeneous environments

It'll be a show.

#Counting coins

We have a video with coins on a table. We want to process the video, count the coins, and send IM on change of the count. 

In summary, we are building this system:

```
H.264 -- (HTTP) --> Scala/Akka -- (AMQP) --> C++/CUDA 
                        |                       |
                        +<------- (AMQP) -------+
                        |
                        +------- (Jabber) ------> 
```

The main components:

* Scala & Akka processing core (Maintain multiple recognition sessions, deal with integration points.)
* Spray for the REST API (Chunked HTTP posts to receive the video.)
* AMQP connecting the native components (Array[Byte] out; JSON in.)
* C++ / CUDA for the image processing (OpenCV with CUDA build. Coin counting logic.)
* iOS client that sends the H.264 stream (Make chunked POST request to the Spray endpoint.)

#What our code does
We support multiple recognition sessions; each session maintains its own state. The session _coordinator_ starts new sessions and routes request to existing sessions.

```
            ( Begin(params)        )
??? ----->  ( SingleImage(id, ...) )  -----> [[ Coordinator ]]
            ( FrameChunk(id, ...)  )                |
                                               +----+----+---- [[ Jabber ]]
                                               |         | 
                                       ...  [[ S ]]   [[ S ]]  ...
                                               |         |
                                               |         |
                                            [[ A ]]   [[ A ]]
```

The coordinator receives the ``Begin`` message and creates a _session actor_, passing it the _jabber actor_; the _session actor_ in turn creates connection to RabbitMQ. On receiving the ``Begin`` message, the _session actor_ replies to the sender with the id of the session.

The _???_ component is responsible for initiating these messages; it is either a command-line app or it is a complete REST server app.

#Let's begin
Let's take a look at the shell application. It extends ``App`` and presents trivial user interface.

```scala
object Shell extends App {
  import CoordinatorActor._
  import Commands._
  import akka.actor.ActorDSL._
  import Utils._
    
  @tailrec
  def commandLoop(): Unit = {
    Console.readLine() match {
      case QuitCommand => return
      case _           => println("WTF??!!")
    }
    commandLoop()
  }

  commandLoop()
}
```

So far, not so good. We can run the ``Shell`` app, which starts the command loop. The command loop reads from the console. When we type ``quit``, it exits; otherwise, it prints ``WTF??!!``. We need to get it to do something interesting.

> groll next | 0ae1a6a

#Layers
To allow us to assemble the shells, we structure our code into two main traits: ``Core`` and ``Api``.

```scala
trait Core {
  // start the actor system
  implicit lazy val system = ActorSystem("recog")

  lazy val amqpConnectionFactory: ConnectionFactory = new ConnectionFactory()
  amqpConnectionFactory.setHost("localhost")

  lazy val amqpConnection = system.actorOf(Props(new ConnectionOwner(amqpConnectionFactory)))
  lazy val coordinator = system.actorOf(Props(new CoordinatorActor(amqpConnection)), "coordinator")
}
```

For now, we will only say that ``Api`` requires to be mixed-in with ``Core``.

```scala
trait Api {
  this: Core =>

}
```

The code in the ``Core`` trait assembles the "headless" part of our application. It starts the ``ActorSystem`` and creates the _coordinator_ and _AMQP_ actors.

> groll next | fa5cb99

#Core configuration
We don't want to hard-code things like the AMQP server host in our application. These need to be somehow externalised. But we may want to use different configuration for our tests; and different configuration in each test for that matter. So, we create

```scala
trait CoreConfiguration {

  def amqpConnectionFactory: ConnectionFactory

}
```

We give implementation of this trait that requires the ``system: ActorSystem`` to be available; and uses the Typesafe config to load the settings.

```scala
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
```

Finally, we require that ``Core`` mixes in the ``CoreConfiguration`` trait:

```scala
trait Core {
  this: CoreConfiguration =>

  ...
}
```

> groll next | 8059e8d

#Shell II
So, let's go back to the ``Shell`` ``App`` and mix in the traits that contian the headless parts of our application; and add the necessary call to shutdown the ``ActorSystem``.

```scala
object Shell extends App with Core with ConfigCoreConfiguration {

   ...

   commandLoop()
   system.shutdown()
}
```

> groll next | ca55eec

#Beginning a session
The coordinator is responsible for the sessions; begins one when it receives the ``Begin`` message. The session itself lives in its own actor: a child of the coordinator:

```scala
class CoordinatorActor(amqpConnection: ActorRef) extends Actor {
  import CoordinatorActor._

  private val jabber = context.actorOf(Props[JabberActor])

  def receive = {
    case b@Begin(_) =>
      val rsa = context.actorOf(Props(new RecogSessionActor(amqpConnection, jabber)), UUID.randomUUID().toString)
      rsa.forward(b)
      ...
  }
}
```

When we receive the ``Begin`` message, the coordinator creates a new child actor for the session and forwards it the same message.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] with

  import RecogSessionActor._
  import scala.concurrent.duration._
  import context.dispatcher

  // default timeout for all states
  val stateTimeout = 60.seconds

  // we start idle and empty and then progress through the states
  startWith(Idle, Empty)

  when(Idle, stateTimeout) {
    case Event(Begin(minCoins), _) =>
      sender ! self.path.name
      goto(Active) using Running(minCoins, None)
  }

  initialize()

}
```

This is the important stuff. The ``CoordinatorActor`` creates a new ``RecogSessionActor``; forwards it the received ``Begin`` message. The newly created ``RecogSessionActor`` replies with its ``name`` to the original sender.

> groll next | b0771c5

#Shell III
We will now need to add the begin command to the shell.

```scala
object Shell extends App with Core with ConfigCoreConfiguration {

  import CoordinatorActor._
  import Commands._
  import akka.actor.ActorDSL._
  import Utils._

  @tailrec
  private def commandLoop(): Unit = {
    Console.readLine() match {
      case QuitCommand                => return
      case BeginCommand(count)        => coordinator ! Begin(count.toInt)

      case _                          => println("WTF??!!")
    }

    commandLoop()
  }

  commandLoop()
  system.shutdown()
}
```

_Does anyone know how to display the response?_ We can use the _ask_ pattern, or we can simply create an actor in ``commandLoop`` to be used as the sender of all messages.

```scala
object Shell extends App with Core with ConfigCoreConfiguration {

  import CoordinatorActor._
  import Commands._
  import akka.actor.ActorDSL._
  import Utils._

  implicit val _ = actor(new Act {
      become {
        case x => println(">>> " + x)
      }
    })

  @tailrec
  def commandLoop(): Unit = {
    ...
  }
}
```

Now the ``!`` function can find the implicit ``ActorRef`` to be used as the sender. And hence, when we now type ``begin:1``, we will see the newly created session id.

> groll next | 8ae6326

#Listing the sessions
Let's handle the command to list the sessions; in other words, when we send the coordinator the ``GetSessions`` command, we expect to get a list of ids of the active sessions.

```scala
object Shell extends App with Core with ConfigCoreConfiguration {

  import CoordinatorActor._
  import Commands._
  import akka.actor.ActorDSL._
  import Utils._

  ...

  @tailrec
  def commandLoop(): Unit = {
    ...
    case GetSessionsCommand         => coordinator ! GetSessions
    ...
  }
}
```

_What now? Any hints?_

We want to get all children, skipping the ``jabber`` actor; and then map them to just their names. Finally, we convert the iterable to ``List``.

```scala
class CoordinatorActor(amqpConnection: ActorRef) extends Actor {
  import CoordinatorActor._

  val jabber = context.actorOf(Props[JabberActor].withRouter(FromConfig()), "jabber")

  def receive = {
    case b@Begin(_) =>
      val rsa = context.actorOf(Props(new RecogSessionActor(amqpConnection, jabber)), UUID.randomUUID().toString)
      rsa.forward(b)
    case GetSessions =>
      sender ! context.children.filter(jabber !=).map(_.path.name).toList
  }

}
```

(How cool is ``jabber !=``, eh?)

> groll next | 9420dbe

#Remaining commands
OK, let's complete the remaining commands in the Shell.

```scala
object Shell extends App with Core with ConfigCoreConfiguration {
  ...
  @tailrec
  private def commandLoop(): Unit = {
    Console.readLine() match {
      case QuitCommand                => return
      case BeginCommand(count)        => coordinator ! Begin(count.toInt)
      case GetSessionsCommand         => coordinator ! GetSessions
      case ImageCommand(id, fileName) => coordinator ! SingleImage(id, readAll(fileName))
      case H264Command(id, fileName)  => readChunks(fileName, 64)(coordinator ! FrameChunk(id, _))

      case _                          => println("WTF??!!")
    }
    commandLoop()
  }
  ...
}
```

We also need to handle the remaining commands in the ``CoordinatorActor``.

```scala
class CoordinatorActor(amqpConnection: ActorRef) extends Actor {
  import CoordinatorActor._

  // sends the messages out
  val jabber = context.actorOf(Props[JabberActor].withRouter(FromConfig()), "jabber")

  def receive = {
    case b@Begin(_) =>
      val rsa = context.actorOf(Props(new RecogSessionActor(amqpConnection, jabber)), UUID.randomUUID().toString)
      rsa.forward(b)
    case GetSessions =>
      sender ! context.children.filter(jabber !=).map(_.path.name).toList
      //                                      ^
      //                                      |
      //                                      well, shave my legs and call me grandma!
    case SingleImage(id, image) =>
      sessionActorFor(id).tell(RecogSessionActor.Image(image), sender)
    case FrameChunk(id, chunk) =>
      sessionActorFor(id).tell(RecogSessionActor.Frame(chunk), sender)
  }

  // finds an ``ActorRef`` for the given session.
  private def sessionActorFor(id: String): ActorSelection = context.actorSelection(id)

}
```

> groll next | 28d255d

#Recog session
The recog session is an FSM actor; it moves through different states depending on the messages it receives; it also maintains timeouts--when the user abandons a session, the session actor will remove itself once the timeout elapses.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] {

  import RecogSessionActor._
  import scala.concurrent.duration._
  import context.dispatcher

  // default timeout for all states
  val stateTimeout = 60.seconds

  // thank goodness for British spelling :)
  val emptyBehaviour: StateFunction = { case _ => stay() }

  // we start idle and empty and then progress through the states
  startWith(Idle, Empty)

  // when we receive the ``Begin`` even when idle, we become ``Active``
  when(Idle, stateTimeout) {
    case Event(Begin(minCoins), _) =>
      sender ! self.path.name
      goto(Active) using Running(minCoins, None)
  }

  // when ``Active``, we can process images and frames
  when(Active, stateTimeout) {
    case Event(message, data) =>
      goto(Completed)
  }

  // until we hit Aborted and Completed, which do nothing interesting
  when(Aborted)(emptyBehaviour)
  when(Completed)(emptyBehaviour)

  // unhandled events in the states
  whenUnhandled {
    case Event(StateTimeout, _) => goto(Aborted)
  }

  // go!
  initialize()
}
```

Complicated? Yes. Difficult to write & understand? No! 

> groll next | 6b2449d

#AMQP
But we're counting coins in images! All we've seen so far is some data pushing in Scala. We need to connect our super-smart (T&Cs apply) computer vision code.

It sits on the other end of RabbitMQ; when we send the broker a message to the ``cm.exchange`` exchange, using the ``count.key`` routing key, it will get routed to the running native code. The native code will pick up the message, take its payload; perform the coin counting using the hough circles transform and reply back with a JSON:

```json
{ 
   "coins":[{"center":{"x":123,"y":333}, "diameter":30.4}, {"center":{"x":100,"y":200}, "diameter":55}],
   "succeeded":true
}
```

In other words, we have the array of coins and indication whether we were able to successfully process the image.

So, let's wire in the AMQP connector and send it the messages.

###AMQP actor
We create the ``amqp`` actor whenever we create the ``RecogSessionActor``.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] {

  import RecogSessionActor._
  import scala.concurrent.duration._
  import context.dispatcher

  // default timeout for all states
  val stateTimeout = 60.seconds

  // thank goodness for British spelling :)
  val emptyBehaviour: StateFunction = { case _ => stay() }

  // make a connection to the AMQP broker
  val amqp = ConnectionOwner.createChildActor(amqpConnection, Props(new RpcClient()))

  ...

}
```

Because it is not a child of this actor (it is a child of the AMQP connection owner actor--don't ask!), we must remember to clean it up in the ``postStop()`` function.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] {

  ...

  // cleanup
  override def postStop() {
    context.stop(amqp)
  }

}
```

###Stopping

But wait? When **do** we stop? Intuitively, we sould like to stop this actor when we encounter a state timout or when we complete. That can all be defined as behaviour on transitions.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] {

  ...

  // cleanup
  onTransition {
    case _ -> Aborted => context.stop(self)
    case _ -> Completed => context.stop(self)
  }

  ...
}
```

Right ho. We can now write the code to send the images to the components on the other end of RabbitMQ.

> groll next | 017381a

#Sending images
The recognition can deal with H.264 stream as well as individual images, but once you've sent the first frame of the stream or the first image, you must keep sending more frames or more images. In other words, you cannot combine H.264 and static frames.
This means that we can set up the ``DecoderContext`` on first input and simply keep it in our FSM state.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] {

  ...
  // when ``Active``, we can process images and frames
  when(Active, stateTimeout) {
    case Event(Image(image), Starting(minCoins)) =>
      val decoder = new ChunkingDecoderContext(countCoins(minCoins))
      decoder.decode(image, true)
      stay() using Running(decoder)
    case Event(Image(image), Running(decoder)) if image.length > 0  =>
      decoder.decode(image)
      stay()
    case Event(Image(_), Running(decoder)) =>
      decoder.close()
      goto(Completed)

    case Event(Frame(frame), Starting(minCoins)) =>
      val decoder = new H264DecoderContext(countCoins(minCoins))
      decoder.decode(frame, true)
      stay() using Running(decoder)
    case Event(Frame(frame), Running(decoder)) if frame.length > 0 =>
      decoder.decode(frame, true)
      stay()
    case Event(Frame(_), Running(decoder)) =>
      decoder.close()
      goto(Completed)
  }
}
```

Now, the interesting function is the ``f: Array[Byte] => U`` function, which the current decoder calls when it has complete frame or image. Its job is to take the decoded frame and send it over RabbitMQ for processing.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] {

  ...
  // when ``Active``, we can process images and frames
  when(Active, stateTimeout) {
    case Event(Image(image, end), r@Running(minCoins, None)) if image.length > 0  =>
      val decoder = new ChunkingDecoderContext(countCoins(minCoins))
      decoder.decode(image, end)
      stay() using r.copy(decoder = Some(decoder))
    ...
  }

  def countCoins(minCoins: Int)(f: Array[Byte]): Unit = ()
}
```

> groll next | 9c632af

#Asking AMQP
Let's keep peeling the onion and define the ``amqpAsk`` function. We'll put it in the ``AmqpOperations`` trait.

```scala
private[core] trait AmqpOperations {

  protected def amqpAsk(amqp: ActorRef)
                       (exchange: String, routingKey: String, payload: Array[Byte])
                       (implicit ctx: ExecutionContext): Future[String] = {
    import scala.concurrent.duration._
    import akka.pattern.ask

    val builder = new AMQP.BasicProperties.Builder
    implicit val timeout = Timeout(2.seconds)

    (amqp ? Request(Publish(exchange, routingKey, payload, Some(builder.build())) :: Nil)).map {
      case Response(Delivery(_, _, _, body)::_) => new String(body)
      case x => sys.error("Bad match " + x)
    }
  }

}
```

Right. The ``amqp`` ``ActorRef`` behaves just like ordinary Akka actor, except it sends the message over AMQP. Mixed into our ``RecogSessionActor`` makes us ready to move on!

> groll next | 8409107

##Stringly-typed
Oh, the humanity! We have ``Future[String]`` returned from the ``amqpAsk`` function. Stringly-typed code is not a good thing to have; we know that the response will be a JSON; one that matches

```scala
private[core] case class Point(x: Int, y: Int)
private[core] case class Coin(center: Point, radius: Double)
private[core] case class CoinResponse(coins: List[Coin], succeeded: Boolean)
```

Let's no modify the ``amqpAsk`` to deal with it. _Any suggestions?_

##Stand back. I know typeclasses!
Specifically, typeclass ``JsonReader[A]``, whose instances can turn some JSON into instances of ``A``. We then leave the compiler to do the dirty work for us and select the appropriate ``JsonReader[A]`` in our ``amqpAsk``. We need to make it polymorphic and ask the compiler to implicitly give us instance of ``JsonReader`` for that ``A``. Of course, we're now returning ``Future[A]``.

```scala
private[core] trait AmqpOperations {

  protected def amqpAsk[A](amqp: ActorRef)
                       (exchange: String, routingKey: String, payload: Array[Byte])
                       (implicit ctx: ExecutionContext, reader: JsonReader[A]): Future[A] = {
    import scala.concurrent.duration._
    import akka.pattern.ask

    val builder = new AMQP.BasicProperties.Builder
    implicit val timeout = Timeout(2.seconds)

    (amqp ? Request(Publish(exchange, routingKey, payload, Some(builder.build())) :: Nil)).map {
      case Response(Delivery(_, _, _, body)::_) =>
        val s = new String(body)
        reader.read(JsonParser(s))
    }
  }

}
```

Goodie. But what about the instances of ``JsonReader`` for our ``CoinResponse`` and ``Coin``? We put them in yet another trait that we can mix in to our actor.

```scala
trait RecogSessionActorFormats extends DefaultJsonProtocol {
  import RecogSessionActor._

  implicit val PointFormat = jsonFormat2(Point)
  implicit val CoinFormat = jsonFormat2(Coin)
  implicit val CoinResponseFormat = jsonFormat2(CoinResponse)
}
```

Now, when used in the ``RecogSessionActor``, we're now all set:

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] with
  AmqpOperations with
  ImageEncoding with
  RecogSessionActorFormats {


  def countCoins(minCoins: Int)(f: Array[Byte]): Unit =
    amqpAsk[CoinResponse](amqp)("cm.exchange", "cm.coins.key", mkImagePayload(image)) onSuccess {
      case res => if (res.coins.size >= minCoins) jabberActor ! res
    }
}
```

> groll next | e9a7d10

#Let's now see...
We should be able to us the shell completely. Let's execute

```
begin:1
>>> 3ce43dc1-7397-4ab7-89ad-ecf70ebf681a
3ce43dc1-7397-4ab7-89ad-ecf70ebf681a/h264:/coins.mp4
...

quit
```

So, it works.

#REST
We have this funky, concurrent, scalable system; and yet, we use it from the command line. Not good. Let's give it some nice RESTful API that can deal with different clients. And, seeing how cool we all are, let's have iOS client.

Remember our ``Api`` trait? It's time to look in detail into what it does.

```scala
trait Api {
  this: Core =>

  // our endpoints
  val recogService = system.actorOf(Props(new RecogService(coordinator)))

  IO(Http)(system) ! Http.Bind(recogService, "0.0.0.0", port = 8080)

}
```

These incantations create the Spray-can server with the ``StreamingRecogService`` actor handling all the requests. Naturally, just like the ``Shell``, the ``StreamingRecogService`` needs to be given the ``ActorRef`` to the coordinator actor.

> groll next | 0fa89fd

#StreamingRecogService
The ``StreamingRecogService`` now receives the messages; deals with the details of HTTP and hands over to the ``coordinator``.

```scala
class RecogService(coordinator: ActorRef) extends Actor {
  import akka.pattern.ask
  import scala.concurrent.duration._
  import CoordinatorActor._
  import StreamingRecogService._

  import context.dispatcher

  implicit val timeout = akka.util.Timeout(2.seconds)

  def receive = {
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
```

And

```scala
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
```

Just a bit of typing, is all! Notice though that we handle HTTP chunks. In other words, we expect our clients to send us the video stream by parts, not in one big chunk. 

> groll next | 54e4c0e

#REST server app
Finally, we need to build another ``App`` object. This time, we will be starting the REST API. Looking at the ``Api`` and ``Core`` traits, nothing could be simpler! 

_Observe!_

```scala
object Rest extends App with Core with ConfigCoreConfiguration with Api
```

And that's it!

> groll next | d08a5f6

#Let's play!
I happen to have my iPhone here with the app installed; and I've pre-recorded a video. Let's see how it all behaves.

* Run the iOS app
* Observe the JVM console
* Observe RabbitMQ console

#Questions?

#Done

[@honzam399](https://twitter.com/honzam399) |
[janm@cakesolutions.net](mailto:janm@cakesolutions.net) |
[cakesolutions.net](http://www.cakesolutions.net) |
[github.com/janm399](https://github.com/janm399) |
[github.com/eigengo](https://github.com/eigengo)
