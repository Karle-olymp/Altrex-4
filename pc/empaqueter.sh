#!/usr/bin/env bash
# Construit les distributions d'Altrex PC dans pc/dist :
#   - Linux x64 (archive .tar.gz) ;
#   - Windows x64 (.exe dans un .zip) par compilation croisée, si mingw-w64 et la cible Rust
#     x86_64-pc-windows-gnu sont installés (sinon cette partie est sautée).
# La version publiée pour Windows vient de la CI (.github/workflows/build-pc.yml, cible MSVC).
set -euo pipefail
cd "$(dirname "$0")"
version=$(sed -n 's/^version = "\(.*\)"/\1/p' Cargo.toml | head -1)
rm -rf dist && mkdir -p dist

cargo build --release --locked -p altrex-pc
tar -C target/release -czf "dist/altrex-pc-$version-linux-x64.tar.gz" altrex-pc

if command -v x86_64-w64-mingw32-gcc >/dev/null && rustup target list --installed | grep -q x86_64-pc-windows-gnu; then
  cargo build --release --locked -p altrex-pc --target x86_64-pc-windows-gnu
  (cd target/x86_64-pc-windows-gnu/release && zip -q -9 "../../../dist/altrex-pc-$version-windows-x64.zip" altrex-pc.exe)
else
  echo "Compilation croisée Windows sautée (il faut gcc-mingw-w64-x86-64 et : rustup target add x86_64-pc-windows-gnu)."
fi

(cd dist && sha256sum ./* > SHA256SUMS && ls -l)
