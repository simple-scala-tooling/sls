package org.scala.abusers.pc

/** Overrides the `scala3-presentation-compiler_3` version, decoupled from the build target's Scala version. Per-module
  * (`-Dsls.pc.versions=mod=ver,...` / `SLS_PC_VERSIONS`, keyed by display name) over a global (`-Dsls.pc.version` /
  * `SLS_PC_VERSION`); falls back to the target's own version.
  */
final case class PcVersionOverride(perModule: Map[String, String], global: Option[String]) {

  def resolve(module: String, default: ScalaVersion): ScalaVersion =
    perModule.get(module).orElse(global).map(ScalaVersion(_)).getOrElse(default)
}

object PcVersionOverride {

  val empty: PcVersionOverride = PcVersionOverride(Map.empty, None)

  /** Parse `module=version,module=version`; malformed entries are dropped. */
  def parseMap(raw: String): Map[String, String] =
    raw
      .split(',')
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { entry =>
        entry.split("=", 2) match {
          case Array(k, v) if k.trim.nonEmpty && v.trim.nonEmpty => Some(k.trim -> v.trim)
          case _                                                 => None
        }
      }
      .toMap

  private def read(prop: String, env: String): Option[String] =
    Option(System.getProperty(prop)).orElse(Option(System.getenv(env))).map(_.trim).filter(_.nonEmpty)

  def fromEnv: PcVersionOverride =
    PcVersionOverride(
      perModule = read("sls.pc.versions", "SLS_PC_VERSIONS").map(parseMap).getOrElse(Map.empty),
      global = read("sls.pc.version", "SLS_PC_VERSION"),
    )
}
