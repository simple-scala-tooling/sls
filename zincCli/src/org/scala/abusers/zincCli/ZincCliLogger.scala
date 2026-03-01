package org.scala.abusers.zincCli

import java.{util => ju}
import xsbti.Logger
import ju.function.Supplier

class ZincCliLogger extends Logger {

  override def trace(exception: Supplier[Throwable]): Unit = System.err.println(exception.get)

  override def info(msg: Supplier[String]): Unit = System.err.println(msg.get())

  // debug should be grayed out
  override def debug(msg: Supplier[String]): Unit = System.err.println(s"\u001b[90m${msg.get()}\u001b[0m")

  override def error(msg: Supplier[String]): Unit = System.err.println(msg.get())

  override def warn(msg: Supplier[String]): Unit = System.err.println(msg.get())
}
