package org.scala.abusers.sls

import java.net.URI
import scala.annotation.targetName

object NioConverter {
  // extension (documentUri: DocumentUri) @targetName("lspAsNio") def asNio: URI = URI.create(documentUri.value)
  extension (uri: bsp.URI) @targetName("bspAsNio") def asNio: URI             = URI.create(uri.value)
}
