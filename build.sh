#!/usr/bin/env bash
# Builds every plugin folder (one with manifest.json + java/) into out/<id>.mtp.
# API classes from api-stubs/ are compile-only and are NOT packed into the dex.
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "ANDROID_HOME not set"; exit 1; }
PLAT=$(ls "$SDK/platforms" | sort -V | tail -1)
BT=$(ls "$SDK/build-tools" | sort -V | tail -1)
AJ="$SDK/platforms/$PLAT/android.jar"
D8="$SDK/build-tools/$BT/d8"
echo "android.jar: $PLAT, d8: build-tools $BT"

rm -rf build out
mkdir -p build/api out
javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options -cp "$AJ" -d build/api $(find api-stubs -name '*.java')
(cd build/api && jar cf ../api.jar .)

for manifest in */manifest.json; do
  dir=$(dirname "$manifest")
  [ -d "$dir/java" ] || continue
  id=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['id'])" "$manifest")
  echo "== $dir -> $id.mtp"
  w="build/$dir"
  mkdir -p "$w/cls" "$w/dex"
  javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options -Xlint:deprecation \
    -cp "$AJ:build/api.jar" -d "$w/cls" $(find "$dir/java" -name '*.java')
  (cd "$w/cls" && jar cf ../plugin.jar .)
  "$D8" --release --min-api 26 --lib "$AJ" --classpath build/api.jar --output "$w/dex" "$w/plugin.jar"
  cp "$manifest" "$w/dex/"
  [ -f "$dir/icon.png" ] && cp "$dir/icon.png" "$w/dex/"
  (cd "$w/dex" && zip -q "../../../out/$id.mtp" manifest.json classes.dex $( [ -f icon.png ] && echo icon.png ))
  unzip -l "out/$id.mtp"
done
