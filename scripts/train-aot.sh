#!/usr/bin/env bash
# Creates an AOT cache trained on the Gatling workload, and an image that uses it.
# The training JVM runs with the same runtime, classpath, modules and heap as the measured runs:
# the JVM ignores an AOT cache created with different settings.
#
# Usage: train-aot.sh <base-image> <output-image> <rate> [training-seconds] [JVM options]
#   e.g. train-aot.sh alfio-bench4:jdk27-aot alfio-bench4:jdk27-aot-workload 5 300 "-Xms256m -Xmx256m"
# The training traffic uses another journey sequence (JOURNEY_SEED=1) than the measured runs (0).
set -uo pipefail
source "$(dirname "$0")/lib.sh"
BASE_IMAGE=$1; OUTPUT_IMAGE=$2; RATE=$3; SECONDS_AT_RATE=${4:-300}; JVM_OPTS=${5:-}
NAME="train-$(echo "$OUTPUT_IMAGE" | tr -c 'a-zA-Z0-9\n' '-')"
OUT="$ROOT/results/$NAME"
CONTAINER="alfio-$NAME"
DB="bench_$(echo "$NAME" | tr -c 'a-zA-Z0-9\n' '_')"
rm -rf "$OUT"; mkdir -p "$OUT"; chmod 777 "$OUT"
docker rm -f "$CONTAINER" >/dev/null 2>&1
$PG dropdb -U postgres --if-exists "$DB" 2>/dev/null
$PG createdb -U postgres -T "$TEMPLATE_DB" "$DB" || exit 1

# the image's own start script (same runtime, modules and JVM_OPTS as the measured runs), with -XX:AOTCacheOutput
# instead of the startup-trained cache. "exec" in start.sh: java receives the SIGTERM of "docker stop", exits normally
# and writes the cache. The container gets more memory than the measured runs: at exit, a second JVM assembles the cache
docker update --cpuset-cpus="$DB_CPUS" alfio-main-postgres >/dev/null
docker run -d --name "$CONTAINER" --network host --cpuset-cpus="$SERVER_CPUS" --memory="${TRAIN_MEM_LIMIT:-1g}" \
  -e ALFIO_STARTUP_OVERRIDE="-XX:AOTCacheOutput=/out/alfio.aot" \
  -e ALFIO_JAVA_OPTS="$JVM_OPTS -Dspring.profiles.active=dev -Ddatasource.url=jdbc:postgresql://localhost:5432/$DB -Ddatasource.username=postgres -Ddatasource.password=password" \
  -v "$ALFIO_DEV_PROPERTIES:/home/alfio/app/application-dev.properties:ro" -v "$OUT:/out" \
  "$BASE_IMAGE" >/dev/null || exit 1
until curl -sf http://localhost:8080/healthz >/dev/null; do sleep 1; done

echo "training at $RATE journeys/s for ${SECONDS_AT_RATE}s"
JOURNEY_SEED=1 gatling training "$RATE" 60 "$SECONDS_AT_RATE"
echo "gatling exit $?"

# at exit the JVM records the training data, then a child JVM assembles the cache: give it time
docker stop -t 600 "$CONTAINER" >/dev/null
docker logs "$CONTAINER" > "$OUT/container.log" 2>&1
docker rm "$CONTAINER" >/dev/null
$PG dropdb -U postgres --if-exists "$DB"
if [ ! -s "$OUT/alfio.aot" ]; then
  echo "no AOT cache created, see $OUT/container.log" >&2
  exit 1
fi
grep -E 'AOTCache creation' "$OUT/container.log"
docker build -q --build-arg BASE_IMAGE="$BASE_IMAGE" -t "$OUTPUT_IMAGE" -f "$ROOT/docker/workload-aot/Dockerfile" "$OUT" >/dev/null || exit 1
echo "image $OUTPUT_IMAGE ready"
