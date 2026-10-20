#!/usr/bin/env bash
# Finds the maximum sustainable arrival rate (journeys/s) of one configuration.
# After a warm-up, it runs a sequence of probes at increasing rates on the same JVM.
# A probe passes when less than 1% of the requests fail and the p95 latency stays below P95_LIMIT_MS.
# The search stops at the first failing probe, then bisects between the last pass and the failure, down to RESOLUTION.
# Before each bisection probe the server idles for COOLDOWN_SECONDS, so that the backlog of a failed probe doesn't
# spill over. If the server dies (e.g. killed for exceeding its memory limit), the probe fails and a new server is
# started and warmed up (results/<run-name>-restart-N).
#
# Usage: capacity.sh <image> <run-name> [extra JVM options]
set -uo pipefail
source "$(dirname "$0")/lib.sh"
IMAGE=$1; NAME=$2; EXTRA_OPTS=${3:-}
P95_LIMIT_MS=${P95_LIMIT_MS:-500}
START_RATE=${START_RATE:-3}
STEP=${STEP:-1}
RESOLUTION=${RESOLUTION:-0.25}
COOLDOWN_SECONDS=${COOLDOWN_SECONDS:-60}
RESULT="$ROOT/results/$NAME"
restarts=0

# warm-up: 1 minute of ramp-up, then 3 minutes at 2/3 of the first probe
start_and_warm_up() {
  local name=$NAME
  if [ "$restarts" -gt 0 ]; then
    name="$NAME-restart-$restarts"
  fi
  start_server "$IMAGE" "$name" "$EXTRA_OPTS" || return 1
  echo "warm-up"
  if ! gatling warmup "$(echo "$START_RATE * 2 / 3" | bc -l | xargs printf '%.2f')" 60 180; then
    echo "warm-up failed, see $OUT/warmup-gatling.log" >&2
    stop_server
    return 1
  fi
}

probe() {
  local rate=$1
  gatling "probe-$rate" "$rate" 30 120 "$P95_LIMIT_MS"
  local status=$?
  local p95
  p95=$(grep '95th percentile' "$OUT/probe-$rate-gatling.log" | head -1 | awk -F'|' '{gsub(/[ ,]/,"",$2); print $2}')
  if ! server_alive; then
    echo "$rate,died,$p95" >> "$RESULT/probes.csv"
    echo "probe $rate/s: the server died, see $OUT/container-state"
    stop_server
    restarts=$((restarts + 1))
    start_and_warm_up || exit 1
    return 1
  fi
  if [ -z "$p95" ]; then
    # no statistics: Gatling crashed, this is not a capacity failure
    echo "probe $rate/s: Gatling crashed, see $OUT/probe-$rate-gatling.log" >&2
    stop_server
    exit 1
  fi
  echo "$rate,$status,$p95" >> "$RESULT/probes.csv"
  echo "probe $rate/s: exit $status, p95 ${p95}ms"
  return $status
}

start_and_warm_up || exit 1
echo "rate,exit,p95_ms" > "$RESULT/probes.csv"
rate=$START_RATE
last_pass=0
while probe "$rate"; do
  last_pass=$rate
  rate=$(echo "$rate + $STEP" | bc)
done
first_fail=$rate
while [ "$(echo "$first_fail - $last_pass > $RESOLUTION" | bc)" = "1" ]; do
  sleep "$COOLDOWN_SECONDS"
  midpoint=$(echo "($last_pass + $first_fail) / 2" | bc -l | xargs printf '%.2f')
  if probe "$midpoint"; then
    last_pass=$midpoint
  else
    first_fail=$midpoint
  fi
done
echo "$last_pass" > "$RESULT/max-rate"
stop_server
echo "max sustainable rate for $NAME: $last_pass journeys/s"
