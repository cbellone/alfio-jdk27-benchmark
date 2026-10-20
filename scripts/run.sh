#!/usr/bin/env bash
# One measured run at a fixed arrival rate:
#   30s idle | ramp-up (RAMP_SECONDS) | warm-up (WARMUP_SECONDS) | measurement (MEASURE_SECONDS) | 60s idle
# Only the measurement window is used in the analysis.
# PERF=yes: counts instructions and cycles of every JVM thread during the measurement window (perf-stat.txt);
# perf must be allowed to attach to the JVM, e.g. CONTAINER_USER=$(id -u)
#
# Usage: run.sh <image> <run-name> <rate> [extra JVM options]
set -uo pipefail
source "$(dirname "$0")/lib.sh"
IMAGE=$1; NAME=$2; RATE=$3; EXTRA_OPTS=${4:-}
RAMP_SECONDS=${RAMP_SECONDS:-120}
WARMUP_SECONDS=${WARMUP_SECONDS:-180}
MEASURE_SECONDS=${MEASURE_SECONDS:-480}

start_server "$IMAGE" "$NAME" "$EXTRA_OPTS" || exit 1
echo "rate=$RATE ramp=$RAMP_SECONDS warmup=$WARMUP_SECONDS measure=$MEASURE_SECONDS" >> "$OUT/config.txt"
sleep 30
if [ "${PERF:-no}" = "yes" ]; then
  (
    until [ -f "$OUT/load-start-millis" ]; do sleep 1; done
    sleep $((RAMP_SECONDS + WARMUP_SECONDS))
    perf stat --per-thread -e instructions,cycles -p "$JAVA_PID" -o "$OUT/perf-stat.txt" -- sleep "$MEASURE_SECONDS"
  ) &
  PERF_PID=$!
fi
gatling load "$RATE" "$RAMP_SECONDS" $((WARMUP_SECONDS + MEASURE_SECONDS))
echo "gatling exit $?" > "$OUT/load-exit"
date +%s > "$OUT/load-end"
if [ -n "${PERF_PID:-}" ]; then
  wait "$PERF_PID"
fi
if server_alive; then
  live_data
else
  echo "the server died during the run, see $OUT/container-state" >&2
fi
sleep 60
stop_server
grep -E 'request count|95th' "$OUT/load-gatling.log" | head -2
echo "done: $OUT"
