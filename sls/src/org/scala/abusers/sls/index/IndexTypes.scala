package org.scala.abusers.sls.index

import org.scala.abusers.sls.SourceUri

/** Canonical, producer-agnostic symbol identifier.
  *
  * Replaces the prior opaque-string form whose value drifted by producer: TASTy emitted `Cls.foo`, bytecode emitted
  * `Cls#foo`. The case-class form has every producer go through one of the `from*` factories, so the *same*
  * source-level symbol turns into the *same* `SymbolId` regardless of who saw it.
  *
  * Shape:
  *   - `pkg` — package segments, root-to-leaf (e.g. `List("scala", "collection")`)
  *   - `owners` — outer class/object chain inside the package (e.g. `List("List")` for `List.foo`)
  *   - `name` — simple name of this symbol
  *   - `member` — `None` for types (class/trait/enum/typealias/typeparam/package), `Some` for terms
  *     (method/val/var/object/given/enum-case/constructor/field)
  *
  * Phase 1 deliberately *omits* member kind and overload disambig (`Member.disambig = None`). The plan documents the
  * trade-off: overloaded methods coalesce to one id (find-references returns all overloads), and a class's `val x` and
  * `def x` would also coalesce (the language already forbids that combo, so it bites nothing). Promote to an
  * erased-arg-list disambig if real users complain.
  */
case class SymbolId(
    pkg: List[String],
    owners: List[String],
    name: String,
    member: Option[Member],
) {
  def render: String = {
    val parts = pkg ++ owners
    val base  = if parts.isEmpty then name else (parts :+ name).mkString(".")
    // Terms render with parens; the inside carries the overload disambig once Phase 2 starts setting it.
    // Empty parens on vals/objects look a bit odd but keep type-vs-term distinguishable in debug output.
    member.fold(base)(m => s"$base(${m.disambig.getOrElse("")})")
  }
  override def toString: String = render
}

case class Member(disambig: Option[String])
object Member {

  /** The Phase 1 default: a term with no overload disambig. */
  val term: Member = Member(None)
}

object SymbolId {

  /** Type-level id (class, trait, enum-type, type alias, type param, package). */
  def tpe(pkg: List[String], owners: List[String], name: String): SymbolId =
    SymbolId(pkg, owners, name, None)

  /** Term-level id (method, val, var, object, given, enum case, constructor, field). */
  def term(pkg: List[String], owners: List[String], name: String): SymbolId =
    SymbolId(pkg, owners, name, Some(Member.term))

  /** TASTy-flavoured input: caller has already decomposed `Symbol.owner` into a package chain + outer-class chain,
    * extracted the simple name, and decided type-vs-term from the symbol's flags.
    */
  def fromTasty(pkg: List[String], owners: List[String], name: String, isType: Boolean): SymbolId =
    if isType then tpe(pkg, owners, name) else term(pkg, owners, name)

  /** Java symbols (via the Java frontend driver) flow through the same dotty `Symbol` API as TASTy. */
  def fromJava(pkg: List[String], owners: List[String], name: String, isType: Boolean): SymbolId =
    fromTasty(pkg, owners, name, isType)

  /** JVM bytecode input. `jvmInternalClass` is the slash-separated internal name (e.g. `crossproducer/Lib$Inner` or
    * `crossproducer/Lib$` for an object's module class). `memberName`:
    *   - `None` on a trailing-`$` class → TERM id (the object companion)
    *   - `None` otherwise → TYPE id (the class)
    *   - `Some(name)` → TERM id rooted at the class's canonical owner chain, regardless of `$` suffix
    */
  def fromJvm(jvmInternalClass: String, memberName: Option[String]): SymbolId = {
    val dotted              = jvmInternalClass.replace('/', '.')
    val lastDot             = dotted.lastIndexOf('.')
    val (pkgStr, classPath) =
      if lastDot >= 0 then (dotted.substring(0, lastDot), dotted.substring(lastDot + 1))
      else ("", dotted)
    val pkg = if pkgStr.isEmpty then Nil else pkgStr.split('.').toList

    val endsWithDollar       = classPath.endsWith("$")
    val core                 = if endsWithDollar then classPath.dropRight(1) else classPath
    val classSegments        = core.split('$').toList.filter(_.nonEmpty)
    val (clsOwners, clsName) = classSegments match {
      case Nil  => (Nil, "")
      case many => (many.init, many.last)
    }
    memberName match {
      case None    =>
        if endsWithDollar then term(pkg, clsOwners, clsName)
        else tpe(pkg, clsOwners, clsName)
      case Some(m) =>
        term(pkg, clsOwners :+ clsName, m)
    }
  }

  /** SemanticDB input. Parses strings like `scala/Predef.println(+1).` or `crossproducer/Lib#compute().`. Drops the
    * overload disambig `(+N)` since Phase 1 coalesces overloads (see class docstring).
    *
    * Separator rules:
    *   - `/` between package segments
    *   - `.` / `#` between class/owner segments (we don't preserve which is which — owners are just names)
    *   - trailing `#` ⇒ type, trailing `.` ⇒ term
    */
  def fromSemanticDb(sdbSymbol: String): SymbolId = {
    val cleaned             = sdbSymbol.replaceAll("\\(\\+?\\d*\\)", "")
    val lastSlash           = cleaned.lastIndexOf('/')
    val (pkgStr, classPart) =
      if lastSlash >= 0 then (cleaned.substring(0, lastSlash), cleaned.substring(lastSlash + 1))
      else ("", cleaned)
    val pkg = if pkgStr.isEmpty then Nil else pkgStr.split('/').toList

    val isType =
      if classPart.endsWith("#") then true
      else false // `.` or anything else falls through as term — bare strings are rare and best treated as terms

    val core   = classPart.stripSuffix("#").stripSuffix(".")
    val tokens = core.split("[.#]").toList.filter(_.nonEmpty)
    tokens match {
      case Nil       => SymbolId(pkg, Nil, "", None)
      case List(one) => if isType then tpe(pkg, Nil, one) else term(pkg, Nil, one)
      case many      =>
        val name   = many.last
        val owners = many.init
        if isType then tpe(pkg, owners, name) else term(pkg, owners, name)
    }
  }
}

case class Location(
    uri: SourceUri,
    startLine: Int,
    startCol: Int,
    endLine: Int,
    endCol: Int,
)

enum SymbolKind {
  case Class, Trait, Object, Enum, Method, Val, Var, TypeAlias, TypeParam,
    Package, Constructor, Field, EnumCase, Given
}

enum Visibility {
  case Public, Protected, Private, PackagePrivate
}

enum ReferenceKind {
  case Call, TypeRef, Import, Override, Extends, Annotation
}

enum SymbolOrigin {
  case ProjectTasty(buildTarget: String, sourceFile: SourceUri)
  case ProjectJavaSource(buildTarget: String, sourceFile: SourceUri)
  case DependencyClassfile(jarPath: String)
  case DependencySource(jarPath: String, sourceFile: SourceUri)
  case JdkSource(srcZipPath: String, sourceFile: SourceUri)
}

case class IndexedSymbol(
    id: SymbolId,
    name: String,
    kind: SymbolKind,
    visibility: Visibility,
    owner: Option[SymbolId],
    location: Option[Location],
    origin: SymbolOrigin,
    parents: List[SymbolId],
    typeSignature: Option[String],
)

case class SymbolReference(
    symbol: SymbolId,
    location: Location,
    referenceKind: ReferenceKind,
)
