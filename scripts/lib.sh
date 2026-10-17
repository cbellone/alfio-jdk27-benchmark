# Shared functions for run.sh and capacity.sh. Source it, don't execute it.
# Requires: ALFIO_DEV_PROPERTIES (path to alf.io's application-dev.properties)

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
TEMPLATE_DB=${TEMPLATE_DB:-alfio_load_template}
PG="docker exec alfio-main-postgres"
# Gatling runs inside Gradle's JVM, which must be Java 25+ (the simulation is compiled for Java 25).
# Set BENCH_JAVA_HOME when the default JAVA_HOME points to an older JDK.
if [ -n "${BENCH_JAVA_HOME:-}" ]; then
  export JAVA_HOME=$BENCH_JAVA_HOME
fi
java_major=$("${JAVA_HOME:-/usr}/bin/java" -version 2>&1 | head -1 | sed -nE 's/.*version "([0-9]+).*/\1/p')
if ! [[ "$java_major" =~ ^[0-9]+$ ]] || [ "$java_major" -lt 25 ]; then
  echo "Java 25+ required to run Gatling, found ${java_major:-none} in JAVA_HOME=${JAVA_HOME:-}. Set BENCH_JAVA_HOME." >&2
  exit 1
fi

# the server gets 2 dedicated cores, the load generator runs on the others
SERVER_CPUS=${SERVER_CPUS:-2,3}
LOADGEN_CPUS=${LOADGEN_CPUS:-4-13}

# start_server <image> <run-name> [extra JVM options]
# sets OUT, CONTAINER, DB, JAVA_PID, PIDSTAT, SAMPLER
start_server() {
  local image=$1 name=$2 extra_opts=${3:-}
  OUT="$ROOT/results/$name"
  CONTAINER="alfio-$name"
  DB="bench_$(echo "$name" | tr -c 'a-zA-Z0-9\n' '_')"
  rm -rf "$OUT"; mkdir -p "$OUT"; chmod 777 "$OUT"
  docker rm -f "$CONTAINER" >/dev/null 2>&1
  $PG dropdb -U postgres --if-exists "$DB" 2>/dev/null
  $PG createdb -U postgres -T "$TEMPLATE_DB" "$DB" || return 1

  # JFR records every compilation (threshold 0ms); maxsize avoids losing the beginning of long recordings
  local java_opts="-Dspring.profiles.active=dev \
 -Ddatasource.url=jdbc:postgresql://localhost:5432/$DB -Ddatasource.username=postgres -Ddatasource.password=password \
 -XX:StartFlightRecording=filename=/out/recording.jfr,settings=profile,maxsize=4g,dumponexit=true,jdk.Compilation#threshold=0ms \
 -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics \
 -Xlog:gc*:file=/out/gc.log:uptime $extra_opts"
  echo "image=$image extra_opts=$extra_opts" > "$OUT/config.txt"

  date +%s%3N > "$OUT/container-start-millis"
  docker run -d --name "$CONTAINER" --network host --cpuset-cpus="$SERVER_CPUS" --memory=2g \
    -e ALFIO_JAVA_OPTS="$java_opts" \
    -v "$ALFIO_DEV_PROPERTIES:/home/alfio/app/application-dev.properties:ro" \
    -v "$OUT:/out" "$image" >/dev/null || return 1

  # poll often: the time to the first successful health check is a startup metric
  until curl -sf http://localhost:8080/healthz >/dev/null; do sleep 0.1; done
  date +%s%3N > "$OUT/ready-millis"
  JAVA_PID=$(docker top "$CONTAINER" -o pid,comm | awk '$2=="java" {print $1}')

  # per-thread CPU usage, every 5 seconds
  S_TIME_FORMAT=ISO pidstat -t -u -h -p "$JAVA_PID" 5 > "$OUT/pidstat.txt" &
  PIDSTAT=$!

  # resident memory of the java process + cgroup memory, every 2 seconds
  (
    echo "epoch,cgroup_bytes,java_rss_kb" > "$OUT/memory.csv"
    # kill -0 is not allowed on a process of another user (the container runs as uid 1001)
    while [ -d "/proc/$JAVA_PID" ]; do
      echo "$(date +%s),$(docker exec "$CONTAINER" cat /sys/fs/cgroup/memory.current 2>/dev/null),$(grep VmRSS /proc/"$JAVA_PID"/status 2>/dev/null | awk '{print $2}')" >> "$OUT/memory.csv"
      sleep 2
    done
  ) &
  SAMPLER=$!
}

# gatling <output-prefix> <rate> <ramp-seconds> <steady-seconds> [assert-p95-ms]
# returns Gatling's exit code (non-zero when an assertion fails)
gatling() {
  local prefix=$1 rate=$2 ramp=$3 steady=$4 assert_p95=${5:-}
  (cd "$ROOT" && BASE_URL=http://localhost:8080 API_KEY=$(cat "$ROOT/results/api-key.txt") RATE=$rate \
    RAMP_SECONDS=$ramp STEADY_SECONDS=$steady ASSERT_P95_MS=$assert_p95 \
    START_MARKER="$OUT/$prefix-start-millis" LATENCY_LOG="$OUT/$prefix-latencies.csv" \
    taskset -c "$LOADGEN_CPUS" ./gradlew --no-daemon --console=plain gatlingRun --simulation alfio.bench.PurchaseJourneySimulation \
    > "$OUT/$prefix-gatling.log" 2>&1)
  local status=$?
  cp -r "$(ls -td "$ROOT"/build/reports/gatling/purchasejourneysimulation-* | head -1)" "$OUT/$prefix-gatling-report"
  return $status
}

stop_server() {
  docker stop -t 120 "$CONTAINER" >/dev/null
  kill "$PIDSTAT" 2>/dev/null
  wait "$SAMPLER" 2>/dev/null
  docker logs "$CONTAINER" > "$OUT/container.log" 2>&1
  docker rm "$CONTAINER" >/dev/null
  $PG dropdb -U postgres --if-exists "$DB"
}
