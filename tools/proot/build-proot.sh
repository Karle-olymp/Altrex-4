#!/usr/bin/env bash
# [ALTREX] Rebuilds app/src/main/jniLibs/arm64-v8a/libproot.so and libproot-loader.so from source.
#
# PRoot is GPL-2.0 (https://github.com/termux/proot). It is shipped in the APK as a separate program,
# run by the Linux terminal; Altrex itself stays Apache-2.0. talloc (LGPL-3.0, samba.org) is linked
# statically into PRoot. See docs/terminal/PROOT.md.
#
# Needs: the Android NDK, git, curl, make and gawk.
#   NDK=/path/to/ndk tools/proot/build-proot.sh
set -euo pipefail

PROOT_COMMIT=b27d81a68d765f3462e807f4a14e01a03f8e89ea
TALLOC_VERSION=2.4.2
API=31

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
: "${NDK:?set NDK to the Android NDK folder}"
tc="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"

# talloc: the library alone, without its waf build (config.h written for Android / bionic).
curl -sSfL -o talloc.tar.gz "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
mkdir talloc out
tar -xzf talloc.tar.gz -C talloc --strip-components=1
cp "$here/talloc-config.h" out/config.h
"$tc/aarch64-linux-android$API-clang" -O2 -fPIC -c talloc/talloc.c -include dlfcn.h -include out/config.h \
  -Iout -Italloc -Italloc/lib/replace -D__STDC_WANT_LIB_EXT1__=1 -D_GNU_SOURCE -o out/talloc.o
"$tc/llvm-ar" rcs out/libtalloc.a out/talloc.o
cp talloc/talloc.h out/

# PRoot, with its loader as a separate file (unbundled): Android only executes files from the
# APK's native library folder.
git clone -q https://github.com/termux/proot
git -C proot checkout -q "$PROOT_COMMIT"
git -C proot apply "$here/proot-android.patch"
mkdir -p bin && ln -sf "$(command -v gawk)" bin/awk
(
  cd proot/src
  PATH="$work/bin:$PATH" CFLAGS="-I$work/out -O2" LDFLAGS="-L$work/out" make -j"$(nproc)" \
    CC="$tc/aarch64-linux-android$API-clang" LD="$tc/aarch64-linux-android$API-clang" \
    STRIP="$tc/llvm-strip" OBJCOPY="$tc/llvm-objcopy" OBJDUMP="$tc/llvm-objdump" \
    PROOT_UNBUNDLE_LOADER=/unused proot loader/loader
)

dest="$repo/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$dest"
"$tc/llvm-strip" -o "$dest/libproot.so" proot/src/proot
"$tc/llvm-strip" -o "$dest/libproot-loader.so" proot/src/loader/loader
echo "OK: $dest/libproot.so, $dest/libproot-loader.so"
