package org.scala.abusers.sls.index.util

/** Named position marker for use inside a [[Code.code]] literal. */
final case class CodeMarker(name: String)

/** Source text with marker positions extracted. Lines and columns are 0-indexed (LSP convention) and refer to the
  * final [[text]] — after margin stripping and marker removal.
  */
final case class SourceWithPositions(text: String, positions: Map[CodeMarker, lsp.Position]) {
  def at(marker: CodeMarker): lsp.Position =
    positions.getOrElse(marker, sys.error(s"Marker '${marker.name}' does not appear in this code literal"))
}

object Code {

  /** Predefined markers, mirroring dotty's language-server test DSL (`dotty.tools.languageserver.util.Code`): tests
    * `import Code.*` and interpolate `${m1}`…`${m20}` directly instead of declaring markers per test.
    */
  val m1: CodeMarker  = CodeMarker("m1")
  val m2: CodeMarker  = CodeMarker("m2")
  val m3: CodeMarker  = CodeMarker("m3")
  val m4: CodeMarker  = CodeMarker("m4")
  val m5: CodeMarker  = CodeMarker("m5")
  val m6: CodeMarker  = CodeMarker("m6")
  val m7: CodeMarker  = CodeMarker("m7")
  val m8: CodeMarker  = CodeMarker("m8")
  val m9: CodeMarker  = CodeMarker("m9")
  val m10: CodeMarker = CodeMarker("m10")
  val m11: CodeMarker = CodeMarker("m11")
  val m12: CodeMarker = CodeMarker("m12")
  val m13: CodeMarker = CodeMarker("m13")
  val m14: CodeMarker = CodeMarker("m14")
  val m15: CodeMarker = CodeMarker("m15")
  val m16: CodeMarker = CodeMarker("m16")
  val m17: CodeMarker = CodeMarker("m17")
  val m18: CodeMarker = CodeMarker("m18")
  val m19: CodeMarker = CodeMarker("m19")
  val m20: CodeMarker = CodeMarker("m20")

  /** `code"""…"""` interpolator: applies `stripMargin` and removes `${marker}` tokens from the literal, recording
    * each marker's 0-indexed line/column in the resulting text.
    *
    * {{{
    * val m1 = CodeMarker("m1")
    * val src = code"""
    *   |object Foo {
    *   |  val x: ${m1}Int = 42
    *   |}
    * """
    * src.text   // marker-free source, margins stripped
    * src.at(m1) // lsp.Position(line = 2, character = 9)
    * }}}
    *
    * A marker interpolated more than once keeps its last occurrence.
    */
  extension (sc: StringContext) {
    def code(args: CodeMarker*): SourceWithPositions = {
      val sb = new StringBuilder(sc.parts.head)
      args.indices.foreach(i => sb.append(sentinel(i)).append(sc.parts(i + 1)))

      // Sentinels survive stripMargin (they never start a line), then get located and removed one by one.
      // Removal order matters: each sentinel's position is computed on text with all earlier sentinels gone.
      var text      = sb.toString.stripMargin
      val positions = Map.newBuilder[CodeMarker, lsp.Position]
      args.zipWithIndex.foreach { case (marker, i) =>
        val token = sentinel(i)
        val idx   = text.indexOf(token)
        require(idx >= 0, s"Sentinel for marker '${marker.name}' missing — interpolator bug")
        val lastNewline = text.lastIndexOf('\n', idx - 1)
        val line        = text.substring(0, idx).count(_ == '\n')
        positions += marker -> lsp.Position(line = line, character = idx - (lastNewline + 1))
        text = text.substring(0, idx) + text.substring(idx + token.length)
      }
      SourceWithPositions(text, positions.result())
    }
  }

  // NUL delimiters: cannot occur in a source literal, so indexOf can never match real text.
  private def sentinel(i: Int): String = "\u0000" + i + "\u0000"
}
