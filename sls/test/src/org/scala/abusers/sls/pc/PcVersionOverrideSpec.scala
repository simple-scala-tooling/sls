package org.scala.abusers.pc

import weaver.SimpleIOSuite

object PcVersionOverrideSpec extends SimpleIOSuite {

  pureTest("parseMap reads module=version entries and drops malformed ones") {
    val parsed = PcVersionOverride.parseMap("sls=3.8.4-SNAPSHOT, zincCli=3.8.3 ,broken,=novalue,nokey=")
    expect(parsed == Map("sls" -> "3.8.4-SNAPSHOT", "zincCli" -> "3.8.3"))
  }

  pureTest("per-module entry wins over global, global wins over default, default otherwise") {
    val ov = PcVersionOverride(perModule = Map("sls" -> "9.9.9"), global = Some("8.8.8"))
    expect(ov.resolve("sls", ScalaVersion("3.8.3")).value == "9.9.9") and
      expect(ov.resolve("zincCli", ScalaVersion("3.8.3")).value == "8.8.8") and
      expect(PcVersionOverride.empty.resolve("sls", ScalaVersion("3.8.3")).value == "3.8.3")
  }
}
