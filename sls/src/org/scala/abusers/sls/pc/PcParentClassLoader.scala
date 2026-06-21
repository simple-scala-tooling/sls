package org.scala.abusers.pc

/** Parent for the PC's `URLClassLoader`. Parent is the bootstrap loader, so `dotty.tools.*` and the stdlib load from
  * the PC's own jars (not sls's bundled scala3-compiler). Only the host<->PC bridge packages delegate to the host, so
  * they keep one class identity across the boundary.
  */
final class PcParentClassLoader(host: ClassLoader) extends ClassLoader(null) {

  private val sharedPrefixes = List("scala.meta.pc.", "org.eclipse.lsp4j", "com.google.gson")

  override def findClass(name: String): Class[?] =
    if sharedPrefixes.exists(name.startsWith) then host.loadClass(name)
    else throw new ClassNotFoundException(name)
}
