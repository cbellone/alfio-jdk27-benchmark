# alf.io: JDK 25 vs JDK 27 + Leyden AOT cache

Benchmark suite behind the blog post
[Spring Boot on JDK 27 with Leyden: faster startup, less GC work, a few traps](https://ticketguy.dev/blogs/2026-10-20-jdk27-memory/).

The detailed results are in [RESULTS.md](RESULTS.md).

It compares startup time, warm-up, memory footprint, GC, JIT and CPU usage of [alf.io](https://github.com/alfio-event/alf.io)
in different Docker images, under the same fixed-throughput workload.

## Configurations

| Name | Image | JVM | GC | Heap | Startup optimization |
|---|---|---|---|---|---|
| `old` | `alfio-bench3:old` | Azul Zulu 25 | G1 | `-Xms512m -Xmx512m` | AppCDS archive (not used, see the post) |
| `aot` | `alfio-bench3:jdk27-aot` | Temurin 27 | ParallelGC | `-Xms384m -Xmx384m` | AOT cache trained at startup, during `docker build` |
| `aot-workload` | `alfio-bench3:jdk27-aot-workload` | Temurin 27 | ParallelGC | `-Xms384m -Xmx384m` | AOT cache trained with 5 minutes of this workload |

The configurations are defined in [`scripts/configs.sh`](scripts/configs.sh).
The AOT configurations run with `-XX:AOTMode=on`, so the JVM fails at startup instead of silently ignoring the cache.

## How it works

1. **Images**: built from the alf.io repository (`./gradlew distribution`, then `docker build build/dockerize`).
   - `old`: the Dockerfile of the main branch.
   - `aot`: the Dockerfile with JDK 27, the Leyden AOT cache and ParallelGC.

   The alf.io images don't include the `jdk.jfr` module, which the benchmark needs to record JFR data. Before building them:
   - `old` and `aot`: add `jdk.jfr` to the `--add-modules` list of the `jlink` command.
   - `aot` only: add `--add-modules jdk.jfr` to the AOT cache training command and to the `CMD`.
     The JVM uses the AOT cache only if the modules match the training run, and starting a recording adds `jdk.jfr`:
     without this change the cache is disabled, and with `-XX:AOTMode=on` the container doesn't start.

   `aot-workload` is created from `aot` by [`scripts/train-aot.sh`](scripts/train-aot.sh), which runs the application under load
   with `-XX:AOTCacheOutput` and copies the new cache on top of the image ([`docker/workload-aot/Dockerfile`](docker/workload-aot/Dockerfile)).
2. **Template database**: [`scripts/prepare-template.sh`](scripts/prepare-template.sh) creates an API key and a published
   event with 50,000 seats. Every run starts from a fresh copy of this database.
3. **Workload**: [`PurchaseJourneySimulation`](src/gatling/java/alfio/bench/PurchaseJourneySimulation.java) (Gatling, Java DSL).
   Open model: journeys arrive at a fixed rate, regardless of the server response time. A journey is 3 anonymous
   visits to the event page followed by a purchase (reserve, attendee data, confirm), about 22 HTTP requests on average.
   Admin API calls (event statistics, attendees export) run alongside. Every random choice is derived from the journey index,
   so all runs send the same traffic.
4. **Capacity**: [`scripts/capacity.sh`](scripts/capacity.sh) finds the maximum sustainable rate of a configuration
   (p95 < 500 ms, errors < 1%), with probes of increasing rate after a warm-up.
5. **Measured runs**: [`scripts/run.sh`](scripts/run.sh), at **80% of the maximum rate of the slowest configuration**:
   `30s idle | 2 min ramp-up | 3 min warm-up | 8 min measurement | 60s idle`.
   The configurations are alternated, 3 runs each ([`scripts/series.sh`](scripts/series.sh)).
6. **Analysis**: [`analysis/Analyze.java`](analysis/Analyze.java) (JBang) reads the JFR recordings with the
   `jdk.jfr.consumer` API, plus `pidstat` and the client-side latencies, and compares the medians per configuration,
   separately for the warm-up and the measurement window.
   [`analysis/Charts.java`](analysis/Charts.java) renders the SVG charts of the blog post.

## What is collected

| Source | Data |
|---|---|
| Startup | time from `docker run` to the first successful health check, and Spring's own startup time |
| JFR (`settings=profile`, `jdk.Compilation#threshold=0ms`) | RSS, heap after GC, committed heap, metaspace, GC pauses, every JIT compilation (duration, tier), compiler statistics |
| Native Memory Tracking | committed native memory, by category |
| `pidstat -t -u` | CPU usage of every JVM thread, grouped by component: GC, JIT compilers, JFR, VM, Jetty, scheduled jobs |
| Gatling | latency of every request (client side) |
| `/proc/<pid>/status` | RSS of the Java process, every 2 seconds |

The server container is pinned to 2 CPUs (`--cpuset-cpus=2,3`, `--memory=2g`) and the load generator to the others (`taskset`, CPUs 4-13).
Adjust `SERVER_CPUS` and `LOADGEN_CPUS` to your machine: on the laptop used for the blog post (Intel Core Ultra 7 155U),
CPUs 2 and 3 are the two hardware threads of one performance core, and CPUs 4-13 are the efficiency cores.

## Running

Requirements: Docker, a PostgreSQL container named `alfio-main-postgres` with an `alfio` database, JDK 25+, JBang,
`pidstat` (sysstat), `taskset`, `bc`.

```shell
export ALFIO_DEV_PROPERTIES=/path/to/alf.io/application-dev.properties
# Gatling runs in Gradle's JVM: set this if JAVA_HOME points to a JDK older than 25
export BENCH_JAVA_HOME=/path/to/jdk-25

# once: template database, and the image with the workload-trained AOT cache
scripts/prepare-template.sh alfio-bench3:jdk27-aot
scripts/train-aot.sh alfio-bench3:jdk27-aot alfio-bench3:jdk27-aot-workload 5 300 "-Xms384m -Xmx384m"

# capacity search (old, aot), then the measured series at 80% of the slower maximum rate (a few hours)
setsid nohup scripts/benchmark.sh > results/benchmark.log 2>&1 < /dev/null &

# analysis and charts
jbang analysis/Analyze.java results
jbang analysis/Charts.java results charts
```

The scripts skip the steps that already have a result, so `benchmark.sh` can be stopped and started again.
`Analyze.java` writes `results/summary.md`, `results/summary.csv` and `results/timeline.csv`.

## License

[MIT](LICENSE)
