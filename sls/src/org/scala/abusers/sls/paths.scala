package org.scala.abusers.sls

import java.net.{URI => JavaURI}
import java.nio.file.{Path => NioPath}

opaque type SourceUri = JavaURI

object SourceUri {
  def apply(uri: JavaURI): SourceUri      = uri
  def apply(uriString: String): SourceUri = JavaURI.create(uriString)

  extension (su: SourceUri) {
    def toURI: JavaURI              = su
    def toLspUri: String            = su.toString
    def toBspUri: bsp.URI           = bsp.URI(su.toString)
    def toPath: AbsolutePath        = AbsolutePath(NioPath.of(su.getPath))
    def toFs2Path: fs2.io.file.Path = fs2.io.file.Path.fromNioPath(NioPath.of(su.getPath))
  }
}

opaque type AbsolutePath = NioPath

object AbsolutePath {
  def apply(path: NioPath): AbsolutePath      = path
  def apply(pathString: String): AbsolutePath = NioPath.of(pathString)

  extension (ap: AbsolutePath) {
    def toNioPath: NioPath                   = ap
    def toSourceUri: SourceUri               = SourceUri(ap.toUri)
    def resolve(other: String): AbsolutePath = AbsolutePath(ap.resolve(other))
    def toFile: java.io.File                 = ap.toFile
    def exists: Boolean                      = java.nio.file.Files.exists(ap)
    def deleteRecursively: Unit              =
      if java.nio.file.Files.exists(ap) then
        java.nio.file.Files
          .walk(ap)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(java.nio.file.Files.deleteIfExists(_))
  }
}

extension (td: lsp.TextDocumentIdentifier) {
  def sourceUri: SourceUri = SourceUri(JavaURI.create(td.uri))
}

extension (td: lsp.TextDocumentItem) {
  def sourceUri: SourceUri = SourceUri(JavaURI.create(td.uri))
}

extension (td: lsp.VersionedTextDocumentIdentifier) {
  def sourceUri: SourceUri = SourceUri(JavaURI.create(td.uri))
}

extension (u: bsp.URI) {
  def toSourceUri: SourceUri = SourceUri(JavaURI.create(u.value))
}

extension (u: JavaURI) {
  def toSourceUri: SourceUri = SourceUri(u)
}

extension (p: NioPath) {
  def toSourceUri: SourceUri = SourceUri(p.toUri)
}
