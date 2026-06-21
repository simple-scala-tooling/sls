package org.scala.abusers.pc

/** Parent classloader for the presentation compiler's `URLClassLoader`.
  *
  * The host (sls) bundles a `scala3-compiler` of its own (transitively, via `scala3-tasty-inspector`), so
  * `dotty.tools.dotc.*` — including `InteractiveDriver` — lives on the host's application classloader. A plain
  * `URLClassLoader(pcJars, host)` delegates parent-first, so those compiler classes would load from the host's version
  * while the PC impl (`dotty.tools.pc.*`, only on the PC jars) loads from the PC's version. When the two versions differ
  * (e.g. a [[PcVersionOverride]], or simply a project on a different Scala version) they are binary-incompatible —
  * `dotty.tools.pc.CachingDriver` calling `new InteractiveDriver(...)` blows up with `NoSuchMethodError`.
  *
  * This classloader has the bootstrap loader as its parent (so only JDK classes resolve through it) and delegates
  * exactly the host↔PC bridge packages to the host loader, so those types keep a single class identity across the
  * boundary. Everything else — notably `dotty.tools.*` and the Scala stdlib — is hidden, forcing the PC's
  * `URLClassLoader` to load it from the PC's own jars.
  */
final class PcParentClassLoader(host: ClassLoader) extends ClassLoader(null) {

  // Types that cross the sls<->PC boundary (see PresentationCompilerDTOInterop): the mtags PC API, the lsp4j types
  // it returns, and gson (lsp4j's json layer). These must resolve to the host's classes, not the PC jars' copies.
  private val sharedPrefixes = List("scala.meta.pc.", "org.eclipse.lsp4j", "com.google.gson")

  override def findClass(name: String): Class[?] =
    if sharedPrefixes.exists(name.startsWith) then host.loadClass(name)
    else throw new ClassNotFoundException(name)
}
