package uk.co.odinconsultants.mss

import weaver.{FunSuite, SimpleIOSuite}
import weaver.scalacheck.Checkers
import cats.effect.IO
import cats.data.NonEmptyList
import StatefulStreams.WordCount

object StatefulStreamsSuite extends SimpleIOSuite with Checkers:
  test("stream produces word count") {
    val expectedWordCount: Map[String, Int] = Map("a" -> 1, "b" -> 2, "c" -> 3)
    val input: List[String] = List("c", "a", "c", "b", "b", "c")
    for {
      xs <- StatefulStreams.orderedStream(input).compile.last
    } yield expect.same(xs.get, expectedWordCount)
  }

