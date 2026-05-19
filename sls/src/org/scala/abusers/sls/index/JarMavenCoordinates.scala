package org.scala.abusers.sls.index

import org.scala.abusers.sls.AbsolutePath

import java.util.Properties
import java.util.zip.ZipFile
import scala.util.Using

final case class MavenCoordinates(groupId: String, artifactId: String, version: String) {
  override def toString: String = s"$groupId:$artifactId:$version"
}

/** Reads the Maven `pom.properties` that Maven-built jars embed at
  * `META-INF/maven/<groupId>/<artifactId>/pom.properties`. Returns `None` if the jar wasn't built by Maven, the file
  * is missing or the properties don't contain all three coordinates.
  */
object JarMavenCoordinates {

  def read(jarPath: AbsolutePath): Option[MavenCoordinates] =
    Using.resource(new ZipFile(jarPath.toFile)) { zf =>
      val entries = zf.entries()
      var found: Option[MavenCoordinates] = None
      while (entries.hasMoreElements && found.isEmpty) {
        val entry = entries.nextElement()
        val name  = entry.getName
        if !entry.isDirectory && name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties") then {
          val props = new Properties()
          Using.resource(zf.getInputStream(entry))(props.load)
          for {
            g <- Option(props.getProperty("groupId"))
            a <- Option(props.getProperty("artifactId"))
            v <- Option(props.getProperty("version"))
          } found = Some(MavenCoordinates(g, a, v))
        }
      }
      found
    }
}
