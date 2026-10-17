# The configurations under test: name|image|JVM options. Sourced by capacity-all.sh and series.sh.
# -XX:AOTMode=on: fail at startup if the AOT cache can't be used, instead of silently running without it.
CONFIGS=(
  "old|alfio-bench3:old|-Xms512m -Xmx512m"
  "aot|alfio-bench3:jdk27-aot|-Xms384m -Xmx384m -XX:AOTMode=on"
  "aot-workload|alfio-bench3:jdk27-aot-workload|-Xms384m -Xmx384m -XX:AOTMode=on"
)
