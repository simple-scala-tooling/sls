package org.scala.abusers.sls.index

import cats.effect.IO
import weaver.*

object CamelCaseUtilsSpec extends SimpleIOSuite {

  test("AbstractListType extracts to alt") {
    IO(expect(CamelCaseUtils.extractCamelCase("AbstractListType") == "alt"))
  }

  test("ListBuffer extracts to lb") {
    IO(expect(CamelCaseUtils.extractCamelCase("ListBuffer") == "lb"))
  }

  test("map extracts to m") {
    IO(expect(CamelCaseUtils.extractCamelCase("map") == "m"))
  }

  test("IOException extracts to ie (IO is one word, Exception is another)") {
    IO(expect(CamelCaseUtils.extractCamelCase("IOException") == "ie"))
  }

  test("HTMLParser extracts to hp") {
    IO(expect(CamelCaseUtils.extractCamelCase("HTMLParser") == "hp"))
  }

  test("single char x extracts to x") {
    IO(expect(CamelCaseUtils.extractCamelCase("x") == "x"))
  }

  test("all caps XML extracts to x") {
    IO(expect(CamelCaseUtils.extractCamelCase("XML") == "x"))
  }

  test("empty string extracts to empty") {
    IO(expect(CamelCaseUtils.extractCamelCase("") == ""))
  }

  test("isCamelCaseQuery returns true for ALT") {
    IO(expect(CamelCaseUtils.isCamelCaseQuery("ALT")))
  }

  test("isCamelCaseQuery returns false for list") {
    IO(expect(!CamelCaseUtils.isCamelCaseQuery("list")))
  }
}
