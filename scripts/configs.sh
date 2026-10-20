# The configurations under test: name|image|JVM options. Sourced by capacity-all.sh, series.sh and benchmark.sh.
# Images: scripts/build-images.sh (the -workload ones: scripts/train-aot.sh).
# -XX:AOTMode=on: fail at startup if the AOT cache can't be used, instead of silently running without it.
SCENARIO=${SCENARIO:-container}
if [ "$SCENARIO" = "large" ]; then
  # A container with 2 CPUs and more memory (MEM_LIMIT=1g or 2g), PostgreSQL on its own CPUs as in the container
  # scenario. The heap is half of the limit, like 256 MB in 512 MB. The run names start with the limit, e.g. "1g-".
  RUN_PREFIX="${MEM_LIMIT:?MEM_LIMIT is required}-"
  case "$MEM_LIMIT" in
    1g) HEAP=${HEAP:-512m} ;;
    2g) HEAP=${HEAP:-1g} ;;
    *) HEAP=${HEAP:?HEAP is required for MEM_LIMIT=$MEM_LIMIT} ;;
  esac
  CONFIGS=(
    "jdk25|alfio-bench4:jdk25|-Xms$HEAP -Xmx$HEAP"
    "jdk27-aot-workload|alfio-bench4:jdk27-aot-workload|-Xms$HEAP -Xmx$HEAP -XX:AOTMode=on"
    "jdk27-parallel-aot|alfio-bench4:jdk27-parallel-aot|-Xms$HEAP -Xmx$HEAP -XX:AOTMode=on"
  )
  CAPACITY_CONFIGS=${CAPACITY_CONFIGS:-"jdk25 jdk27-aot-workload jdk27-parallel-aot"}
else
  # A container with 2 CPUs and MEM_LIMIT (see lib.sh). All the configurations get the same fixed heap (HEAP).
  RUN_PREFIX=""
  HEAP=${HEAP:-256m}
  CONFIGS=(
    "jdk25|alfio-bench4:jdk25|-Xms$HEAP -Xmx$HEAP"
    "jdk25-coh|alfio-bench4:jdk25-coh|-Xms$HEAP -Xmx$HEAP"
    "jdk27-nocoh|alfio-bench4:jdk27-nocoh|-Xms$HEAP -Xmx$HEAP"
    "jdk27|alfio-bench4:jdk27|-Xms$HEAP -Xmx$HEAP"
    "jdk27-aot|alfio-bench4:jdk27-aot|-Xms$HEAP -Xmx$HEAP -XX:AOTMode=on"
    "jdk27-aot-workload|alfio-bench4:jdk27-aot-workload|-Xms$HEAP -Xmx$HEAP -XX:AOTMode=on"
    "jdk27-parallel-aot|alfio-bench4:jdk27-parallel-aot|-Xms$HEAP -Xmx$HEAP -XX:AOTMode=on"
    "jdk27-parallel-aot-nr3|alfio-bench4:jdk27-parallel-aot|-Xms$HEAP -Xmx$HEAP -XX:AOTMode=on -XX:NewRatio=3"
  )
  # the configurations whose maximum capacity is measured: the baseline and the GC candidates
  CAPACITY_CONFIGS=${CAPACITY_CONFIGS:-"jdk25 jdk27-aot jdk27-parallel-aot jdk27-parallel-aot-nr3"}
fi
