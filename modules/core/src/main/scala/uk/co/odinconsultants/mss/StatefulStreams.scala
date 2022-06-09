package uk.co.odinconsultants.mss

import fs2.Stream
import cats.effect.{IO, Ref}

object StatefulStreams {

  type WordCount = Map[String, Int]

  def update(old: WordCount, word: String): WordCount = old + (word -> (old.getOrElse(word, 0) + 1))

  def orderedStream(xs: List[String]): Stream[IO, WordCount] = {
    val initial: IO[Ref[IO, WordCount]] = Ref.of[IO, WordCount](Map.empty[String, Int])
    for {
      ref   <- Stream.eval(initial)
      word  <- Stream.emits(xs)
      io: IO[WordCount] = for {
        _ <- IO.println(word)
        changed <- ref.modify(old => (update(old, word), update(old, word)))
      } yield changed
      result <- Stream.eval(io)
    } yield {
      result
    }
  }

}
