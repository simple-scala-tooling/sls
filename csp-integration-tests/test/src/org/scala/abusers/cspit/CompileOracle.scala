package org.scala.abusers.cspit

import cats.effect.IO
import cats.syntax.all.*

import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** The Tier-Z convergence oracle (`ai/test-plan.md`): after any sequence of incremental compiles, the output must
  * equal a clean compile of the final sources — otherwise "delete the analysis store and rebuild" would fix
  * something, which is the bug.
  */
object CompileOracle {

  /** Jar entry name -> SHA-256 of its bytes. Content-only on purpose: zip metadata (timestamps, ordering) is not part
    * of the contract.
    */
  def jarEntries(jar: os.Path): IO[Map[String, String]] = IO.blocking {
    val zip = new java.util.zip.ZipFile(jar.toIO)
    try
      zip.entries.asScala
        .filterNot(_.isDirectory)
        .map { entry =>
          val bytes  = zip.getInputStream(entry).readAllBytes()
          val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
          entry.getName -> digest.map("%02x".format(_)).mkString
        }
        .toMap
    finally zip.close()
  }

  /** Clean compile of `sources` on a fresh server and fresh scope — the oracle side of the comparison. */
  def cleanOutput(sources: (String, String)*): IO[Map[String, String]] =
    (CspTestClient.spawn, CspWorkspace.resource).tupled.use { case (h, ws) =>
      for {
        _      <- sources.toList.traverse_ { case (name, content) => ws.write(name, content) }
        result <- h.compile(s"oracle-${System.nanoTime()}", ws.root)
        _      <- IO.raiseWhen(result.status != org.scala.abusers.csp.CompilationStatus.SUCCEEDED)(
          new RuntimeException(s"oracle compile did not succeed: ${result.status}")
        )
        entries <- jarEntries(os.Path(result.outputJar))
      } yield entries
    }
}
