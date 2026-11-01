#!/usr/bin/env bash
# Fetches PRoot (+ loader + libtalloc) for aarch64 from the Termux package repo and renames them
# to lib*.so so Android installs them in nativeLibraryDir (the only place binaries may be executed).
set -euo pipefail
R=https://packages.termux.dev/apt/termux-main
OUT="$(pwd)/app/src/main/jniLibs/arm64-v8a"; mkdir -p "$OUT"
W="$(mktemp -d)"; cd "$W"
curl -fsSL "$R/dists/stable/main/binary-aarch64/Packages" -o Packages
fname(){ awk -v p="$1" 'BEGIN{RS="";FS="\n"} $0 ~ ("(^|\n)Package: " p "\n"){for(i=1;i<=NF;i++) if($i ~ /^Filename: /){sub(/^Filename: /,"",$i);print $i;exit}}' Packages | head -1; }
for p in proot libtalloc; do
  f="$(fname $p)"; [ -n "$f" ] || { echo "package $p not found"; exit 1; }
  mkdir "$p"; (cd "$p"; curl -fsSLO "$R/$f"; ar x ./*.deb; tar -xf data.tar.*)
done
cp "$(find proot -type f -name proot -path '*bin*' | head -1)" "$OUT/libproot.so"
cp "$(find proot -type f -name loader -path '*libexec*' | head -1)" "$OUT/libproot-loader.so"
cp "$(find libtalloc -type f -name 'libtalloc.so*' | head -1)" "$OUT/libtalloc.so"
patchelf --replace-needed libtalloc.so.2 libtalloc.so "$OUT/libproot.so" || true
chmod 755 "$OUT"/*.so
echo "Fetched:"; ls -l "$OUT"
