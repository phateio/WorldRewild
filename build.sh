#!/bin/sh
# Build WorldRewild.jar with javac (no Maven/Gradle needed).
#
# Compiles against the running Paper server's bundled paper-api + libraries.
# Adjust SRV / the paper-api glob for your server version if needed.
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRV="${SRV:-/home/minecraft/server}"

API=$(find "$SRV/libraries" -type f -path '*paper-api*' -name '*.jar' | sort | tail -1)
LIBS=$(find "$SRV/libraries" -type f -name '*.jar' | tr '\n' ':')
CP="$API:$LIBS"

cd "$ROOT"
rm -rf build
mkdir -p build
find src -name '*.java' > /tmp/wr_sources.txt
echo "Compiling against: $API"
javac -Xlint:deprecation -cp "$CP" -d build @/tmp/wr_sources.txt
cp plugin.yml config.yml build/
cd build
jar cf "$ROOT/WorldRewild.jar" .
echo "Built: $ROOT/WorldRewild.jar"
