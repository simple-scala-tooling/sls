{ scala-cli-nix }:
scala-cli-nix.buildCoursierApp {
  pname = "langousitneTracer";
  version = "0.1.0";
  lockFile = ./scala.lock.json;
}
