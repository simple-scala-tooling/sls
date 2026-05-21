$version: "2.0"

// On-disk binary format for the dep-index cache. Strings are interned into a
// per-file table and every reference in [[ProtoIndexedSymbol]] is a varint
// index into that table. This collapses the heavy redundancy in symbol data
// (FQN prefixes, the jar/srcZip path repeated on every entry, the source URI
// shared across all symbols of a compilation unit) so the encoded payload is
// roughly an order of magnitude smaller than the JSON form and parses in a
// single pass with no string allocation for repeated values.
namespace org.scala.abusers.sls.depindex

use alloy.proto#protoIndex

structure CachedDepIndex {
    @required
    @protoIndex(1)
    strings: StringTable

    @required
    @protoIndex(2)
    symbols: ProtoIndexedSymbolList
}

list StringTable {
    member: String
}

list ProtoIndexedSymbolList {
    member: ProtoIndexedSymbol
}

structure ProtoIndexedSymbol {
    @required
    @protoIndex(1)
    idRef: Integer

    @required
    @protoIndex(2)
    nameRef: Integer

    @required
    @protoIndex(3)
    kind: ProtoSymbolKind

    @required
    @protoIndex(4)
    visibility: ProtoVisibility

    @protoIndex(5)
    ownerRef: Integer

    @protoIndex(6)
    location: ProtoLocation

    @required
    @protoIndex(7)
    origin: ProtoSymbolOrigin

    @required
    @protoIndex(8)
    parentRefs: IntList

    @protoIndex(9)
    typeSignatureRef: Integer
}

list IntList {
    member: Integer
}

structure ProtoLocation {
    @required
    @protoIndex(1)
    uriRef: Integer

    @required
    @protoIndex(2)
    startLine: Integer

    @required
    @protoIndex(3)
    startCol: Integer

    @required
    @protoIndex(4)
    endLine: Integer

    @required
    @protoIndex(5)
    endCol: Integer
}

enum ProtoSymbolKind {
    CLASS
    TRAIT
    OBJECT
    ENUM
    METHOD
    VAL
    VAR
    TYPE_ALIAS
    TYPE_PARAM
    PACKAGE
    CONSTRUCTOR
    FIELD
    ENUM_CASE
    GIVEN
}

enum ProtoVisibility {
    PUBLIC
    PROTECTED
    PRIVATE
    PACKAGE_PRIVATE
}

union ProtoSymbolOrigin {
    @protoIndex(1)
    projectTasty: ProjectTastyOrigin

    @protoIndex(2)
    projectJavaSource: ProjectJavaSourceOrigin

    @protoIndex(3)
    dependencyClassfile: DependencyClassfileOrigin

    @protoIndex(4)
    dependencySource: DependencySourceOrigin

    @protoIndex(5)
    jdkSource: JdkSourceOrigin
}

structure ProjectTastyOrigin {
    @required
    @protoIndex(1)
    buildTargetRef: Integer

    @required
    @protoIndex(2)
    sourceFileRef: Integer
}

structure ProjectJavaSourceOrigin {
    @required
    @protoIndex(1)
    buildTargetRef: Integer

    @required
    @protoIndex(2)
    sourceFileRef: Integer
}

structure DependencyClassfileOrigin {
    @required
    @protoIndex(1)
    jarPathRef: Integer
}

structure DependencySourceOrigin {
    @required
    @protoIndex(1)
    jarPathRef: Integer

    @required
    @protoIndex(2)
    sourceFileRef: Integer
}

structure JdkSourceOrigin {
    @required
    @protoIndex(1)
    srcZipPathRef: Integer

    @required
    @protoIndex(2)
    sourceFileRef: Integer
}
