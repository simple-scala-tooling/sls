package org.scala.abusers.sls.index

import cats.effect.IO
import java.io.{OutputStream, PrintStream}
import java.util.zip.ZipFile
import org.scala.abusers.sls.{AbsolutePath, SourceUri, toSourceUri}
import org.slf4j.LoggerFactory


class TastyIndexer(buildTarget: String) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def indexFiles(
      tastyFiles: List[AbsolutePath],
      classpath: List[AbsolutePath],
  ): IO[Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]] =
    IO.blocking(runInspector(tastyFiles.map(_.toNioPath.toString), Nil, classpath.map(_.toNioPath.toString)))

  def indexDirectory(
      classesDir: AbsolutePath,
      classpath: List[AbsolutePath],
  ): IO[Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]] =
    IO.blocking {
      val tastyFiles = java.nio.file.Files.walk(classesDir.toNioPath)
        .filter(p => p.toString.endsWith(".tasty"))
        .toArray.toList.map(_.asInstanceOf[java.nio.file.Path].toString)
      runInspector(tastyFiles, Nil, classpath.map(_.toNioPath.toString))
    }

  def indexJar(
      jarPath: AbsolutePath,
      classpath: List[AbsolutePath],
  ): IO[Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]] =
    IO.blocking(runInspector(Nil, List(jarPath.toNioPath.toString), classpath.map(_.toNioPath.toString)))

  def indexBetastyJar(
      jarPath: AbsolutePath,
      classpath: List[AbsolutePath],
      onlyEntries: Set[String] = Set.empty,
  ): IO[Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]] =
    IO.blocking {
      val tempDir = java.nio.file.Files.createTempDirectory("betasty-extract")
      try {
        val betastyFiles = extractBetastyFiles(jarPath, tempDir, onlyEntries)
        if betastyFiles.isEmpty then Map.empty
        else runBetastyInspector(betastyFiles.map(_.toString), Nil, classpath.map(_.toNioPath.toString))
      } finally {
        AbsolutePath(tempDir).deleteRecursively
      }
    }

  /** Run the TASTy inspector, catching any Throwable (including compiler
    * Errors like AssertionError, LinkageError, etc.) and suppressing stdout
    * on the current thread to prevent corrupting the JSON-RPC pipe.
    */
  private def runInspector(
      tastyFiles: List[String],
      jars: List[String],
      classpath: List[String],
  ): Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])] = {
    val collector = new SymbolCollector(buildTarget)
    TastyIndexer.suppressedThreads.set(true)
    try {
      TastyInspectorDriver.inspectTastyFiles(tastyFiles, jars, classpath)(collector)
    } catch {
      case t: Throwable =>
        val target = if jars.nonEmpty then jars.mkString(", ") else tastyFiles.take(3).mkString(", ")
        logger.error(s"TASTy inspector crash for $target", t)
    } finally {
      TastyIndexer.suppressedThreads.set(false)
    }
    collector.result
  }

  private def extractBetastyFiles(jarPath: AbsolutePath, destDir: java.nio.file.Path, onlyEntries: Set[String] = Set.empty): List[java.nio.file.Path] = {
    val files = scala.collection.mutable.ListBuffer.empty[java.nio.file.Path]
    val zf = new ZipFile(jarPath.toFile)
    try {
      val entries = zf.entries()
      while (entries.hasMoreElements) {
        val entry = entries.nextElement()
        val matchesFilter = onlyEntries.isEmpty || onlyEntries.contains(entry.getName)
        if entry.getName.endsWith(".betasty") && !entry.isDirectory && matchesFilter then
          val outPath = destDir.resolve(entry.getName)
          java.nio.file.Files.createDirectories(outPath.getParent)
          val is = zf.getInputStream(entry)
          try java.nio.file.Files.copy(is, outPath)
          finally is.close()
          files += outPath
      }
    } finally zf.close()
    files.toList
  }

  private def runBetastyInspector(
      betastyFiles: List[String],
      jars: List[String],
      classpath: List[String],
  ): Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])] = {
    logger.info(s"runBetastyInspector: ${betastyFiles.size} betasty files, ${jars.size} jars, ${classpath.size} classpath entries")
    betastyFiles.foreach(f => logger.debug(s"  betasty file: $f"))
    val collector = new SymbolCollector(buildTarget)
    TastyIndexer.suppressedThreads.set(true)
    try {
      val success = TastyInspectorDriver.inspectBetastyFiles(betastyFiles, jars, classpath)(collector)
      logger.info(s"TastyInspectorDriver.inspectBetastyFiles returned success=$success")
    } catch {
      case t: Throwable =>
        val target = if jars.nonEmpty then jars.mkString(", ") else betastyFiles.take(3).mkString(", ")
        logger.error(s"BeTASTy inspector crash for $target", t)
    } finally {
      TastyIndexer.suppressedThreads.set(false)
    }
    val result = collector.result
    logger.info(s"runBetastyInspector result: ${result.size} files, ${result.values.map(_._1.size).sum} symbols")
    result
  }
}

private class SymbolCollector(buildTarget: String) extends scala.tasty.inspector.Inspector {
  import scala.collection.mutable

  private val logger = LoggerFactory.getLogger(classOf[SymbolCollector])
  private val symbols = mutable.Map.empty[SourceUri, mutable.ListBuffer[IndexedSymbol]]
  private val references = mutable.Map.empty[SourceUri, mutable.ListBuffer[SymbolReference]]

  def result: Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])] = {
    val allUris = symbols.keySet ++ references.keySet
    allUris.map { uri =>
      uri -> (
        symbols.getOrElse(uri, mutable.ListBuffer.empty).toList,
        references.getOrElse(uri, mutable.ListBuffer.empty).toList,
      )
    }.toMap
  }

  def inspect(using quotes: scala.quoted.Quotes)(tastys: List[scala.tasty.inspector.Tasty[quotes.type]]): Unit = {
    import quotes.reflect.*

    logger.info(s"SymbolCollector.inspect called with ${tastys.size} tasty units for buildTarget=$buildTarget")

    lazy val indexTraverser: TreeTraverser = new TreeTraverser {
      override def traverseTree(tree: Tree)(owner: Symbol): Unit = {
        try {
          tree match {
            case cd: ClassDef if !shouldSkipSymbol(cd.symbol) =>
              processClassDef(cd, owner)
              traverseTreeChildren(tree)(cd.symbol)
            case dd: DefDef if !shouldSkipSymbol(dd.symbol) =>
              processDefDef(dd, owner)
              traverseTreeChildren(tree)(dd.symbol)
            case vd: ValDef if !shouldSkipSymbol(vd.symbol) =>
              processValDef(vd, owner)
              traverseTreeChildren(tree)(vd.symbol)
            case td: TypeDef if !shouldSkipSymbol(td.symbol) =>
              processTypeDef(td, owner)
              traverseTreeChildren(tree)(td.symbol)
            case definition: Definition =>
              traverseTreeChildren(tree)(owner)
            case tree if !tree.symbol.isNoSymbol =>
              addReferenceFromTree(tree, owner)
              traverseTreeChildren(tree)(owner)
            case _ =>
              traverseTreeChildren(tree)(owner)
          }
        } catch {
          case e: Exception =>
            logger.debug(s"traverseTree failed for ${tree.getClass.getSimpleName}: ${e.getMessage}")
            try traverseTreeChildren(tree)(owner) catch { case _: Exception => () }
        }
      }
    }

    tastys.foreach { tasty =>
      try {
        logger.debug(s"Processing tasty: ${tasty.path}")
        indexTraverser.traverseTree(tasty.ast)(tasty.ast.symbol)
      } catch {
        case e: Exception => logger.error(s"Failed to process tasty ${tasty.path}: ${e.getMessage}", e)
      }
    }
    logger.info(s"SymbolCollector finished: ${symbols.values.map(_.size).sum} symbols, ${references.values.map(_.size).sum} references across ${symbols.size} files")

    def processClassDef(cd: ClassDef, owner: Symbol): Unit = {
      val sym = cd.symbol
      val symId = mkSymbolId(sym)
      val ownerId = if owner.isNoSymbol then None else Some(mkSymbolId(owner))
      val sourceUri = sourceFileUri(sym)

      sourceUri.foreach { uri =>
        val kind = classKind(sym)
        val vis = extractVisibility(sym)
        val parents = extractParents(cd)
        val loc = symbolLocation(sym, uri)
        val sig = tryOpt(sym.fullName)

        addSymbol(uri, IndexedSymbol(
          id = symId, name = sym.name, kind = kind, visibility = vis,
          owner = ownerId, location = loc,
          origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
          parents = parents, typeSignature = sig,
        ))

        parents.foreach { parentId =>
          loc.foreach(l => addReference(uri, SymbolReference(parentId, l, ReferenceKind.Extends)))
        }
      }
    }

    def processDefDef(dd: DefDef, owner: Symbol): Unit = {
      val sym = dd.symbol
      val symId = mkSymbolId(sym)
      val ownerId = if owner.isNoSymbol then None else Some(mkSymbolId(owner))
      val sourceUri = sourceFileUri(sym)

      sourceUri.foreach { uri =>
        val kind = if sym.isClassConstructor then SymbolKind.Constructor else SymbolKind.Method
        val vis = extractVisibility(sym)
        val loc = symbolLocation(sym, uri)

        addSymbol(uri, IndexedSymbol(
          id = symId, name = sym.name, kind = kind, visibility = vis,
          owner = ownerId, location = loc,
          origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
          parents = Nil, typeSignature = tryOpt(sym.fullName),
        ))

        try {
          sym.allOverriddenSymbols.foreach { overridden =>
            loc.foreach(l => addReference(uri, SymbolReference(mkSymbolId(overridden), l, ReferenceKind.Override)))
          }
        } catch { case e: Exception => () }
      }
    }

    def processValDef(vd: ValDef, owner: Symbol): Unit = {
      val sym = vd.symbol
      val symId = mkSymbolId(sym)
      val ownerId = if owner.isNoSymbol then None else Some(mkSymbolId(owner))
      val sourceUri = sourceFileUri(sym)

      sourceUri.foreach { uri =>
        val kind =
          if sym.flags.is(Flags.Given) then SymbolKind.Given
          else if sym.flags.is(Flags.Mutable) then SymbolKind.Var
          else SymbolKind.Val
        val vis = extractVisibility(sym)
        val loc = symbolLocation(sym, uri)

        addSymbol(uri, IndexedSymbol(
          id = symId, name = sym.name, kind = kind, visibility = vis,
          owner = ownerId, location = loc,
          origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
          parents = Nil, typeSignature = tryOpt(sym.fullName),
        ))
      }
    }

    def processTypeDef(td: TypeDef, owner: Symbol): Unit = {
      val sym = td.symbol
      val symId = mkSymbolId(sym)
      val ownerId = if owner.isNoSymbol then None else Some(mkSymbolId(owner))
      val sourceUri = sourceFileUri(sym)

      sourceUri.foreach { uri =>
        val kind = if sym.flags.is(Flags.Param) then SymbolKind.TypeParam else SymbolKind.TypeAlias
        val vis = extractVisibility(sym)
        val loc = symbolLocation(sym, uri)

        addSymbol(uri, IndexedSymbol(
          id = symId, name = sym.name, kind = kind, visibility = vis,
          owner = ownerId, location = loc,
          origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
          parents = Nil, typeSignature = None,
        ))
      }
    }

    def addReferenceFromTree(tree: Tree, owner: Symbol): Unit = {
      try {
        val sym = tree.symbol
        if sym.isNoSymbol then return
        val refId = mkSymbolId(sym)
        val pos = tree.pos
        if pos.start != pos.end then {
          posSourceUri(pos).foreach { uri =>
            val loc = Location(uri, pos.startLine, pos.startColumn, pos.endLine, pos.endColumn)
            addReference(uri, SymbolReference(refId, loc, ReferenceKind.Call))
          }
        }
      } catch { case e: Exception => () }
    }

    def mkSymbolId(sym: Symbol): SymbolId =
      SymbolId(try sym.fullName catch { case e: Exception =>
        // logger.debug(s"mkSymbolId: fullName failed for ${sym.name}: ${e.getMessage}")
        sym.name
      })

    def sourceFileUri(sym: Symbol): Option[SourceUri] =
      try {
        val pos = sym.pos
        if pos.isDefined then {
          val path = pos.get.sourceFile.path
          if path != null then Some(java.nio.file.Path.of(path).toSourceUri)
          else {
            // logger.debug(s"sourceFileUri: null path for symbol ${sym.name}")
            None
          }
        } else {
          // logger.debug(s"sourceFileUri: no position for symbol ${sym.name}")
          None
        }
      } catch { case e: Exception =>
        // logger.debug(s"sourceFileUri: exception for symbol ${sym.name}: ${e.getMessage}")
        None
      }

    def posSourceUri(pos: Position): Option[SourceUri] =
      try {
        val path = pos.sourceFile.path
        if path != null then Some(java.nio.file.Path.of(path).toSourceUri)
        else {
          // logger.debug(s"posSourceUri: null path")
          None
        }
      } catch { case e: Exception =>
        // logger.debug(s"posSourceUri: exception: ${e.getMessage}")
        None
      }

    def symbolLocation(sym: Symbol, uri: SourceUri): Option[Location] =
      try {
        val pos = sym.pos
        if pos.isDefined then {
          val p = pos.get
          Some(Location(uri, p.startLine, p.startColumn, p.endLine, p.endColumn))
        } else {
          // logger.debug(s"symbolLocation: no position for ${sym.name}")
          None
        }
      } catch { case e: Exception =>
        // logger.debug(s"symbolLocation: exception for ${sym.name}: ${e.getMessage}")
        None
      }

    def classKind(sym: Symbol): SymbolKind = {
      val flags = sym.flags
      if flags.is(Flags.Trait) then SymbolKind.Trait
      else if flags.is(Flags.Module) then SymbolKind.Object
      else if flags.is(Flags.Enum) && flags.is(Flags.Case) then SymbolKind.EnumCase
      else if flags.is(Flags.Enum) then SymbolKind.Enum
      else SymbolKind.Class
    }

    def extractVisibility(sym: Symbol): Visibility = {
      val flags = sym.flags
      if flags.is(Flags.Private) || flags.is(Flags.PrivateLocal) then Visibility.Private
      else if flags.is(Flags.Protected) then Visibility.Protected
      else Visibility.Public
    }

    def extractParents(cd: ClassDef): List[SymbolId] =
      try {
        cd.parents.flatMap { parent =>
          try {
            val tpe = parent match {
              case t: Term     => t.tpe
              case t: TypeTree => t.tpe
            }
            val typeSym = tpe.typeSymbol
            if typeSym.fullName == "java.lang.Object" || typeSym.fullName == "scala.Any" then None
            else Some(mkSymbolId(typeSym))
          } catch { case e: Exception =>
            // logger.debug(s"extractParents: failed for parent of ${cd.symbol.name}: ${e.getMessage}")
            None
          }
        }
      } catch { case e: Exception =>
        // logger.debug(s"extractParents: failed for ${cd.symbol.name}: ${e.getMessage}")
        Nil
      }

    def shouldSkipSymbol(sym: Symbol): Boolean =
      try {
        val flags = sym.flags
        val name = sym.name
        flags.is(Flags.Synthetic) || flags.is(Flags.Artifact) ||
        name.contains("$anon") || name.contains("$evidence") || name.startsWith("$")
      } catch { case e: Exception =>
        // logger.debug(s"shouldSkipSymbol: exception for symbol: ${e.getMessage}")
        true
      }

    def tryOpt[A](a: => A): Option[A] =
      try Some(a) catch { case e: Exception =>
        // logger.debug(s"tryOpt failed: ${e.getMessage}")
        None
      }
  }

  private def addSymbol(uri: SourceUri, sym: IndexedSymbol): Unit =
    symbols.getOrElseUpdate(uri, scala.collection.mutable.ListBuffer.empty) += sym

  private def addReference(uri: SourceUri, ref: SymbolReference): Unit =
    references.getOrElseUpdate(uri, scala.collection.mutable.ListBuffer.empty) += ref
}

object TastyIndexer {
  def apply(buildTarget: String): TastyIndexer = new TastyIndexer(buildTarget)

  private val suppressedThreads: ThreadLocal[Boolean] = ThreadLocal.withInitial(() => false)

  /** Install a thread-aware PrintStream as System.out that suppresses output
    * from threads running the TASTy inspector, while letting all other threads
    * (especially the LSP JSON-RPC output) write normally.
    * Call once at server startup.
    */
  def installStdoutGuard(): Unit = {
    val real = System.out
    val guarded = new PrintStream(new OutputStream {
      override def write(b: Int): Unit =
        if !suppressedThreads.get() then real.write(b)
      override def write(b: Array[Byte], off: Int, len: Int): Unit =
        if !suppressedThreads.get() then real.write(b, off, len)
      override def flush(): Unit = real.flush()
    }, true)
    System.setOut(guarded)
  }
}
