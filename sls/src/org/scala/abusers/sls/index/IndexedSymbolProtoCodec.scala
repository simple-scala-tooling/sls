package org.scala.abusers.sls.index

import org.scala.abusers.sls.depindex as proto
import org.scala.abusers.sls.SourceUri
import smithy4s.protobuf.ProtobufCodec
import smithy4s.protobuf.ProtobufReadError
import smithy4s.Blob

object IndexedSymbolProtoCodec {

  private val codec: ProtobufCodec[proto.CachedDepIndex] =
    ProtobufCodec.fromSchema(proto.CachedDepIndex.schema)

  def encode(symbols: List[IndexedSymbol]): Array[Byte] = {
    val interner     = new StringInterner
    val protoSymbols = symbols.map(toProto(_, interner))
    val payload      = proto.CachedDepIndex(strings = interner.strings, symbols = protoSymbols)
    codec.writeBlob(payload).toArray
  }

  def decode(bytes: Array[Byte]): Either[ProtobufReadError, List[IndexedSymbol]] =
    codec.readBlob(Blob(bytes)).map { payload =>
      val strings = payload.strings.toVector
      payload.symbols.map(fromProto(_, strings))
    }

  private final class StringInterner {
    private val table          = scala.collection.mutable.LinkedHashMap.empty[String, Int]
    def intern(s: String): Int = table.getOrElseUpdate(s, table.size)
    def strings: List[String]  = table.keysIterator.toList
  }

  private def toProto(s: IndexedSymbol, interner: StringInterner): proto.ProtoIndexedSymbol =
    proto.ProtoIndexedSymbol(
      idRef = interner.intern(s.id.value),
      nameRef = interner.intern(s.name),
      kind = kindToProto(s.kind),
      visibility = visibilityToProto(s.visibility),
      origin = originToProto(s.origin, interner),
      parentRefs = s.parents.map(p => interner.intern(p.value)),
      ownerRef = s.owner.map(o => interner.intern(o.value)),
      location = s.location.map(locationToProto(_, interner)),
      typeSignatureRef = s.typeSignature.map(interner.intern),
    )

  private def fromProto(p: proto.ProtoIndexedSymbol, strings: Vector[String]): IndexedSymbol =
    IndexedSymbol(
      id = SymbolId(strings(p.idRef)),
      name = strings(p.nameRef),
      kind = kindFromProto(p.kind),
      visibility = visibilityFromProto(p.visibility),
      owner = p.ownerRef.map(i => SymbolId(strings(i))),
      location = p.location.map(locationFromProto(_, strings)),
      origin = originFromProto(p.origin, strings),
      parents = p.parentRefs.map(i => SymbolId(strings(i))),
      typeSignature = p.typeSignatureRef.map(strings),
    )

  private def locationToProto(l: Location, interner: StringInterner): proto.ProtoLocation =
    proto.ProtoLocation(
      uriRef = interner.intern(l.uri.toLspUri),
      startLine = l.startLine,
      startCol = l.startCol,
      endLine = l.endLine,
      endCol = l.endCol,
    )

  private def locationFromProto(p: proto.ProtoLocation, strings: Vector[String]): Location =
    Location(
      uri = SourceUri(strings(p.uriRef)),
      startLine = p.startLine,
      startCol = p.startCol,
      endLine = p.endLine,
      endCol = p.endCol,
    )

  private def originToProto(origin: SymbolOrigin, interner: StringInterner): proto.ProtoSymbolOrigin =
    origin match {
      case SymbolOrigin.ProjectTasty(buildTarget, sourceFile) =>
        proto.ProtoSymbolOrigin.projectTasty(
          proto.ProjectTastyOrigin(
            buildTargetRef = interner.intern(buildTarget),
            sourceFileRef = interner.intern(sourceFile.toLspUri),
          )
        )
      case SymbolOrigin.ProjectJavaSource(buildTarget, sourceFile) =>
        proto.ProtoSymbolOrigin.projectJavaSource(
          proto.ProjectJavaSourceOrigin(
            buildTargetRef = interner.intern(buildTarget),
            sourceFileRef = interner.intern(sourceFile.toLspUri),
          )
        )
      case SymbolOrigin.DependencyClassfile(jarPath) =>
        proto.ProtoSymbolOrigin.dependencyClassfile(
          proto.DependencyClassfileOrigin(jarPathRef = interner.intern(jarPath))
        )
      case SymbolOrigin.DependencySource(jarPath, sourceFile) =>
        proto.ProtoSymbolOrigin.dependencySource(
          proto.DependencySourceOrigin(
            jarPathRef = interner.intern(jarPath),
            sourceFileRef = interner.intern(sourceFile.toLspUri),
          )
        )
      case SymbolOrigin.JdkSource(srcZipPath, sourceFile) =>
        proto.ProtoSymbolOrigin.jdkSource(
          proto.JdkSourceOrigin(
            srcZipPathRef = interner.intern(srcZipPath),
            sourceFileRef = interner.intern(sourceFile.toLspUri),
          )
        )
    }

  private def originFromProto(origin: proto.ProtoSymbolOrigin, strings: Vector[String]): SymbolOrigin =
    origin match {
      case proto.ProtoSymbolOrigin.ProjectTastyCase(o) =>
        SymbolOrigin.ProjectTasty(strings(o.buildTargetRef), SourceUri(strings(o.sourceFileRef)))
      case proto.ProtoSymbolOrigin.ProjectJavaSourceCase(o) =>
        SymbolOrigin.ProjectJavaSource(strings(o.buildTargetRef), SourceUri(strings(o.sourceFileRef)))
      case proto.ProtoSymbolOrigin.DependencyClassfileCase(o) =>
        SymbolOrigin.DependencyClassfile(strings(o.jarPathRef))
      case proto.ProtoSymbolOrigin.DependencySourceCase(o) =>
        SymbolOrigin.DependencySource(strings(o.jarPathRef), SourceUri(strings(o.sourceFileRef)))
      case proto.ProtoSymbolOrigin.JdkSourceCase(o) =>
        SymbolOrigin.JdkSource(strings(o.srcZipPathRef), SourceUri(strings(o.sourceFileRef)))
    }

  private def kindToProto(k: SymbolKind): proto.ProtoSymbolKind = k match {
    case SymbolKind.Class       => proto.ProtoSymbolKind.CLASS
    case SymbolKind.Trait       => proto.ProtoSymbolKind.TRAIT
    case SymbolKind.Object      => proto.ProtoSymbolKind.OBJECT
    case SymbolKind.Enum        => proto.ProtoSymbolKind.ENUM
    case SymbolKind.Method      => proto.ProtoSymbolKind.METHOD
    case SymbolKind.Val         => proto.ProtoSymbolKind.VAL
    case SymbolKind.Var         => proto.ProtoSymbolKind.VAR
    case SymbolKind.TypeAlias   => proto.ProtoSymbolKind.TYPE_ALIAS
    case SymbolKind.TypeParam   => proto.ProtoSymbolKind.TYPE_PARAM
    case SymbolKind.Package     => proto.ProtoSymbolKind.PACKAGE
    case SymbolKind.Constructor => proto.ProtoSymbolKind.CONSTRUCTOR
    case SymbolKind.Field       => proto.ProtoSymbolKind.FIELD
    case SymbolKind.EnumCase    => proto.ProtoSymbolKind.ENUM_CASE
    case SymbolKind.Given       => proto.ProtoSymbolKind.GIVEN
  }

  private def kindFromProto(k: proto.ProtoSymbolKind): SymbolKind = k match {
    case proto.ProtoSymbolKind.CLASS       => SymbolKind.Class
    case proto.ProtoSymbolKind.TRAIT       => SymbolKind.Trait
    case proto.ProtoSymbolKind.OBJECT      => SymbolKind.Object
    case proto.ProtoSymbolKind.ENUM        => SymbolKind.Enum
    case proto.ProtoSymbolKind.METHOD      => SymbolKind.Method
    case proto.ProtoSymbolKind.VAL         => SymbolKind.Val
    case proto.ProtoSymbolKind.VAR         => SymbolKind.Var
    case proto.ProtoSymbolKind.TYPE_ALIAS  => SymbolKind.TypeAlias
    case proto.ProtoSymbolKind.TYPE_PARAM  => SymbolKind.TypeParam
    case proto.ProtoSymbolKind.PACKAGE     => SymbolKind.Package
    case proto.ProtoSymbolKind.CONSTRUCTOR => SymbolKind.Constructor
    case proto.ProtoSymbolKind.FIELD       => SymbolKind.Field
    case proto.ProtoSymbolKind.ENUM_CASE   => SymbolKind.EnumCase
    case proto.ProtoSymbolKind.GIVEN       => SymbolKind.Given
  }

  private def visibilityToProto(v: Visibility): proto.ProtoVisibility = v match {
    case Visibility.Public         => proto.ProtoVisibility.PUBLIC
    case Visibility.Protected      => proto.ProtoVisibility.PROTECTED
    case Visibility.Private        => proto.ProtoVisibility.PRIVATE
    case Visibility.PackagePrivate => proto.ProtoVisibility.PACKAGE_PRIVATE
  }

  private def visibilityFromProto(v: proto.ProtoVisibility): Visibility = v match {
    case proto.ProtoVisibility.PUBLIC          => Visibility.Public
    case proto.ProtoVisibility.PROTECTED       => Visibility.Protected
    case proto.ProtoVisibility.PRIVATE         => Visibility.Private
    case proto.ProtoVisibility.PACKAGE_PRIVATE => Visibility.PackagePrivate
  }
}
