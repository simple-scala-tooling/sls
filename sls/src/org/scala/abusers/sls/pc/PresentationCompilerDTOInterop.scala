package org.scala.abusers.pc

import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.scala.abusers.sls.DocumentState
import smithy4s.json.Json
import smithy4s.schema.Schema
import smithy4s.Blob

import java.net.URI
import java.util.Collections
import scala.meta.pc.CancelToken
import scala.meta.pc.OffsetParams

object PresentationCompilerDTOInterop {
  val gson =
    MessageJsonHandler(Collections.emptyMap).getDefaultGsonBuilder().create()

  // We need to translate lsp4j / scalameta into langoustine and vice versa. It may be good idea to use chimney here

  // FIXME VVVVV Missing completion for langoustine
  def convert[In, Out: Schema](x: In): Out = Json
    .read[Out](Blob(gson.toJson(x, x.getClass)))
    .getOrElse(
      sys.error(s"Failed to convert $x to ${summon[Schema[Out]].shapeId} using gson: ${gson.toJson(x, x.getClass)}")
    )

  def toOffsetParams(position: lsp.Position, doc: DocumentState, cancelToken: CancelToken): OffsetParams = {
    import doc.*
    new OffsetParams {
      override def toString(): String =
        s"""offset: $offset
           |$uri
           |$text""".stripMargin
      def offset(): Int        = position.toOffset
      def text(): String       = doc.content
      def token(): CancelToken = cancelToken
      def uri(): URI           = doc.uri
    }
  }

  trait WithPosition[A] {
    def position(params: A): lsp.Position
  }

  trait WithRange[A] {
    def range(params: A): lsp.Range
  }

  trait WithURI[A] {
    def uri(params: A): URI
  }

  trait PositionWithURI[A] extends WithPosition[A] with WithURI[A]
  trait RangeWithURI[A]    extends WithRange[A] with WithURI[A]

  given PositionWithURI[lsp.CompletionParams] with {
    def position(params: lsp.CompletionParams): lsp.Position = params.position
    def uri(params: lsp.CompletionParams): URI               = URI(params.textDocument.uri)
  }

  given PositionWithURI[lsp.HoverParams] with { // TODO can't rename inside the type param
    def position(params: lsp.HoverParams): lsp.Position = params.position
    def uri(params: lsp.HoverParams): URI               = URI(params.textDocument.uri)
  }

  given PositionWithURI[lsp.SignatureHelpParams] with {
    def position(params: lsp.SignatureHelpParams): lsp.Position = params.position
    def uri(params: lsp.SignatureHelpParams): URI               = URI(params.textDocument.uri)
  }

  given PositionWithURI[lsp.DefinitionParams] with {
    def position(params: lsp.DefinitionParams): lsp.Position = params.position
    def uri(params: lsp.DefinitionParams): URI               = URI(params.textDocument.uri)
  }

  given RangeWithURI[lsp.InlayHintParams] with {
    def range(params: lsp.InlayHintParams): lsp.Range = params.range
    def uri(params: lsp.InlayHintParams): URI         = URI(params.textDocument.uri)
  }
}
