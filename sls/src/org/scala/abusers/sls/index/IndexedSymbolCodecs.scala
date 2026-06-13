package org.scala.abusers.sls.index

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import org.scala.abusers.sls.SourceUri

object IndexedSymbolCodecs {

  /** SourceUri is opaque-over-String, so still needs an explicit codec — JsonCodecMaker can't derive opaque types. */
  given JsonValueCodec[SourceUri] = new JsonValueCodec[SourceUri] {
    def decodeValue(in: JsonReader, default: SourceUri): SourceUri = SourceUri(in.readString(null))
    def encodeValue(x: SourceUri, out: JsonWriter): Unit           = out.writeVal(x.toLspUri)
    /** jsoniter requires a sentinel for nullable contexts. SourceUri is never nullable on real read paths
      * (we always wrap a non-null String), so this cast is unreachable in practice — it's API ceremony. */
    def nullValue: SourceUri                                       = null.asInstanceOf[SourceUri]
  }

  /** SymbolId / Member are case classes; JsonCodecMaker derives a structural object codec automatically through the
    * top-level `List[IndexedSymbol]` codec below. The on-disk format is JSON objects, not opaque strings — a breaking
    * change vs. Phase 0. [[DepIndexCache.Version]] is bumped to invalidate old caches.
    */
  given JsonValueCodec[List[IndexedSymbol]] = JsonCodecMaker.make(
    CodecMakerConfig.withDiscriminatorFieldName(Some("_kind"))
  )
}
