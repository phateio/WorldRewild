#!/bin/sh
# Build WorldRewild.jar with javac — no Maven/Gradle needed.
#
# Compiles against the running Paper server's bundled paper-api + libraries.
# Override SRV to point at a different server install.
set -e
ROOT="$(cd "$(dirname "${0}")" && pwd)"
SRV="${SRV:-/home/minecraft/server}"

API=$(find "${SRV}/libraries" -type f -path '*paper-api*' -name '*.jar' | sort | tail -1)
if [ -z "${API}" ]; then
    echo "paper-api jar not found under ${SRV}/libraries — set SRV to your server path." >&2
    exit 1
fi
CP="${API}:$(find "${SRV}/libraries" -type f -name '*.jar' | tr '\n' ':')"

cd "${ROOT}"
rm -rf build
mkdir -p build
echo "Compiling against: ${API}"
javac --release 21 -Xlint:all,-classfile -cp "${CP}" -d build $(find src -name '*.java')
cp plugin.yml config.yml build/
jar cf "${ROOT}/WorldRewild.jar" -C build .
echo "Built: ${ROOT}/WorldRewild.jar"
