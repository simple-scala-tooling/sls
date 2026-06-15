package org.scala.abusers.sls.index

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import org.scala.abusers.sls.toSourceUri
import org.scala.abusers.sls.AbsolutePath
import org.scala.abusers.sls.SourceUri
import org.slf4j.LoggerFactory
import org.typelevel.otel4s.trace.StatusCode
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute

import java.io.OutputStream
import java.io.PrintStream
import java.util.zip.ZipFile
import scala.util.control.NonFatal

private inline def safe[A](inline op: => A): Option[A] =
  try Some(op)
  catch { case NonFatal(_) => None }

class TastyIndexer(buildTarget: String)(using Tracer[IO]) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  type Indexed = Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]

  /** Record the diagnostics from a TASTy read onto the current span (the per-library `index.jar` span when called from
    * the index bootstrap): error/warning counts, the first few messages as span events, and an Error status when any
    * error was seen — so a jar that indexed only *partially* (e.g. classpath mismatch) stands out instead of looking
    * like a clean success. Also logged, so the signal survives even with no trace backend attached.
    */
  private def attachDiagnostics(diags: List[IndexDiagnostic]): IO[Unit] =
    IO.whenA(diags.nonEmpty) {
      val errors   = diags.count(_.isError)
      val warnings = diags.size - errors
      IO(logger.warn(s"TASTy read for $buildTarget produced $errors error(s), $warnings warning(s)")) *>
        Tracer[IO].currentSpanOrNoop.flatMap { span =>
          span.addAttribute(Attribute("index.errors", errors.toLong)) *>
            span.addAttribute(Attribute("index.warnings", warnings.toLong)) *>
            IO.whenA(errors > 0)(span.setStatus(StatusCode.Error, s"indexed with $errors error(s)")) *>
            diags.take(TastyIndexer.maxReportedDiagnostics).traverse_ { d =>
              span.addEvent(if d.isError then "index.error" else "index.warning", Attribute("message", d.message))
            }
        }
    }

  def indexFiles(
      tastyFiles: List[AbsolutePath],
      classpath: List[AbsolutePath],
  ): IO[Indexed] =
    IO.blocking(runInspector(tastyFiles.map(_.toNioPath.toString), Nil, classpath.map(_.toNioPath.toString)))
      .flatMap { case (result, diags) => attachDiagnostics(diags).as(result) }

  def indexDirectory(
      classesDir: AbsolutePath,
      classpath: List[AbsolutePath],
  ): IO[Indexed] =
    IO.blocking {
      val tastyFiles = java.nio.file.Files
        .walk(classesDir.toNioPath)
        .filter(p => p.toString.endsWith(".tasty"))
        .toArray
        .toList
        .map(_.asInstanceOf[java.nio.file.Path].toString)
      runInspector(tastyFiles, Nil, classpath.map(_.toNioPath.toString))
    }.flatMap { case (result, diags) => attachDiagnostics(diags).as(result) }

  def indexJar(
      jarPath: AbsolutePath,
      classpath: List[AbsolutePath],
  ): IO[Indexed] =
    IO.blocking(runInspector(Nil, List(jarPath.toNioPath.toString), classpath.map(_.toNioPath.toString)))
      .flatMap { case (result, diags) => attachDiagnostics(diags).as(result) }

  def indexBetastyJar(
      jarPath: AbsolutePath,
      classpath: List[AbsolutePath],
      onlyEntries: Set[String] = Set.empty,
  ): IO[Indexed] =
    IO.blocking {
      val tempDir = java.nio.file.Files.createTempDirectory("betasty-extract")
      try {
        val betastyFiles = extractBetastyFiles(jarPath, tempDir, onlyEntries)
        if betastyFiles.isEmpty then (Map.empty, Nil)
        else runBetastyInspector(betastyFiles.map(_.toString), Nil, classpath.map(_.toNioPath.toString))
      } finally
        AbsolutePath(tempDir).deleteRecursively
    }.flatMap { case (result, diags) => attachDiagnostics(diags).as(result) }

  /** Run the TASTy inspector. Re-throws any Throwable (including compiler Errors like AssertionError, LinkageError)
    * wrapped in a RuntimeException so cats-effect's IO.handleErrorWith can fire the bytecode fallback. Stdout is
    * suppressed on the current thread to prevent dotc reporter output from corrupting the JSON-RPC pipe.
    */
  private def runInspector(
      tastyFiles: List[String],
      jars: List[String],
      classpath: List[String],
  ): (Indexed, List[IndexDiagnostic]) = {
    val collector = new SymbolCollector(buildTarget)
    TastyIndexer.suppressedThreads.set(true)
    val diags =
      try TastyInspectorDriver.inspectTastyFiles(tastyFiles, jars, classpath)(collector)
      catch {
        case t: Throwable =>
          val target = if jars.nonEmpty then jars.mkString(", ") else tastyFiles.take(3).mkString(", ")
          throw new RuntimeException(s"TASTy inspector crash for $target", t)
      } finally
        TastyIndexer.suppressedThreads.set(false)
    (collector.result, diags)
  }

  private def extractBetastyFiles(
      jarPath: AbsolutePath,
      destDir: java.nio.file.Path,
      onlyEntries: Set[String] = Set.empty,
  ): List[java.nio.file.Path] = {
    val files = scala.collection.mutable.ListBuffer.empty[java.nio.file.Path]
    val zf    = new ZipFile(jarPath.toFile)
    try {
      val entries = zf.entries()
      while (entries.hasMoreElements) {
        val entry         = entries.nextElement()
        val matchesFilter = onlyEntries.isEmpty || onlyEntries.contains(entry.getName)
        if entry.getName.endsWith(".betasty") && !entry.isDirectory && matchesFilter then {
          val outPath = destDir.resolve(entry.getName)
          java.nio.file.Files.createDirectories(outPath.getParent)
          val is = zf.getInputStream(entry)
          try java.nio.file.Files.copy(is, outPath)
          finally is.close()
          files += outPath
        }
      }
    } finally zf.close()
    files.toList
  }

  private def runBetastyInspector(
      betastyFiles: List[String],
      jars: List[String],
      classpath: List[String],
  ): (Indexed, List[IndexDiagnostic]) = {
    logger.info(
      s"runBetastyInspector: ${betastyFiles.size} betasty files, ${jars.size} jars, ${classpath.size} classpath entries"
    )
    betastyFiles.foreach(f => logger.debug(s"  betasty file: $f"))
    val collector = new SymbolCollector(buildTarget)
    TastyIndexer.suppressedThreads.set(true)
    val diags =
      try TastyInspectorDriver.inspectBetastyFiles(betastyFiles, jars, classpath)(collector)
      catch {
        case t: Throwable =>
          val target = if jars.nonEmpty then jars.mkString(", ") else betastyFiles.take(3).mkString(", ")
          logger.error(s"BeTASTy inspector crash for $target", t)
          Nil
      } finally
        TastyIndexer.suppressedThreads.set(false)
    val result = collector.result
    logger.info(s"runBetastyInspector result: ${result.size} files, ${result.values.map(_._1.size).sum} symbols")
    (result, diags)
  }
}

private class SymbolCollector(buildTarget: String) extends scala.tasty.inspector.Inspector {
  import scala.collection.mutable

  private val logger     = LoggerFactory.getLogger(classOf[SymbolCollector])
  private val symbols    = mutable.Map.empty[SourceUri, mutable.ListBuffer[IndexedSymbol]]
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
      override def traverseTree(tree: Tree)(owner: Symbol): Unit =
        try
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
            case _: Definition =>
              traverseTreeChildren(tree)(owner)
            case tree if !tree.symbol.isNoSymbol =>
              addReferenceFromTree(tree, owner)
              traverseTreeChildren(tree)(owner)
            case _ =>
              traverseTreeChildren(tree)(owner)
          }
        catch {
          case NonFatal(e) =>
            logger.debug(s"traverseTree failed for ${tree.getClass.getSimpleName}: ${e.getMessage}")
            try traverseTreeChildren(tree)(owner)
            catch { case NonFatal(_) => () }
        }
    }

    tastys.foreach { tasty =>
      try {
        logger.debug(s"Processing tasty: ${tasty.path}")
        indexTraverser.traverseTree(tasty.ast)(tasty.ast.symbol)
      } catch {
        case NonFatal(e) => logger.error(s"Failed to process tasty ${tasty.path}: ${e.getMessage}", e)
      }
    }
    logger.info(
      s"SymbolCollector finished: ${symbols.values.map(_.size).sum} symbols, ${references.values.map(_.size).sum} references across ${symbols.size} files"
    )

    def processClassDef(cd: ClassDef, owner: Symbol): Unit = {
      val sym       = cd.symbol
      val symId     = mkSymbolId(sym)
      val ownerId   = if owner.isNoSymbol then None else Some(mkSymbolId(owner))
      val sourceUri = sourceFileUri(sym)

      sourceUri.foreach { uri =>
        val kind    = classKind(sym)
        val vis     = extractVisibility(sym)
        val parents = extractParents(cd)
        val loc     = symbolLocation(sym, uri)
        val sig     = safe(sym.fullName)

        addSymbol(
          uri,
          IndexedSymbol(
            id = symId,
            name = sym.name,
            kind = kind,
            visibility = vis,
            owner = ownerId,
            location = loc,
            origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
            parents = parents,
            typeSignature = sig,
          ),
        )

        parents.foreach { parentId =>
          loc.foreach(l => addReference(uri, SymbolReference(parentId, l, ReferenceKind.Extends)))
        }
      }
    }

    def processDefDef(dd: DefDef, owner: Symbol): Unit = {
      val sym       = dd.symbol
      val symId     = mkSymbolId(sym)
      val ownerId   = if owner.isNoSymbol then None else Some(mkSymbolId(owner))
      val sourceUri = sourceFileUri(sym)

      sourceUri.foreach { uri =>
        val kind = if sym.isClassConstructor then SymbolKind.Constructor else SymbolKind.Method
        val vis  = extractVisibility(sym)
        val loc  = symbolLocation(sym, uri)

        addSymbol(
          uri,
          IndexedSymbol(
            id = symId,
            name = sym.name,
            kind = kind,
            visibility = vis,
            owner = ownerId,
            location = loc,
            origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
            parents = Nil,
            typeSignature = safe(sym.fullName),
          ),
        )

        safe(sym.allOverriddenSymbols.foreach { overridden =>
          loc.foreach(l => addReference(uri, SymbolReference(mkSymbolId(overridden), l, ReferenceKind.Override)))
        })
      }
    }

    def processValDef(vd: ValDef, owner: Symbol): Unit = {
      val sym       = vd.symbol
      val symId     = mkSymbolId(sym)
      val ownerId   = if owner.isNoSymbol then None else Some(mkSymbolId(owner))
      val sourceUri = sourceFileUri(sym)

      sourceUri.foreach { uri =>
        val kind =
          if sym.flags.is(Flags.Given) then SymbolKind.Given
          else if sym.flags.is(Flags.Enum) && sym.flags.is(Flags.Case) then SymbolKind.EnumCase
          else if sym.flags.is(Flags.Mutable) then SymbolKind.Var
          else SymbolKind.Val
        val vis = extractVisibility(sym)
        val loc = symbolLocation(sym, uri)

        addSymbol(
          uri,
          IndexedSymbol(
            id = symId,
            name = sym.name,
            kind = kind,
            visibility = vis,
            owner = ownerId,
            location = loc,
            origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
            parents = Nil,
            typeSignature = safe(sym.fullName),
          ),
        )
      }
    }

    def processTypeDef(td: TypeDef, owner: Symbol): Unit = {
      val sym       = td.symbol
      val symId     = mkSymbolId(sym)
      val ownerId   = if owner.isNoSymbol then None else Some(mkSymbolId(owner))
      val sourceUri = sourceFileUri(sym)

      sourceUri.foreach { uri =>
        val kind = if sym.flags.is(Flags.Param) then SymbolKind.TypeParam else SymbolKind.TypeAlias
        val vis  = extractVisibility(sym)
        val loc  = symbolLocation(sym, uri)

        addSymbol(
          uri,
          IndexedSymbol(
            id = symId,
            name = sym.name,
            kind = kind,
            visibility = vis,
            owner = ownerId,
            location = loc,
            origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
            parents = Nil,
            typeSignature = None,
          ),
        )
      }
    }

    def addReferenceFromTree(tree: Tree, owner: Symbol): Unit =
      safe {
        val sym = tree.symbol
        if !sym.isNoSymbol then {
          val refId = mkSymbolId(sym)
          val pos   = tree.pos
          if pos.start != pos.end then {
            posSourceUri(pos).foreach { uri =>
              val loc = Location(uri, pos.startLine, pos.startColumn, pos.endLine, pos.endColumn)
              addReference(uri, SymbolReference(refId, loc, ReferenceKind.Call))
            }
          }
        }
      }

    def mkSymbolId(sym: Symbol): SymbolId =
      safe {
        val isType        = isTypeLike(sym)
        val name          = stripDollar(sym.name)
        val (pkg, owners) = collectOwners(sym.maybeOwner)
        SymbolId.fromTasty(pkg, owners, name, isType)
      }
        .orElse(safe(SymbolId.fromTasty(Nil, Nil, stripDollar(sym.name), isType = false)))
        .getOrElse(SymbolId.tpe(Nil, Nil, "<unknown>"))

    /** Type vs term decision. Packages are types; objects (Module flag) are terms even though their module-class is
      * structurally a class; everything else falls back to dotty's class/type/val/def classifiers.
      */
    def isTypeLike(sym: Symbol): Boolean = {
      val flags = sym.flags
      if flags.is(Flags.Package) then true
      else if flags.is(Flags.Module) then false
      else if sym.isClassDef || sym.isTypeDef then true
      else false
    }

    /** Walk the owner chain, collecting type-level owners until the first package, then package segments until root.
      * The returned lists are root-to-leaf.
      */
    def collectOwners(start: Symbol): (List[String], List[String]) = {
      val ownersBuf  = scala.collection.mutable.ListBuffer.empty[String]
      val packageBuf = scala.collection.mutable.ListBuffer.empty[String]
      var cur        = start
      while (!isStopSymbol(cur) && !cur.flags.is(Flags.Package)) {
        ownersBuf.prepend(stripDollar(cur.name))
        cur = cur.maybeOwner
      }
      while (!isStopSymbol(cur)) {
        packageBuf.prepend(stripDollar(cur.name))
        cur = cur.maybeOwner
      }
      (packageBuf.toList, ownersBuf.toList)
    }

    def isStopSymbol(sym: Symbol): Boolean =
      sym.isNoSymbol || {
        val n = sym.name
        n == "<root>" || n == "_root_" || n == "<empty>" || n.isEmpty
      }

    /** Module-class symbols carry a trailing `$` in some dotty paths; strip it so class `Lib` and object `Lib`'s
      * owner-name agree.
      */
    def stripDollar(n: String): String =
      if n.endsWith("$") then n.dropRight(1) else n

    def sourceFileUri(sym: Symbol): Option[SourceUri] =
      safe(sym.pos.flatMap(p => Option(p.sourceFile.path)).map(java.nio.file.Path.of(_).toSourceUri)).flatten

    def posSourceUri(pos: Position): Option[SourceUri] =
      safe(Option(pos.sourceFile.path).map(java.nio.file.Path.of(_).toSourceUri)).flatten

    def symbolLocation(sym: Symbol, uri: SourceUri): Option[Location] =
      safe(sym.pos.map(p => Location(uri, p.startLine, p.startColumn, p.endLine, p.endColumn))).flatten

    def classKind(sym: Symbol): SymbolKind = {
      val flags = sym.flags
      if flags.is(Flags.Trait) then SymbolKind.Trait
      else if flags.is(Flags.Enum) && flags.is(Flags.Case) then SymbolKind.EnumCase
      else if flags.is(Flags.Module) then SymbolKind.Object
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
      safe {
        cd.parents.flatMap { parent =>
          safe {
            val tpe = parent match {
              case t: Term     => t.tpe
              case t: TypeTree => t.tpe
            }
            val typeSym = tpe.typeSymbol
            if typeSym.fullName == "java.lang.Object" || typeSym.fullName == "scala.Any" then None
            else Some(mkSymbolId(typeSym))
          }.flatten
        }
      }.getOrElse(Nil)

    def shouldSkipSymbol(sym: Symbol): Boolean =
      safe {
        val flags = sym.flags
        val name  = sym.name
        flags.is(Flags.Synthetic) || flags.is(Flags.Artifact) ||
        name.contains("$anon") || name.contains("$evidence") || name.startsWith("$")
      }.getOrElse(true)
  }

  private def addSymbol(uri: SourceUri, sym: IndexedSymbol): Unit =
    symbols.getOrElseUpdate(uri, scala.collection.mutable.ListBuffer.empty) += sym

  private def addReference(uri: SourceUri, ref: SymbolReference): Unit =
    references.getOrElseUpdate(uri, scala.collection.mutable.ListBuffer.empty) += ref
}

object TastyIndexer {

  /** Cap on how many TASTy diagnostics we attach as span events per jar, to keep a pathologically broken jar from
    * flooding the trace. The error/warning counts are always exact; only the per-message events are capped.
    */
  private val maxReportedDiagnostics = 20

  def apply(buildTarget: String)(using Tracer[IO]): TastyIndexer = new TastyIndexer(buildTarget)

  private val suppressedThreads: ThreadLocal[Boolean] = ThreadLocal.withInitial(() => false)

  /** Install a thread-aware PrintStream as System.out that suppresses output from threads running the TASTy inspector,
    * while letting all other threads (especially the LSP JSON-RPC output) write normally. The original System.out is
    * restored on Resource finalization so shutdown leaves the JVM in the state it was found in.
    */
  def stdoutGuard: Resource[IO, Unit] =
    Resource
      .make(IO {
        val real    = System.out
        val guarded = new PrintStream(
          new OutputStream {
            override def write(b: Int): Unit =
              if !suppressedThreads.get() then real.write(b)
            override def write(b: Array[Byte], off: Int, len: Int): Unit =
              if !suppressedThreads.get() then real.write(b, off, len)
            override def flush(): Unit = real.flush()
          },
          true,
        )
        System.setOut(guarded)
        real
      })(real => IO(System.setOut(real)))
      .map(_ => ())
}
