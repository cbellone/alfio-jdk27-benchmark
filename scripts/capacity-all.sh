#!/usr/bin/env bash
# Capacity search for the configurations listed as arguments (default: all, see scripts/configs.sh).
# Configurations that already have a result (max-rate) are skipped, so the script can be stopped and started again.
# Usage: capacity-all.sh [config...]   (run it detached: setsid nohup scripts/capacity-all.sh > results/capacity.log 2>&1 < /dev/null &)
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
source "$HERE/configs.sh"
for config in "${CONFIGS[@]}"; do
  IFS='|' read -r name image opts <<< "$config"
  if [ $# -gt 0 ] && [[ " $* " != *" $name "* ]]; then
    continue
  fi
  if [ -f "$HERE/../results/${RUN_PREFIX}capacity-$name/max-rate" ]; then
    echo "=== ${RUN_PREFIX}capacity-$name: already done ($(cat "$HERE/../results/${RUN_PREFIX}capacity-$name/max-rate") journeys/s)"
    continue
  fi
  echo "=== $(date +%T) ${RUN_PREFIX}capacity-$name"
  "$HERE/capacity.sh" "$image" "${RUN_PREFIX}capacity-$name" "$opts"
done
echo "slowest configuration: $(grep -H . "$HERE"/../results/"${RUN_PREFIX}"capacity-*/max-rate | sort -t: -k2 -n | head -1)"
echo "CAPACITY-DONE $(date +%T)"
