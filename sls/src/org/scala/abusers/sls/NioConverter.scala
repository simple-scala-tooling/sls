package org.scala.abusers.sls

import java.net.URI
import scala.annotation.targetName

object NioConverter {
  extension (uri: bsp.URI) @targetName("bspAsNio") def asNio: URI = URI.create(uri.value)
}
