#!/usr/bin/env bash
# The experiments of the follow-up post, in phases. Like benchmark.sh, every run that is already done is skipped,
# so the script can be stopped and started again. The run names start with "fu-".
#   1. allocations outside the TLABs (humongous objects), with JFR, in a 1.5 GB container (JFR needs memory of its own)
#   2. G1 region size: 2 MB and 4 MB, in the 1 GB container of scenario 2
#   3. write barrier: G1, ParallelGC and SerialGC without the AOT cache, with perf, in the 512 MB container
#   4. smallest heap: 160, 192 and 224 MB in the 512 MB container (256 MB: the runs of scenario 1)
#   5. compact headers on and off, in the 1 GB container of scenario 2
# Images: scripts/build-images.sh (jdk27-parallel and jdk27-serial are only used here).
# Usage: setsid nohup scripts/followup.sh > results/followup.log 2>&1 < /dev/null &
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
RESULTS="$HERE/../results"
ROUNDS=${ROUNDS:-2}
# the rates of scenario 1 (512 MB) and scenario 2 (1 GB)
RATE_512=$(cat "$RESULTS/series-rate")
RATE_1G=$(cat "$RESULTS/1g-series-rate")

# measure <run-name> <image> <rate> <JVM options>, with the environment of the caller (MEM_LIMIT, PROFILE...)
measure() {
  local name=$1 image=$2 rate=$3 opts=$4
  if [ -f "$RESULTS/$name/load-end" ]; then
    echo "=== $name: already done"
    return
  fi
  echo "=== $(date +%T) $name"
  "$HERE/run.sh" "$image" "$name" "$rate" "$opts"
}

echo "=== $(date +%T) phase 1: allocations outside the TLABs"
export MEM_LIMIT=1536m PROFILE=alloc
measure fu-alloc-jdk25-1 alfio-bench4:jdk25 "$RATE_1G" "-Xms512m -Xmx512m"
measure fu-alloc-jdk27-aot-workload-1 alfio-bench4:jdk27-aot-workload "$RATE_1G" "-Xms512m -Xmx512m -XX:AOTMode=on"

echo "=== $(date +%T) phase 2: G1 region size"
export MEM_LIMIT=1g PROFILE=light
for round in $(seq 1 "$ROUNDS"); do
  for size in 2m 4m; do
    measure "fu-region-jdk25-$size-$round" alfio-bench4:jdk25 "$RATE_1G" "-Xms512m -Xmx512m -XX:G1HeapRegionSize=$size"
    measure "fu-region-jdk27-aot-workload-$size-$round" alfio-bench4:jdk27-aot-workload "$RATE_1G" \
      "-Xms512m -Xmx512m -XX:AOTMode=on -XX:G1HeapRegionSize=$size"
  done
done

echo "=== $(date +%T) phase 3: write barrier"
export MEM_LIMIT=512m PROFILE=light PERF=yes CONTAINER_USER="$(id -u):$(id -g)"
for round in $(seq 1 "$ROUNDS"); do
  measure "fu-barrier-jdk27-$round" alfio-bench4:jdk27 "$RATE_512" "-Xms256m -Xmx256m"
  measure "fu-barrier-jdk27-parallel-$round" alfio-bench4:jdk27-parallel "$RATE_512" "-Xms256m -Xmx256m"
  measure "fu-barrier-jdk27-serial-$round" alfio-bench4:jdk27-serial "$RATE_512" "-Xms256m -Xmx256m"
done
unset PERF CONTAINER_USER

echo "=== $(date +%T) phase 4: smallest heap"
export MEM_LIMIT=512m PROFILE=light
for round in $(seq 1 "$ROUNDS"); do
  for heap in 160m 192m 224m; do
    for config in jdk25 jdk25-coh jdk27; do
      measure "fu-heap-$config-$heap-$round" "alfio-bench4:$config" "$RATE_512" "-Xms$heap -Xmx$heap"
    done
  done
done

echo "=== $(date +%T) phase 5: compact headers with the 512 MB heap"
export MEM_LIMIT=1g PROFILE=light
for round in $(seq 1 "$ROUNDS"); do
  for config in jdk25-coh jdk27-nocoh jdk27; do
    measure "fu-coh-$config-$round" "alfio-bench4:$config" "$RATE_1G" "-Xms512m -Xmx512m"
  done
done
echo "FOLLOWUP-DONE $(date +%T)"
