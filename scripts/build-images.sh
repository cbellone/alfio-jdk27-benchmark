#!/usr/bin/env bash
# Builds the benchmark images from the extracted alf.io application, with docker/alfio/Dockerfile.
# All the images contain the same alfio-boot.jar.
#
# Usage: build-images.sh <alf.io image or directory> [image...]
#   the first argument is an alf.io image (its /home/alfio/app is extracted) or a directory with alfio-boot.jar + lib/
#   without image names, all the images listed below are built
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
SOURCE=$1; shift
CONTEXT="$ROOT/build/image-context"

ZULU25=azul/zulu-openjdk-alpine:25
TEMURIN27=eclipse-temurin:27-alpine
# name|JDK image|STARTUP|JVM_OPTS
IMAGES=(
  "jdk25|$ZULU25|appcds|-XX:+UseG1GC"
  "jdk25-coh|$ZULU25|appcds|-XX:+UseG1GC -XX:+UseCompactObjectHeaders"
  "jdk27-nocoh|$TEMURIN27|none|-XX:+UseG1GC -XX:-UseCompactObjectHeaders"
  "jdk27|$TEMURIN27|none|-XX:+UseG1GC"
  "jdk27-aot|$TEMURIN27|aot|-XX:+UseG1GC"
  "jdk27-parallel-aot|$TEMURIN27|aot|-XX:+UseParallelGC"
  "jdk27-parallel|$TEMURIN27|none|-XX:+UseParallelGC"
  "jdk27-serial|$TEMURIN27|none|-XX:+UseSerialGC"
)

rm -rf "$CONTEXT"; mkdir -p "$CONTEXT"
if [ -d "$SOURCE" ]; then
  cp -r "$SOURCE/alfio-boot.jar" "$SOURCE/lib" "$CONTEXT/"
else
  id=$(docker create "$SOURCE")
  docker cp "$id:/home/alfio/app/alfio-boot.jar" "$CONTEXT/"
  docker cp "$id:/home/alfio/app/lib" "$CONTEXT/"
  docker rm "$id" >/dev/null
fi
cp "$ROOT/docker/alfio/Dockerfile" "$ROOT/docker/alfio/start.sh" "$CONTEXT/"

for image in "${IMAGES[@]}"; do
  IFS='|' read -r name jdk startup jvm_opts <<< "$image"
  if [ $# -gt 0 ] && [[ " $* " != *" $name "* ]]; then
    continue
  fi
  echo "=== $(date +%T) building alfio-bench4:$name"
  docker build -q --build-arg JDK_IMAGE="$jdk" --build-arg STARTUP="$startup" --build-arg JVM_OPTS="$jvm_opts" \
    -t "alfio-bench4:$name" "$CONTEXT" >/dev/null
done
echo "BUILD-DONE $(date +%T)"
