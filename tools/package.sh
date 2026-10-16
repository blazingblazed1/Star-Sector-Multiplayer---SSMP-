#!/bin/sh
# package.sh <version> : make SSMP-<version>.zip containing the ready-to-install StarMP mod folder
# Run tools/build.sh and tools/build-helper.sh first.
set -e
V=${1:?version}; cd "$(dirname "$0")/.."
grep -q "VERSION = \"$V\"" java/StarMPPlugin.java || { echo "VERSION constant in java/StarMPPlugin.java is not $V"; exit 1; }
P=dist/SSMP-$V; rm -rf "$P"; mkdir -p "$P/StarMP/jars" "$P/StarMP/bin"
cp StarMP.jar "$P/StarMP/jars/"
cp rust-smnet/target/release/smnet "$P/StarMP/bin/smnet-linux"
cp rust-smnet/target/x86_64-pc-windows-gnu/release/smnet.exe "$P/StarMP/bin/smnet.exe"
cp -r moddata/data "$P/StarMP/"
sed "s/@VERSION@/$V/g" moddata/mod_info.json > "$P/StarMP/mod_info.json"
cp README-mod.txt "$P/StarMP/README.txt"; cp THIRD_PARTY_LICENSES.txt "$P/StarMP/"
cp tools/installers/single-click-install-windows.bat tools/installers/single-click-install-linux.sh "$P/"
(cd dist && rm -f "SSMP-$V.zip" && zip -qr "SSMP-$V.zip" "SSMP-$V")
echo "dist/SSMP-$V.zip"
