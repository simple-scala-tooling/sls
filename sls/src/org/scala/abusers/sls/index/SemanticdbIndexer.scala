package org.scala.abusers.sls.index

import java.net.URI
import dotty.tools.dotc.semanticdb.{TextDocument, SymbolInformation}

object SemanticdbIndexer:

  def indexDocument(
      uri: URI,
      bytes: Array[Byte],
      buildTarget: String,
  ): (List[IndexedSymbol], List[SymbolReference]) =
    val doc = TextDocument.parseFrom(bytes)
    val symbolInfos = doc.symbols.map(s => s.symbol -> s).toMap
    val symbols = List.newBuilder[IndexedSymbol]
    val refs = List.newBuilder[SymbolReference]

    doc.occurrences.foreach { occ =>
      if occ.symbol.nonEmpty && occ.range.isDefined then
        val r = occ.range.get
        val loc = Location(
          uri = uri,
          startLine = r.startLine,
          startCol = r.startCharacter,
          endLine = r.endLine,
          endCol = r.endCharacter,
        )
        val symId = SymbolId(semanticDbToFullName(occ.symbol))

        if occ.role.isDefinition then
          val info = symbolInfos.get(occ.symbol)
          val name = info.map(_.displayName).filter(_.nonEmpty)
            .getOrElse(extractSimpleName(occ.symbol))
          symbols += IndexedSymbol(
            id = symId,
            name = name,
            kind = info.map(infoToKind).getOrElse(SymbolKind.Val),
            visibility = Visibility.Public,
            owner = None,
            location = Some(loc),
            origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
            parents = Nil,
            typeSignature = None,
          )
        else if occ.role.isReference then
          refs += SymbolReference(symId, loc, ReferenceKind.Call)
    }

    (symbols.result(), refs.result())

  private def extractSimpleName(symbol: String): String =
    symbol.replaceAll("\\(\\+?\\d*\\)", "").stripSuffix("#").stripSuffix(".")
      .split("[/#.]").filter(_.nonEmpty).lastOption.getOrElse(symbol)

  private def infoToKind(info: SymbolInformation): SymbolKind =
    val k = info.kind
    if k.isClass then SymbolKind.Class
    else if k.isTrait || k.isInterface then SymbolKind.Trait
    else if k.isObject || k.isPackageObject then SymbolKind.Object
    else if k.isMethod || k.isMacro then SymbolKind.Method
    else if k.isConstructor then SymbolKind.Constructor
    else if k.isType then SymbolKind.TypeAlias
    else if k.isTypeParameter then SymbolKind.TypeParam
    else if k.isPackage then SymbolKind.Package
    else SymbolKind.Val
