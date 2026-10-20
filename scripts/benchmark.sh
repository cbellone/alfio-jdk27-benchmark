#!/usr/bin/env bash
# The whole benchmark, in phases. Each phase skips what is already done, so the script can be stopped and started again.
#   1. workload-trained AOT caches (JOURNEY_SEED=1: different traffic than the measured runs)
#   2. capacity search for CAPACITY_CONFIGS (scripts/configs.sh)
#   3. measured series (PROFILE=light: no JFR/NMT) at 80% of the slowest maximum rate, ROUNDS rounds
#   4. one diagnostic run per configuration with JFR and NMT (PROFILE=jfr), with more memory for JFR itself
#      (container scenario only, unless DIAG=yes)
# Usage: setsid nohup scripts/benchmark.sh > results/benchmark.log 2>&1 < /dev/null &
#        SCENARIO=large MEM_LIMIT=1g|2g: a bigger container, run names start with the limit
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
RESULTS="$HERE/../results"
source "$HERE/configs.sh"
ROUNDS=${ROUNDS:-2}
if [ -z "${DIAG:-}" ]; then
  DIAG=yes
  if [ "$SCENARIO" != "container" ]; then
    DIAG=no
  fi
fi
TRAIN_RATE=${TRAIN_RATE:-4}

echo "=== $(date +%T) phase 1: workload-trained AOT caches"
if ! docker image inspect alfio-bench4:jdk27-aot-workload >/dev/null 2>&1; then
  "$HERE/train-aot.sh" alfio-bench4:jdk27-aot alfio-bench4:jdk27-aot-workload "$TRAIN_RATE" 300 "-Xms${HEAP:-256m} -Xmx${HEAP:-256m}" || exit 1
fi

echo "=== $(date +%T) phase 2: capacity"
# shellcheck disable=SC2086
"$HERE/capacity-all.sh" $CAPACITY_CONFIGS || exit 1
slowest=$(for c in $CAPACITY_CONFIGS; do cat "$RESULTS/${RUN_PREFIX}capacity-$c/max-rate"; done | sort -n | head -1)
if [ "$(echo "$slowest <= 0" | bc)" = "1" ]; then
  echo "invalid capacity result: $slowest" >&2
  exit 1
fi
rate=$(echo "$slowest * 0.8" | bc -l | xargs printf '%.1f')
echo "slowest max rate: $slowest journeys/s, measured series at $rate journeys/s"
echo "$rate" > "$RESULTS/${RUN_PREFIX}series-rate"

echo "=== $(date +%T) phase 3: measured series"
"$HERE/series.sh" "$rate" "$ROUNDS"

echo "=== $(date +%T) phase 4: diagnostic runs (JFR)"
for config in "${CONFIGS[@]}"; do
  if [ "$DIAG" != "yes" ]; then
    break
  fi
  IFS='|' read -r name image opts <<< "$config"
  if [ -f "$RESULTS/${RUN_PREFIX}diag-$name-1/load-end" ]; then
    continue
  fi
  PROFILE=jfr MEM_LIMIT=${DIAG_MEM_LIMIT:-1g} "$HERE/run.sh" "$image" "${RUN_PREFIX}diag-$name-1" "$rate" "$opts"
done
echo "BENCHMARK-DONE $(date +%T)"
