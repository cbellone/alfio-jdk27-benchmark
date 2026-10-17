#!/usr/bin/env bash
# The whole benchmark: capacity search (old, aot), then the measured series at 80% of the slower maximum rate.
# aot-workload has no capacity search: same JVM, heap and GC as aot, only a better trained AOT cache.
# Usage: setsid nohup scripts/benchmark.sh > results/benchmark.log 2>&1 < /dev/null &
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
"$HERE/capacity-all.sh" old aot || exit 1
slowest=$(cat "$HERE"/../results/capacity-old/max-rate "$HERE"/../results/capacity-aot/max-rate | sort -n | head -1)
if [ "$(echo "$slowest <= 0" | bc)" = "1" ]; then
  echo "invalid capacity result: $slowest" >&2
  exit 1
fi
rate=$(echo "$slowest * 0.8" | bc -l | xargs printf '%.1f')
echo "slowest max rate: $slowest journeys/s, measured series at $rate journeys/s"
echo "$rate" > "$HERE/../results/series-rate"
"$HERE/series.sh" "$rate"
echo "BENCHMARK-DONE $(date +%T)"
