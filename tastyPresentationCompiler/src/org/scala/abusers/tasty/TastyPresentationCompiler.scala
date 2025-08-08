package org.scala.abusers.tasty

import scala.tasty.inspector.*
import scala.quoted.Quotes
import scala.quoted._
import scala.quoted.runtime.impl.QuotesImpl
import dotty.tools.dotc.classpath.ZipAndJarClassPathFactory
import dotty.tools.dotc.classpath.PackageNameUtils
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols.ClassSymbol
import dotty.tools.dotc.core.Symbols
import scala.meta.pc.TastyInformation
import java.{util => ju}
import scala.jdk.OptionConverters.*
import org.eclipse.lsp4j
import ju.Optional

case class TastyInformationImpl(
  inTastyJarPath: ju.Optional[String],
  sourcePath: ju.Optional[String],
  symbol: String,
  tastyJarPath: ju.Optional[String],
  tastyPath: ju.Optional[String],
  range: ju.Optional[org.eclipse.lsp4j.Range]
) extends TastyInformation {
  override def toString(): String =
    s"PcTastyInformation(\ninTastyJarPath=$inTastyJarPath,\n sourcePath=$sourcePath,\n symbol=$symbol,\n tastyJarPath=$tastyJarPath,\n tastyPath=$tastyPath, range=$range\n)"
}

object TastyInformation {

  extension (symbol: Symbols.Symbol)(using Context) {
    def inTastyJarPath = util.Try(symbol.associatedFile.absolutePath).toOption.asJava
    def sourcePath = util.Try(symbol.source.path).toOption.asJava

    def tastyJarPath = util.Try(symbol.associatedFile.underlyingSource.get.file.nn.toURI.toString) match {
      case util.Success(z) if z.endsWith(".jar") =>
        Optional.of(z)
      case _ => Optional.empty[String]
    }

    def tastyPath = util.Try(symbol.associatedFile.underlyingSource.get.file.nn.toURI.toString) match {
      case util.Success(z) if z.endsWith(".tasty") =>
        Optional.of(z)
      case _ => Optional.empty[String]
    }

    def range = {
      val startPos = lsp4j.Position(symbol.startPos.line, symbol.startPos.column)
      val endPos = lsp4j.Position(symbol.endPos.line, symbol.endPos.column)
      Optional.of(lsp4j.Range(startPos, endPos))
    }
  }

  def apply(using quotes: Quotes)(symbol: quotes.reflect.Symbol): TastyInformation = {
    given Context = quotes.asInstanceOf[QuotesImpl].ctx
    val internalSymbol = symbol.asInstanceOf[Symbols.Symbol]

    TastyInformationImpl(
      inTastyJarPath = internalSymbol.inTastyJarPath,
      tastyJarPath = internalSymbol.tastyJarPath,
      sourcePath = internalSymbol.sourcePath,
      symbol = internalSymbol.showFullName,
      tastyPath = internalSymbol.tastyPath,
      range = internalSymbol.range
    )
  }

}


sealed trait Request {
  type Response
  def file: String
  def classpath: List[String]
  def startOffset: Int
  def endOffset: Int
}
case class DefinitionRequest(file: String, classpath: List[String], startOffset: Int, endOffset: Int) extends Request

class TastyPresentationCompiler(request: Request) extends Inspector {


  // TODO missing completions on new TreeAccumul@@
  override def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit = {
    import quotes.reflect.*

    extension (pos: Position) {
      def contains(that: Position): Boolean = pos.start <= that.start && pos.end >= that.end
    }

    lazy val symbolLookup: Position => TreeAccumulator[List[Tree]] = query => new TreeAccumulator[List[Tree]] {
      override def foldTree(x: List[Tree], tree: Tree)(owner: Symbol): List[Tree] = tree match {
        case tree if tree.pos.contains(query) =>
          foldOverTree(tree +: x, tree)(owner)
        case e =>
          foldOverTree(x, e)(owner)
      }
    }

    tastys.foreach { tasty =>
      val tree = tasty.ast
      request match {
        case DefinitionRequest(_, _, startOffset, endOffset) =>
          val queryPosition = Position.apply(tree.pos.sourceFile, startOffset, endOffset)
          val lookup = symbolLookup(queryPosition)
          val res = lookup.foldTree(Nil, tree)(tree.symbol)
          val head = res.headOption.getOrElse(sys.error("Empty path"))
          val tastyInformation = TastyInformation(head.symbol)
      }
    }
  }


}

class TastyIndex() extends Inspector {


  case class IndexSymbol(name: String, tpe: String)
  case class Reference(from: IndexSymbol, to: IndexSymbol, start: Int = 0, end: Int = 0)

  // TODO missing completions on new TreeAccumul@@
  override def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit = {
    import quotes.reflect.*

    val symbols: collection.mutable.Set[IndexSymbol] = collection.mutable.Set.empty
    val connections: collection.mutable.Set[Reference] = collection.mutable.Set.empty

    lazy val buildIndex: TreeTraverser = new TreeTraverser {
      override def traverseTree(tree: Tree)(owner: Symbol): Unit = tree match {
        case definition: Definition =>
          symbols += IndexSymbol(definition.symbol.fullName, definition.symbol.info.show)
          traverseTreeChildren(definition)(owner)
        case reference =>
          val ownerSymbol = IndexSymbol(owner.fullName, reference.symbol.info.show)
          symbols += ownerSymbol
          val thisSymbol = IndexSymbol(reference.symbol.fullName, reference.symbol.info.show)
          symbols += thisSymbol
          connections += Reference(ownerSymbol, thisSymbol, reference.pos.start, reference.pos.end)
          traverseTreeChildren(reference)(owner)
        // case other =>
        //   traverseTreeChildren(other)(owner)
        // case
        // case tree if tree.pos.contains(query) =>
        //   foldOverTree(tree +: x, tree)(owner)
        // case e =>
        //   foldOverTree(x, e)(owner)
      }
    }

    val start = System.currentTimeMillis()

    tastys.foreach { tasty =>
      val tree = tasty.ast
      val lookup = buildIndex.foldTree((), tree)(tree.symbol)
      // println(symbols)
    }
    println(s"Indexed ${symbols.size} symbols and ${connections.size} connections")

    // find all references of scala.cats.IO
    val r = connections.filter(_.to.name == "cats.effect.IO").toList.map { ref =>
      println(s"Found reference from ${ref.from.name} to ${ref.to.name} at positions ${ref.start} to ${ref.end}")
    }

    println(s"In total found ${r.size}")

    println(s"Indexing took ${System.currentTimeMillis() - start} ms")
  }


}

@main def loadTypedTrees(): Unit = {
  // Placeholder implementation - will discover API through compilation
  (1 to 10).foreach { i =>
    println(s"Test run $i")
    TastyInspector.inspectAllTastyFiles(
      Nil, //List("/home/rochala/Projects/sls/sls/out/sls/compile.dest/classes/org/scala/abusers/sls/SimpleScalaServer.tasty"),
      List("/home/rochala/.cache/coursier/v1/https/repo1.maven.org/maven2/org/typelevel/cats-effect_3/3.6.2/cats-effect_3-3.6.2.jar"),
      List(
        "/home/rochala/.cache/coursier/v1/https/repo1.maven.org/maven2/org/typelevel/cats-effect_3/3.6.2/cats-effect_3-3.6.2.jar",
        "/home/rochala/.cache/coursier/v1/https/repo1.maven.org/maven2/org/typelevel/cats-effect-kernel_3/3.6.2/cats-effect-kernel_3-3.6.2.jar",
        "/home/rochala/.cache/coursier/v1/https/repo1.maven.org/maven2/org/typelevel/cats-effect-std_3/3.6.2/cats-effect-std_3-3.6.2.jar",
        "/home/rochala/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.3.4/scala3-library_3-3.3.4.jar",
        "/home/rochala/.cache/coursier/v1/https/repo1.maven.org/maven2/org/typelevel/cats-mtl_3/1.3.1/cats-mtl_3-1.3.1.jar",
        "/home/rochala/.cache/coursier/v1/https/repo1.maven.org/maven2/org/typelevel/cats-core_3/2.11.0/cats-core_3-2.11.0.jar",
        "/home/rochala/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.14/scala-library-2.13.14.jar",
        "/home/rochala/.cache/coursier/v1/https/repo1.maven.org/maven2/org/typelevel/cats-kernel_3/2.11.0/cats-kernel_3-2.11.0.jar",
      )
    )(TastyIndex())
  }
}

object TastyPresentationCompiler {
  def processRequest[T <: Request](request: Request): Request#Response = {
    val deferredResult: Request#Response = ???
    TastyInspector.inspectAllTastyFiles(
      List(request.file),
      Nil,
      request.classpath
    )(new TastyPresentationCompiler(request))
    deferredResult
  }
}
