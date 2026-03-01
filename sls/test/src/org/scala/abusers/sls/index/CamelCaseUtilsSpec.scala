package org.scala.abusers.sls.index

import cats.effect.IO
import weaver.*

object CamelCaseUtilsSpec extends SimpleIOSuite {

  test("AbstractListType extracts to alt") {
    IO(expect(CamelCaseUtils.extractPascalCase("AbstractListType") == "alt"))
  }

  test("ListBuffer extracts to lb") {
    IO(expect(CamelCaseUtils.extractPascalCase("ListBuffer") == "lb"))
  }

  test("camelCase 'map' extracts to empty") {
    IO(expect(CamelCaseUtils.extractPascalCase("map") == ""))
  }

  test("IOException extracts to ie (IO is one word, Exception is another)") {
    IO(expect(CamelCaseUtils.extractPascalCase("IOException") == "ie"))
  }

  test("HTMLParser extracts to hp") {
    IO(expect(CamelCaseUtils.extractPascalCase("HTMLParser") == "hp"))
  }

  test("camelCase single char 'x' extracts to empty") {
    IO(expect(CamelCaseUtils.extractPascalCase("x") == ""))
  }

  test("all caps XML extracts to x") {
    IO(expect(CamelCaseUtils.extractPascalCase("XML") == "x"))
  }

  test("empty string extracts to empty") {
    IO(expect(CamelCaseUtils.extractPascalCase("") == ""))
  }

  test("camelCase 'indexOf' extracts to empty") {
    IO(expect(CamelCaseUtils.extractPascalCase("indexOf") == ""))
  }

  test("isCamelCaseQuery returns true for ALT") {
    IO(expect(CamelCaseUtils.isCamelCaseQuery("ALT")))
  }

  test("isCamelCaseQuery returns false for list") {
    IO(expect(!CamelCaseUtils.isCamelCaseQuery("list")))
  }
}
