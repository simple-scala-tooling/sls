package org.scala.abusers.sls.index

import org.scala.abusers.sls.SourceUri

opaque type SymbolId = String

object SymbolId {
  def apply(value: String): SymbolId = value
}

extension (id: SymbolId) def value: String = id

/** Converts a SemanticDB symbol (e.g. `scala/collection/immutable/List#`) to the dotted fullName format used by TASTy
  * (e.g. `scala.collection.immutable.List`).
  */
def semanticDbToFullName(sdbSymbol: String): String =
  sdbSymbol
    .replaceAll("\\(\\+?\\d*\\)", "")
    .stripSuffix("#")
    .stripSuffix(".")
    .replace("#", ".")
    .replace("/", ".")

/** Generate candidate SymbolIds for a dotted name, covering the class/object ambiguity. TASTy uses `$` suffix for
  * objects (e.g. `Test$`), but SemanticDB doesn't. For `com.example.Test.dupa`, we also try `com.example.Test$.dupa`.
  */
def symbolIdCandidates(dottedName: String): List[SymbolId] = {
  val base    = SymbolId(dottedName)
  val lastDot = dottedName.lastIndexOf('.')
  if lastDot <= 0 then List(base)
  else {
    val ownerEnd = dottedName.lastIndexOf('.', lastDot - 1)
    if ownerEnd <= 0 then List(base)
    else {
      val owner      = dottedName.substring(ownerEnd + 1, lastDot)
      val withDollar = dottedName.substring(0, ownerEnd + 1) + owner + "$" + dottedName.substring(lastDot)
      List(base, SymbolId(withDollar)).distinct
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
  case DependencyClassfile(jarPath: String)
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
