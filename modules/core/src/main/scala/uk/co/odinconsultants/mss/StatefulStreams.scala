package uk.co.odinconsultants.mss

import fs2.Stream
import cats.effect.{IO, Ref}
import uk.co.odinconsultants.mss.StatefulStreams.WordCount

object StatefulStreams {

  type WordCount = Map[String, Int]

  def update(old: WordCount, word: String): WordCount = old + (word -> (old.getOrElse(word, 0) + 1))

  def updateIO(ref: Ref[IO, WordCount], word: String): IO[WordCount] = for {
    _       <- IO.println(word)
    changed <- ref.modify(old => (update(old, word), update(old, word)))
  } yield changed

  def orderedStream(xs: List[String]): Stream[IO, WordCount] = {
    val initial: IO[Ref[IO, WordCount]] = Ref.of[IO, WordCount](Map.empty[String, Int])
    for {
      ref     <- Stream.eval(initial)
      word    <- Stream.emits(xs)
      result  <- Stream.eval(updateIO(ref, word))
    } yield {
      result
    }
  }

}
