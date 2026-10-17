# alf.io: JDK 25 vs JDK 27 benchmark

Compares the memory footprint, GC, JIT and CPU usage of [alf.io](https://github.com/alfio-event/alf.io)
running on JDK 25 and JDK 27, under the same fixed-throughput workload.

## How it works

1. **Images**: two alf.io Docker images, built from the same jar and the same Dockerfile, with a different base image
   (`eclipse-temurin:25-alpine` / `eclipse-temurin:27-alpine`) and `jdk.jfr` included in the jlinked runtime:
   ```shell
   # in the alf.io repository
   ./gradlew distribution
   docker build --build-arg JDK_IMAGE=eclipse-temurin:25-alpine --build-arg EXTRA_JLINK_MODULES=jdk.jfr -t alfio-bench2:jdk25 build/dockerize
   docker build --build-arg JDK_IMAGE=eclipse-temurin:27-alpine --build-arg EXTRA_JLINK_MODULES=jdk.jfr -t alfio-bench2:jdk27 build/dockerize
   ```
2. **Template database**: [`scripts/prepare-template.sh`](scripts/prepare-template.sh) creates an API key and a published
   event with 50,000 seats. Every run starts from a fresh copy of this database.
3. **Workload**: [`PurchaseJourneySimulation`](src/gatling/java/alfio/bench/PurchaseJourneySimulation.java) (Gatling, Java DSL).
   Open model: journeys arrive at a fixed rate, regardless of the server response time. A journey is 3 anonymous
   page views followed by a purchase (reserve, attendee data, confirm). Admin API calls (event statistics, attendees export)
   run alongside. Every random choice is derived from the journey index, so all runs send the same traffic.
4. **Capacity**: [`scripts/capacity.sh`](scripts/capacity.sh) finds the maximum sustainable rate of a configuration
   (p95 < 500 ms, errors < 1%), with probes of increasing rate after a warm-up.
   The measured runs use **80% of the maximum rate of the slowest configuration**.
5. **Measured runs**: [`scripts/run.sh`](scripts/run.sh):
   `30s idle | 2 min ramp-up | 3 min warm-up | 8 min measurement | 60s idle`.
   Only the measurement window is analyzed.
6. **Analysis**: [`analysis/Analyze.java`](analysis/Analyze.java) (JBang) reads the JFR recordings with the
   `jdk.jfr.consumer` API, plus `pidstat` and the client-side latencies, and compares the medians per configuration.

## What is collected

| Source | Data |
|---|---|
| JFR (`settings=profile`, `jdk.Compilation#threshold=0ms`) | RSS, heap after GC, committed heap, metaspace, GC pauses, every JIT compilation (duration, tier), compiler statistics |
| Native Memory Tracking | committed native memory, by category |
| `pidstat -t -u` | CPU usage of every JVM thread, grouped by component: GC, JIT compilers, JFR, VM, application |
| Gatling | latency of every request (client side) |
| `/proc/<pid>/status` | RSS of the Java process, every 2 seconds |

The server container is pinned to 2 cores (`--cpuset-cpus=2,3`, `--memory=2g`); the load generator runs on the other cores.

## Running

```shell
export ALFIO_DEV_PROPERTIES=/path/to/alf.io/application-dev.properties
scripts/prepare-template.sh alfio-bench2:jdk27
scripts/capacity.sh alfio-bench2:jdk25 capacity-jdk25-default
scripts/run.sh alfio-bench2:jdk25 load-jdk25-default-1 <rate>
scripts/run.sh alfio-bench2:jdk25 load-jdk25-xmx256-1 <rate> "-Xms256m -Xmx256m"
jbang analysis/Analyze.java results
```

Requirements: Docker, a PostgreSQL container named `alfio-main-postgres`, JDK 25, JBang, `pidstat` (sysstat), `taskset`.
