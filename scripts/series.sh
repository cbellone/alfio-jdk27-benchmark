#!/usr/bin/env bash
# The measured series: ROUNDS rounds over all configurations (scripts/configs.sh), alternated,
# so that any drift of the machine affects every configuration in the same way.
# Usage: series.sh <rate> [rounds]   (run it detached: setsid nohup scripts/series.sh 7.2 > results/series.log 2>&1 < /dev/null &)
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
source "$HERE/configs.sh"
RATE=$1
ROUNDS=${2:-3}
for round in $(seq 1 "$ROUNDS"); do
  for config in "${CONFIGS[@]}"; do
    IFS='|' read -r name image opts <<< "$config"
    if [ -f "$HERE/../results/load-$name-$round/load-end" ]; then
      echo "=== load-$name-$round: already done"
      continue
    fi
    echo "=== $(date +%T) load-$name-$round"
    "$HERE/run.sh" "$image" "load-$name-$round" "$RATE" "$opts"
  done
done
echo "SERIES-DONE $(date +%T)"
