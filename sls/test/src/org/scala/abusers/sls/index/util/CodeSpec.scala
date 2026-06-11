package org.scala.abusers.sls.index.util

import cats.effect.IO
import weaver.*

import Code.*

object CodeSpec extends SimpleIOSuite {

  test("text has margins stripped and markers removed") {
    val src = code"""
      |object Foo {
      |  val x: ${m1}Int = 42
      |}
    """
    IO(
      expect(src.text.contains("  val x: Int = 42")) &&
        expect(!src.text.contains('\u0000')) &&
        expect(!src.text.contains("|"))
    )
  }

  test("marker positions are 0-indexed line/column in the stripped text") {
    val src = code"""
      |object Foo {
      |  val x: ${m1}Int${m2} = 42
      |}
    """
    // line 0 is the empty line from the leading newline; "  val x: Int = 42" is line 2
    IO(
      expect.same(src.at(m1), lsp.Position(line = 2, character = 9)) &&
        expect.same(src.at(m2), lsp.Position(line = 2, character = 12))
    )
  }

  test("multiple markers on different lines") {
    val src = code"""
      |${m1}class A
      |${m2}class B extends A
    """
    IO(
      expect.same(src.at(m1), lsp.Position(line = 1, character = 0)) &&
        expect.same(src.at(m2), lsp.Position(line = 2, character = 0))
    )
  }

  test("marker text matches the surrounding source") {
    val src = code"""
      |package example
      |
      |class ${m1}Widget(name: String) {
      |  def ${m2}render: String = name
      |}
    """
    def wordAt(pos: lsp.Position): String = {
      val line = src.text.linesIterator.drop(pos.line).next()
      line.drop(pos.character).takeWhile(_.isLetterOrDigit)
    }
    IO(
      expect.same(wordAt(src.at(m1)), "Widget") &&
        expect.same(wordAt(src.at(m2)), "render")
    )
  }

  test("at() on an unused marker fails with the marker name") {
    val src = code"""
      |class A
    """
    IO(
      expect(scala.util.Try(src.at(m9)).failed.toOption.exists(_.getMessage.contains("m9")))
    )
  }
}
