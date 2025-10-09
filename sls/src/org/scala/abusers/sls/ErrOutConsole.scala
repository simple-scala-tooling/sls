package org.scala.abusers.sls

import cats.effect.*
import cats.Show
import cats.effect.std.Console
import java.nio.charset.Charset

class ErrorOutConsole extends Console[IO] {
  override def readLineWithCharset(charset: Charset): IO[String] = IO.raiseError(sys.error("Not implemented"))

  override def print[A](a: A)(implicit S: Show[A]): IO[Unit] = IO.consoleForIO.error(a)

  override def println[A](a: A)(implicit S: Show[A]): IO[Unit] = IO.consoleForIO.errorln(a)

  override def error[A](a: A)(implicit S: Show[A]): IO[Unit] = IO.consoleForIO.error(a)

  override def errorln[A](a: A)(implicit S: Show[A]): IO[Unit] = IO.consoleForIO.errorln(a)
}
