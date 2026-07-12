package org.scala.abusers.zincCli

import xsbti.Position
import xsbti.Problem
import xsbti.Reporter
import xsbti.Severity

/** Collects zinc problems for one compile run so they can be published as CSP diagnostics. Synchronized: the compiler
  * bridge may report from multiple threads.
  */
class ZincCliReporter extends Reporter {

  private val buf = scala.collection.mutable.ListBuffer.empty[Problem]

  override def comment(pos: Position, msg: String): Unit = System.err.println(msg)

  override def reset(): Unit = synchronized(buf.clear())

  override def hasWarnings(): Boolean = synchronized(buf.exists(_.severity == Severity.Warn))

  override def problems(): Array[Problem] = synchronized(buf.toArray)

  override def hasErrors(): Boolean = synchronized(buf.exists(_.severity == Severity.Error))

  override def printSummary(): Unit = ()

  override def log(problem: Problem): Unit = {
    synchronized(buf += problem)
    System.err.println(problem.message)
  }
}
