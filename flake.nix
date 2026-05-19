{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    flake-parts = {
      url = "github:hercules-ci/flake-parts";
    };
    treefmt-nix = {
      url = "github:numtide/treefmt-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    cellar.url = "github:VirtusLab/cellar";
  };

  outputs = inputs@{ nixpkgs, flake-parts, cellar, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [ "x86_64-linux" "aarch64-darwin" "x86_64-darwin" ];
      imports = [
        inputs.treefmt-nix.flakeModule
      ];
      perSystem = { system, config, ... }:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ cellar.overlays.default ];
          };
        in
        {
          devShells.default = pkgs.mkShell {
            packages = [ pkgs.jdk21 pkgs.cellar ];
            inputsFrom = [
              config.treefmt.build.devShell
            ];
          };

          treefmt.config = {
            projectRootFile = "flake.nix";

            programs = {
              nixpkgs-fmt.enable = true;
              scalafmt.enable = true;
            };
          };
        };
    };
}

