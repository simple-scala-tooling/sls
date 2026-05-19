package org.scala.abusers.sls.index

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import org.scala.abusers.sls.SourceUri

object IndexedSymbolCodecs {

  given JsonValueCodec[SymbolId] = new JsonValueCodec[SymbolId] {
    def decodeValue(in: JsonReader, default: SymbolId): SymbolId = SymbolId(in.readString(null))
    def encodeValue(x: SymbolId, out: JsonWriter): Unit          = out.writeVal(x.value)
    def nullValue: SymbolId                                      = null.asInstanceOf[SymbolId]
  }

  given JsonValueCodec[SourceUri] = new JsonValueCodec[SourceUri] {
    def decodeValue(in: JsonReader, default: SourceUri): SourceUri = SourceUri(in.readString(null))
    def encodeValue(x: SourceUri, out: JsonWriter): Unit           = out.writeVal(x.toLspUri)
    def nullValue: SourceUri                                       = null.asInstanceOf[SourceUri]
  }

  given JsonValueCodec[List[IndexedSymbol]] = JsonCodecMaker.make(
    CodecMakerConfig.withDiscriminatorFieldName(Some("_kind"))
  )
}
