package org.scala.abusers.pc

/** Debugging knob: override which `scala3-presentation-compiler_3` version the PC is loaded from, decoupled from the
  * build target's own Scala version. Resolved once at startup from system properties (env vars as fallback), so it is
  * toggled per launch with no recompile — same `-Dsls.*` convention as `sls.profiling`.
  *
  *   - `-Dsls.pc.version=<v>` (env `SLS_PC_VERSION`): global default applied to every module.
  *   - `-Dsls.pc.versions=<module>=<v>,<module>=<v>` (env `SLS_PC_VERSIONS`): per-module map, keyed by build-target
  *     display name; takes precedence over the global default.
  *
  * Resolution for a module is: per-module entry, else global default, else the target's own Scala version. Combined
  * with coursier's `ivy2Local` default repository this makes a locally `publishLocal`-ed PC build directly usable.
  */
final case class PcVersionOverride(perModule: Map[String, String], global: Option[String]) {

  /** Effective PC artifact version for `module`, falling back to `default` (the target's own Scala version). */
  def resolve(module: String, default: ScalaVersion): ScalaVersion =
    perModule.get(module).orElse(global).map(ScalaVersion(_)).getOrElse(default)

  val isActive: Boolean = perModule.nonEmpty || global.isDefined
}

object PcVersionOverride {

  val empty: PcVersionOverride = PcVersionOverride(Map.empty, None)

  /** Parse a `module=version,module=version` list; malformed entries are dropped. */
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

  /** Read the override from system properties / env at startup. */
  def fromEnv: PcVersionOverride =
    PcVersionOverride(
      perModule = read("sls.pc.versions", "SLS_PC_VERSIONS").map(parseMap).getOrElse(Map.empty),
      global = read("sls.pc.version", "SLS_PC_VERSION"),
    )
}
