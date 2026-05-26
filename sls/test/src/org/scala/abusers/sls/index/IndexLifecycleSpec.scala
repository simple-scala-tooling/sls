package org.scala.abusers.sls.index

import cats.effect.IO
import weaver.*

import scala.concurrent.duration.*

object IndexLifecycleSpec extends SimpleIOSuite {

  test("initial phase is Cold") {
    for {
      lifecycle <- IndexLifecycle.empty
      p         <- lifecycle.phase
    } yield expect(p == IndexPhase.Cold)
  }

  test("transition updates the phase") {
    for {
      lifecycle <- IndexLifecycle.empty
      _         <- lifecycle.transition(IndexPhase.Bootstrapping)
      mid       <- lifecycle.phase
      _         <- lifecycle.transition(IndexPhase.Ready)
      end       <- lifecycle.phase
    } yield expect(mid == IndexPhase.Bootstrapping) and expect(end == IndexPhase.Ready)
  }

  test("awaitReady returns Right when already Ready") {
    for {
      lifecycle <- IndexLifecycle.empty
      _         <- lifecycle.transition(IndexPhase.Ready)
      result    <- lifecycle.awaitReady(50.millis)
    } yield expect(result == Right(()))
  }

  test("awaitReady times out on Cold and returns the current phase") {
    for {
      lifecycle <- IndexLifecycle.empty
      result    <- lifecycle.awaitReady(50.millis)
    } yield expect(result == Left(IndexPhase.Cold))
  }

  test("awaitReady unblocks when transition flips to Ready") {
    for {
      lifecycle <- IndexLifecycle.empty
      _         <- lifecycle.transition(IndexPhase.Bootstrapping)
      flip   = IO.sleep(50.millis) *> lifecycle.transition(IndexPhase.Ready)
      result <- (flip, lifecycle.awaitReady(2.seconds)).parMapN((_, r) => r)
    } yield expect(result == Right(()))
  }

  test("awaitReady short-circuits on Failed without waiting the full timeout") {
    for {
      lifecycle <- IndexLifecycle.empty
      _         <- lifecycle.transition(IndexPhase.Failed)
      start     <- IO.monotonic
      result    <- lifecycle.awaitReady(5.seconds)
      end       <- IO.monotonic
    } yield expect(result == Left(IndexPhase.Failed)) and expect((end - start) < 1.second)
  }

  test("awaitReady unblocks when transition flips to Failed") {
    for {
      lifecycle <- IndexLifecycle.empty
      _         <- lifecycle.transition(IndexPhase.Bootstrapping)
      flip   = IO.sleep(50.millis) *> lifecycle.transition(IndexPhase.Failed)
      result <- (flip, lifecycle.awaitReady(2.seconds)).parMapN((_, r) => r)
    } yield expect(result == Left(IndexPhase.Failed))
  }

  test("tryStartBootstrap wins exactly once") {
    for {
      lifecycle <- IndexLifecycle.empty
      first     <- lifecycle.tryStartBootstrap
      second    <- lifecycle.tryStartBootstrap
      p         <- lifecycle.phase
    } yield expect(first) and expect(!second) and expect(p == IndexPhase.Bootstrapping)
  }

  test("tryStartBootstrap returns false when past Cold") {
    for {
      lifecycle <- IndexLifecycle.empty
      _         <- lifecycle.transition(IndexPhase.Ready)
      won       <- lifecycle.tryStartBootstrap
      p         <- lifecycle.phase
    } yield expect(!won) and expect(p == IndexPhase.Ready)
  }
}
