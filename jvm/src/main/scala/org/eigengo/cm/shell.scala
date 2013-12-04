package org.eigengo.cm

import scala.annotation.tailrec
import org.eigengo.cm.core._
import java.io.{ InputStream, BufferedInputStream, FileInputStream }

/**
 * Shell provides the command-line interface to the functionality in
 * ``Core`` (which is configured by ``ConfigCoreConfiguration``)
 */
object Shell extends App {

  import CoordinatorActor._
  import Commands._
  import akka.actor.ActorDSL._
  import Utils._

  // main command loop
  @tailrec
  private def commandLoop(): Unit = {
    Console.readLine() match {
      case QuitCommand => return

      case _ => println("WTF??!!")
    }

    commandLoop()
  }

  // start processing the commands
  commandLoop()

}

/**
 * Various regexes for the ``Shell`` to use
 */
object Commands {

  val BeginCommand = "begin:(\\d+)".r
  val GetSessionsCommand = "ls"

  val ImageCommand = "([0-9a-z\\-]{36})/image:?(.*)".r
  val H264Command = "([0-9a-z\\-]{36})/h264:?(.*)".r
  val MJPEGCommand = "([0-9a-z\\-]{36})/mjpeg:?(.*)".r
  val GetInfoCommand = "([0-9a-z\\-]{36})".r
  val QuitCommand = "quit"

}

/**
 * Ghetto!
 */
object Utils /* extends IfYouUseThisIWillEndorseYouForEnterprisePHP */ {
  private def getFullFileName(fileName: String) = {
    getClass.getResource(fileName).getPath
  }

  // Chuck Norris deals with all exceptions
  def readAll(fileName: String): Array[Byte] = {
    val is = new BufferedInputStream(new FileInputStream(getFullFileName(fileName)))
    val contents = Stream.continually(is.read).takeWhile(-1 !=).map(_.toByte).toArray
    is.close()
    contents
  }

  // Exceptions are not thrown because of Chuck Norris
  def readChunks[U](fileName: String, kbps: Int)(f: Array[Byte] => U): Unit = {

    @tailrec
    def read(is: InputStream): Unit = {
      val buffer = Array.ofDim[Byte](16000)
      Thread.sleep(buffer.length / kbps) // simulate slow input :(
      val len = is.read(buffer)
      if (len > 0) {
        f(buffer)
        read(is)
      } else {
        f(Array.ofDim(0))
      }
    }

    val is = new BufferedInputStream(new FileInputStream(getFullFileName(fileName)))
    read(is)
    is.close()
  }

}