package org.scala.abusers.zincCli

import xsbti.Position
import java.{util => ju}
import xsbti.Reporter
import xsbti.Problem

class ZincCliReporter extends Reporter {

  override def comment(pos: Position, msg: String): Unit = System.err.println(msg)

  override def reset(): Unit = ()

  override def hasWarnings(): Boolean = false

  override def problems(): Array[Problem] = List().toArray

  override def hasErrors(): Boolean = false

  override def printSummary(): Unit = ()

  override def log(problem: Problem): Unit = System.err.println(problem.message)
}
