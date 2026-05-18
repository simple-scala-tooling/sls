package org.scala.abusers.sls.index

object CamelCaseUtils {

  /** Extracts PascalCase word initials from a name. Only operates on PascalCase names (starting with uppercase).
    * Returns empty for camelCase names (starting with lowercase). Examples: "HashMap" → "hm", "IOException" → "ie",
    * "indexOf" → ""
    */
  def extractPascalCase(name: String): String = {
    if name.isEmpty || !name.charAt(0).isUpper then return ""
    val sb = new StringBuilder
    sb.append(name.charAt(0).toLower)
    var i = 1
    while (i < name.length) {
      val c = name.charAt(i)
      if c.isUpper then {
        val prevLower = name.charAt(i - 1).isLower
        val nextLower = i + 1 < name.length && name.charAt(i + 1).isLower
        if prevLower || nextLower then sb.append(c.toLower)
      }
      i += 1
    }
    sb.toString
  }

  /** A query is a CamelCase abbreviation when it's all-uppercase with length >= 2. Examples: "ALT" → true (matches
    * AbstractListType), "HM" → true (matches HashMap). "List", "HashMap", "list" → false (use regular name prefix
    * search).
    */
  def isCamelCaseQuery(query: String): Boolean =
    query.length >= 2 && query.forall(_.isUpper)
}
