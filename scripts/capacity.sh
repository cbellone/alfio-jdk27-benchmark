#!/usr/bin/env bash
# Finds the maximum sustainable arrival rate (journeys/s) of one configuration.
# After a warm-up, it runs a sequence of probes at increasing rates on the same JVM.
# A probe passes when less than 1% of the requests fail and the p95 latency stays below P95_LIMIT_MS.
# The search stops at the first failing probe, then tries the midpoint between the last pass and the failure.
#
# Usage: capacity.sh <image> <run-name> [extra JVM options]
set -uo pipefail
source "$(dirname "$0")/lib.sh"
IMAGE=$1; NAME=$2; EXTRA_OPTS=${3:-}
P95_LIMIT_MS=${P95_LIMIT_MS:-500}
START_RATE=${START_RATE:-3}
STEP=${STEP:-1}

start_server "$IMAGE" "$NAME" "$EXTRA_OPTS" || exit 1
echo "warm-up"
if ! gatling warmup 2 30 150; then
  echo "warm-up failed, see $OUT/warmup-gatling.log" >&2
  stop_server
  exit 1
fi

probe() {
  local rate=$1
  gatling "probe-$rate" "$rate" 30 120 "$P95_LIMIT_MS"
  local status=$?
  local p95
  p95=$(grep '95th percentile' "$OUT/probe-$rate-gatling.log" | head -1 | awk -F'|' '{gsub(/[ ,]/,"",$2); print $2}')
  if [ -z "$p95" ]; then
    # no statistics: Gatling crashed, this is not a capacity failure
    echo "probe $rate/s: Gatling crashed, see $OUT/probe-$rate-gatling.log" >&2
    stop_server
    exit 1
  fi
  echo "$rate,$status,$p95" >> "$OUT/probes.csv"
  echo "probe $rate/s: exit $status, p95 ${p95}ms"
  return $status
}

echo "rate,exit,p95_ms" > "$OUT/probes.csv"
rate=$START_RATE
last_pass=0
while probe "$rate"; do
  last_pass=$rate
  rate=$(echo "$rate + $STEP" | bc)
done
midpoint=$(echo "($last_pass + $rate) / 2" | bc -l | xargs printf '%.2f')
if probe "$midpoint"; then
  last_pass=$midpoint
fi
echo "$last_pass" > "$OUT/max-rate"
stop_server
echo "max sustainable rate for $NAME: $last_pass journeys/s"
