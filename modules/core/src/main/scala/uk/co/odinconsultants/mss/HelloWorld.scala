package uk.co.odinconsultants.mss

import cats.effect.{IO, IOApp}

object HelloWorld extends IOApp.Simple {
  val helloWorld: IO[String] = IO {
    val msg = "Hello world"
    println(msg)
    msg
  }
  def run: IO[Unit] = helloWorld.void
}
