package org.scala.abusers.sls.index

object CamelCaseUtils {

  def extractCamelCase(name: String): String = {
    if name.isEmpty then return ""
    val sb = new StringBuilder
    sb.append(name.charAt(0).toLower)
    var i = 1
    while (i < name.length) {
      val c = name.charAt(i)
      if c.isUpper then {
        // Check if this is the start of a new word:
        // Either the previous char was lowercase, or the next char is lowercase
        // (consecutive uppercase like "IO" in "IOException" — the last uppercase
        // before a lowercase starts a new word)
        val prevLower = name.charAt(i - 1).isLower
        val nextLower = i + 1 < name.length && name.charAt(i + 1).isLower
        if prevLower || nextLower then
          sb.append(c.toLower)
      }
      i += 1
    }
    sb.toString
  }

  def isCamelCaseQuery(query: String): Boolean = {
    var i = 0
    while (i < query.length) {
      if query.charAt(i).isUpper then return true
      i += 1
    }
    false
  }
}
