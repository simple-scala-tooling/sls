package org.scala.abusers.sls.index

import java.net.URI

opaque type SymbolId = String

object SymbolId:
  def apply(value: String): SymbolId = value

extension (id: SymbolId) def value: String = id

case class Location(
    uri: URI,
    startLine: Int,
    startCol: Int,
    endLine: Int,
    endCol: Int,
)

enum SymbolKind:
  case Class, Trait, Object, Enum, Method, Val, Var, TypeAlias, TypeParam,
    Package, Constructor, Field, EnumCase, Given

enum Visibility:
  case Public, Protected, Private, PackagePrivate

enum ReferenceKind:
  case Call, TypeRef, Import, Override, Extends, Annotation

enum SymbolOrigin:
  case ProjectTasty(buildTarget: String, sourceFile: URI)
  case DependencyClassfile(jarPath: String)

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
