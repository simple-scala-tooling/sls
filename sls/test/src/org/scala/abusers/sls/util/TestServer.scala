package org.scala.abusers.sls.util

import cats.effect.kernel.Deferred
import cats.effect.kernel.Resource
import cats.effect.std.AtomicCell
import cats.effect.std.Supervisor
import cats.effect.IO
import cats.effect.Ref
import org.scala.abusers.csp.CspServer
import org.scala.abusers.pc.IOCancelTokens
import org.scala.abusers.pc.PresentationCompilerProvider
import org.scala.abusers.sls.*
import org.scala.abusers.sls.index.util.TestWorkspace
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

/** Wires a real [[ServerImpl]] around a [[TestWorkspace]] with the heavy externals replaced:
  *
  *   - BSP → [[BspStubs.forTarget]] (answers inverse-sources for one synthetic Scala target; everything else raises)
  *   - CSP → [[FakeCspServer.dotcBacked]] (real in-process dotc producing real TASTy jars, no zinc)
  *   - queue → [[TestComputationQueue]] (passthrough; the `./.sls/` classpath swap is skipped)
  *   - index lifecycle is set straight to `Ready`: `bootstrap` would index the JDK and dependency jars, which is
  *     out of scope until the harness grows a knob for it
  *
  * The synthetic target reports Scala 3.0.0, which keeps `ServerImpl`'s PC-diagnostics path (gated on > 3.7.2)
  * disabled — no presentation compiler is downloaded or loaded.
  */
object TestServer {

  final case class Handle(
      client: TestClient,
      server: ServerImpl,
      workspace: TestWorkspace,
      symbolIndex: index.SymbolIndex,
      indexManager: index.IndexManager,
      lifecycle: index.IndexLifecycle,
  )

  def resource(ws: TestWorkspace)(using Tracer[IO], Meter[IO]): Resource[IO, Handle] =
    for {
      client            <- TestClient.create.toResource
      steward           <- ResourceSupervisor[IO]
      // The synthetic target's Scala version keeps every PC path off; a real provider would also drag in
      // scache, whose background machinery misbehaves under mill's test-runner classloader.
      pcProvider = new PresentationCompilerProvider {
        def get(info: ScalaBuildTargetInformation)(using
            org.scala.abusers.sls.SynchronizedState
        ): IO[scala.meta.pc.RawPresentationCompiler] =
          IO.raiseError(new NotImplementedError("presentation compiler is not available in TestServer"))
        def invalidateCompilers(): IO[Unit] = IO.unit
      }
      textDocumentSync  <- TextDocumentSyncManager.instance.toResource
      diagnosticManager <- DiagnosticManager.instance.toResource
      cancelTokens      <- IOCancelTokens.instance
      indexSupervisor   <- Supervisor[IO]
      symbolIndex       <- index.SymbolIndex.empty.toResource
      indexLifecycle    <- index.IndexLifecycle.empty.toResource
      cspWorkDir        <- Resource.make(IO.blocking(os.temp.dir(prefix = "sls-test-csp")))(d =>
        IO.blocking(os.remove.all(d))
      )
      cacheDir          <- Resource.make(IO.blocking(os.temp.dir(prefix = "sls-test-dep-cache")))(d =>
        IO.blocking(os.remove.all(d))
      )

      targetInfo = syntheticTarget(ws)
      bspStub    = BspStubs.forTarget(targetInfo.buildTarget.id)
      fakeCsp    = FakeCspServer.dotcBacked(cspWorkDir)

      bspDeferred      <- Deferred[IO, BuildServer].toResource
      cspDeferred      <- Deferred[IO, CspServer[IO]].toResource
      _                <- (bspDeferred.complete(bspStub) *> cspDeferred.complete(fakeCsp)).toResource
      sourcesToTargets <- AtomicCell[IO].of(Map.empty[SourceUri, ScalaBuildTargetInformation]).toResource
      targetsRef       <- Ref.of[IO, Set[ScalaBuildTargetInformation]](Set(targetInfo)).toResource
      bspStateManager = new BspStateManager(client, bspStub, fakeCsp, sourcesToTargets, targetsRef)

      indexManager = index.IndexManager(
        symbolIndex,
        index.BytecodeIndexer(),
        indexLifecycle,
        indexSupervisor,
        new index.DepIndexCache(cacheDir.toNIO),
        coursierapi.Cache.create(),
      )
      _ <- indexLifecycle.transition(index.IndexPhase.Ready).toResource

      server = ServerImpl(
        pcProvider,
        cancelTokens,
        diagnosticManager,
        steward,
        bspDeferred,
        cspDeferred,
        client,
        TestComputationQueue(),
        textDocumentSync,
        bspStateManager,
        indexManager,
        symbolIndex,
        indexLifecycle,
        indexSupervisor,
      )
      _ <- client.completeServer(server).toResource
    } yield Handle(client, server, ws, symbolIndex, indexManager, indexLifecycle)

  private def syntheticTarget(ws: TestWorkspace): ScalaBuildTargetInformation = {
    val targetId = bsp.BuildTargetIdentifier(bsp.URI("sls-test://target"))
    ScalaBuildTargetInformation(
      scalacOptions = bsp.scala_.ScalacOptionsItem(
        target = targetId,
        options = Nil,
        classpath = Nil,
        classDirectory = ws.classesRoot.toNIO.toUri.toString,
      ),
      buildTarget = bsp.BuildTarget.buildTargetScalaBuildTarget(
        id = targetId,
        tags = Nil,
        languageIds = List(bsp.LanguageId("scala")),
        dependencies = Nil,
        capabilities = bsp.BuildTargetCapabilities(None, None, None, None),
        data = bsp.scala_.ScalaBuildTarget(
          scalaOrganization = "org.scala-lang",
          // Anything <= 3.7.2 keeps ServerImpl's PC-diagnostics path off (no presentation compiler in tests).
          scalaVersion = "3.0.0",
          scalaBinaryVersion = "3",
          platform = bsp.scala_.ScalaPlatform.JVM,
          jars = Nil,
          jvmBuildTarget = None,
        ),
        displayName = Some("sls-test-target"),
        baseDirectory = None,
      ),
      sources = bsp.SourcesItem(
        target = targetId,
        sources = ws.sources.values.toList.map(p =>
          bsp.SourceItem(bsp.URI(p.toNIO.toUri.toString), bsp.SourceItemKind.FILE, generated = false)
        ),
        roots = None,
      ),
    )
  }
}
