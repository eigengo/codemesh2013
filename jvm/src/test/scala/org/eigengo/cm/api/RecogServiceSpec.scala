package org.eigengo.cm.api

import spray.testkit.Specs2RouteTest
import org.specs2.mutable.Specification
import akka.actor.{Props, Actor}
import org.eigengo.cm.core.CoordinatorActor.Begin

class RecogServiceSpec extends Specification with Specs2RouteTest with BasicRecogService {

  class TestCoordinatorActor extends Actor {
    def receive: Receive = {
      case Begin(_) => sender ! "a10b2f45-87dd-4fe1-accf-3361763c1553"
    }
  }

  "Basic recog service" should {
    val coordinator = system.actorOf(Props(new TestCoordinatorActor))

    "return the session ID on post" in {
      Post("/recog") ~> normalRoute(coordinator) ~> check {
        responseAs[String] mustEqual "a10b2f45-87dd-4fe1-accf-3361763c1553"
      }
    }

  }

}
