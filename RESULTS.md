# Detailed results

The full numbers behind the blog post
[Spring Boot on JDK 27 with Leyden: faster startup, less GC work, a few traps](https://ticketguy.dev/blogs/2026-10-20-jdk27-memory/).
The post tells the story; this page has the data, for anyone who wants to check it or dig deeper.

The raw summaries produced by [`analysis/Analyze.java`](analysis/Analyze.java) are in [`data/`](data):
every metric of every run (`*-summary.csv`) and the medians per configuration (`*-summary.md`).

- [Scenario 1: a container with 2 CPUs and 512 MB](#scenario-1-a-container-with-2-cpus-and-512-mb)
- [Scenario 2: a container with 2 CPUs and 1 GB](#scenario-2-a-container-with-2-cpus-and-1-gb)
- [Follow-up experiments](#follow-up-experiments)

## Scenario 1: a container with 2 CPUs and 512 MB

### Setup

| | |
|---|---|
| Machine | Lenovo ThinkPad X1 Carbon Gen 12, Intel Core Ultra 7 155U, 32 GB, Ubuntu 26.04, Docker 29.9 |
| CPU frequency | `performance` governor, turbo capped at 3.0 GHz, AC power |
| alf.io | `--cpuset-cpus=2,3` (both hardware threads of one performance core), `--memory=512m`, no swap |
| PostgreSQL 17.0 | `--cpuset-cpus=0,1` (the other performance core), default settings |
| Gatling | `taskset -c 4-13` (efficiency cores) |
| Heap | `-Xms256m -Xmx256m` for every configuration, GC chosen explicitly |
| Application | the same `alfio-boot.jar` (Spring Boot 4.1.1) in every image |
| JDKs | Azul Zulu `25.0.4.1+1-LTS`, Eclipse Temurin `27+35` |

The configurations ([`scripts/configs.sh`](scripts/configs.sh), images built by [`scripts/build-images.sh`](scripts/build-images.sh)):

| Name | JDK | GC | Compact headers | Startup optimization |
|---|---|---|---|---|
| `jdk25` | 25 | G1 | no | AppCDS (dynamic archive) |
| `jdk25-coh` | 25 | G1 | yes | AppCDS (dynamic archive) |
| `jdk27-nocoh` | 27 | G1 | no | base CDS archive only |
| `jdk27` | 27 | G1 | yes | base CDS archive only |
| `jdk27-aot` | 27 | G1 | yes | AOT cache, trained at startup (102 MB) |
| `jdk27-aot-workload` | 27 | G1 | yes | AOT cache, trained with 5 minutes of traffic (150 MB) |
| `jdk27-parallel-aot` | 27 | ParallelGC | yes | AOT cache, trained at startup |
| `jdk27-parallel-aot-nr3` | 27 | ParallelGC, `-XX:NewRatio=3` | yes | AOT cache, trained at startup |

The AOT configurations run with `-XX:AOTMode=on`. The workload-trained cache was generated with a different journey seed
than the measured runs.

### Capacity

Each search starts with 4 minutes of warm-up, then probes of 30 s ramp-up + 2 min at constant rate.
A probe passes when p95 < 500 ms and errors < 1%. After the first failure, the search bisects down to 0.25 journeys/s,
with 60 s of idle time before each probe.

| Rate (journeys/s) | `jdk25` | `jdk27-aot` | `jdk27-parallel-aot` | `jdk27-parallel-aot-nr3` |
|---|---|---|---|---|
| 3 | 38 ms | 37 ms | 29 ms | 32 ms |
| 4 | 47 ms | 44 ms | 32 ms | 33 ms |
| 5 | 61 ms | 55 ms | 38 ms | 40 ms |
| 6 | 73 ms | 67 ms | 49 ms | 52 ms |
| 7 | 114 ms | 97 ms | 80 ms | 77 ms |
| 7.25 | **698 ms** ✗ | | | |
| 7.5 | **1136 ms** ✗ | 362 ms | 119 ms | 132 ms |
| 7.75 | | **2450 ms** ✗ | **511 ms** ✗ | **1209 ms** ✗ |
| 8 | **2987 ms** ✗ | **2116 ms** ✗ | **884 ms** ✗ | **1058 ms** ✗ |
| **Maximum** | **7.0** | **7.5** | **7.5** | **7.5** |

Values are the p95 of each probe. One journey is about 22 HTTP requests, so 7.0 journeys/s ≈ 152 requests/s.
Raw data: [`capacity-probes.csv`](data/container/capacity-probes.csv).

The measured runs use 80% of the slowest maximum: **5.6 journeys/s**, about 122 requests/s.

### Measured runs

Each run: 30 s idle, 2 min ramp-up, 3 min warm-up, 8 min measurement, forced full GC (`jcmd GC.run`), 60 s idle.
Only GC logging, no JFR or NMT. Two runs per configuration, alternated.
Every run sent 88,285 requests, with no errors and no out-of-memory kills.

Per run (steady-state values are from the 8-minute measurement window):

| Run | Startup | p95 | p99 | JVM CPU | GC CPU | JIT CPU (warm-up) | Full GCs | Live data | Anon memory (avg) |
|---|---|---|---|---|---|---|---|---|---|
| `jdk25-1` | 6.8 s | 73 ms | 142 ms | 119% | 42% | 29% | 9 | 49 MB | 491 MB |
| `jdk25-2` | 7.4 s | 75 ms | 152 ms | 123% | 44% | 30% | 13 | 50 MB | 489 MB |
| `jdk25-coh-1` | 6.9 s | 66 ms | 132 ms | 115% | 39% | 29% | 4 | 45 MB | 486 MB |
| `jdk25-coh-2` | 7.0 s | 68 ms | 135 ms | 115% | 39% | 29% | 9 | 45 MB | 491 MB |
| `jdk27-nocoh-1` | 9.2 s | 75 ms | 152 ms | 122% | 43% | 33% | 16 | 51 MB | 494 MB |
| `jdk27-nocoh-2` | 9.0 s | 74 ms | 149 ms | 124% | 45% | 32% | 11 | 50 MB | 493 MB |
| `jdk27-1` | 9.3 s | 66 ms | 132 ms | 114% | 37% | 34% | 4 | 47 MB | 494 MB |
| `jdk27-2` | 9.4 s | 65 ms | 128 ms | 114% | 37% | 32% | 2 | 47 MB | 493 MB |
| `jdk27-aot-1` | 5.4 s | 65 ms | 129 ms | 113% | 37% | 32% | 4 | 47 MB | 487 MB |
| `jdk27-aot-2` | 5.4 s | 68 ms | 138 ms | 117% | 39% | 33% | 12 | 48 MB | 484 MB |
| `jdk27-aot-workload-1` | 5.2 s | 61 ms | 122 ms | 110% | 35% | 27% | 3 | 47 MB | 470 MB |
| `jdk27-aot-workload-2` | 5.0 s | 62 ms | 127 ms | 113% | 37% | 26% | 3 | 47 MB | 481 MB |
| `jdk27-parallel-aot-1` | 5.3 s | 47 ms | 109 ms | 81% | 16% | 30% | 122 | 46 MB | 491 MB |
| `jdk27-parallel-aot-2` | 5.1 s | 44 ms | 101 ms | 80% | 15% | 29% | 116 | 46 MB | 489 MB |
| `jdk27-parallel-aot-nr3-1` | 5.1 s | 47 ms | 107 ms | 85% | 19% | 31% | 101 | 46 MB | 489 MB |
| `jdk27-parallel-aot-nr3-2` | 5.1 s | 47 ms | 105 ms | 84% | 19% | 29% | 97 | 46 MB | 492 MB |

CPU values are percentages of one CPU (`pidstat -t`, threads grouped by name). Anonymous memory comes from the
container's cgroup (`memory.stat`). The medians of every metric, with deltas against `jdk25`, are in
[`load-summary.md`](data/container/load-summary.md).

#### GC pauses (medians, measurement window)

| | `jdk25` | `jdk25-coh` | `jdk27-nocoh` | `jdk27` | `jdk27-aot` | `jdk27-aot-workload` | `jdk27-parallel-aot` | `jdk27-parallel-aot-nr3` |
|---|---|---|---|---|---|---|---|---|
| Pauses | 4,245 | 3,762 | 4,289 | 3,729 | 3,764 | 3,733 | 3,608 | 5,302 |
| Full collections | 11 | 6.5 | 13.5 | 3 | 8 | 3 | 119 | 99 |
| p99 pause | 45 ms | 47 ms | 47 ms | 43 ms | 44 ms | 41 ms | 128 ms | 126 ms |
| Longest pause | 147 ms | 142 ms | 157 ms | 138 ms | 138 ms | 124 ms | 174 ms | 151 ms |
| Total pause time | 54.6 s | 47.7 s | 57.5 s | 45.5 s | 45.9 s | 41.3 s | 41.0 s | 48.7 s |

#### Warm-up (medians, the 5 minutes before the measurement window)

| | `jdk25` | `jdk25-coh` | `jdk27-nocoh` | `jdk27` | `jdk27-aot` | `jdk27-aot-workload` | `jdk27-parallel-aot` | `jdk27-parallel-aot-nr3` |
|---|---|---|---|---|---|---|---|---|
| JIT threads CPU | 29% | 29% | 32% | 33% | 33% | 26% | 30% | 30% |
| JVM CPU | 136% | 129% | 141% | 134% | 135% | 118% | 104% | 108% |
| p95, first 60 s | 62 ms | 57 ms | 61 ms | 59 ms | 55 ms | 45 ms | 48 ms | 52 ms |
| p99, first 60 s | 145 ms | 130 ms | 140 ms | 139 ms | 132 ms | 100 ms | 121 ms | 131 ms |
| p99, whole warm-up | 163 ms | 144 ms | 169 ms | 151 ms | 151 ms | 126 ms | 127 ms | 129 ms |

#### CPU frequency during the measured runs

Sampled every 5 s from `/sys/devices/system/cpu/cpu{2,3}/cpufreq/scaling_cur_freq`.

| Run | CPU 2 | CPU 3 | Run | CPU 2 | CPU 3 |
|---|---|---|---|---|---|
| `jdk25-1` | 2975 MHz | 2912 MHz | `jdk25-2` | 2960 MHz | 2940 MHz |
| `jdk25-coh-1` | 2988 MHz | 2941 MHz | `jdk25-coh-2` | 2955 MHz | 2951 MHz |
| `jdk27-nocoh-1` | 2995 MHz | 2974 MHz | `jdk27-nocoh-2` | 2979 MHz | 2978 MHz |
| `jdk27-1` | 2967 MHz | 2937 MHz | `jdk27-2` | 2931 MHz | 2823 MHz |
| `jdk27-aot-1` | 2972 MHz | 2908 MHz | `jdk27-aot-2` | 2953 MHz | 2908 MHz |
| `jdk27-aot-workload-1` | 2959 MHz | 2960 MHz | `jdk27-aot-workload-2` | 2994 MHz | 2992 MHz |
| `jdk27-parallel-aot-1` | 2954 MHz | 2953 MHz | `jdk27-parallel-aot-2` | 2950 MHz | 2943 MHz |
| `jdk27-parallel-aot-nr3-1` | 2957 MHz | 2959 MHz | `jdk27-parallel-aot-nr3-2` | 2905 MHz | 2845 MHz |

### Diagnostic runs (JFR and NMT)

One extra run per configuration with JFR (`settings=profile`, every compilation recorded) and Native Memory Tracking,
in a 1 GB container (JFR needs memory of its own). These runs explain the numbers above; don't use them for comparisons:
JFR's own memory and overhead differ between JDK 25 and JDK 27 (see the `Tracing` column below), and on JDK 25 it pushed
the latency up and caused many more full collections. All the metrics: [`diag-summary.md`](data/container/diag-summary.md).

#### Native memory (committed, at the end of the run)

| | Heap | Class data (archive / cache) | Compiled code | GC structures | Metaspace + class space | Other | **Total outside the heap** | JFR (`Tracing`) |
|---|---|---|---|---|---|---|---|---|
| `jdk25` | 256 MB | 83 MB | 92 MB | 44 MB | 52 MB | 13 MB | **284 MB** | 145 MB |
| `jdk25-coh` | 256 MB | 89 MB | 81 MB | 43 MB | 52 MB | 13 MB | **278 MB** | 152 MB |
| `jdk27-nocoh` | 256 MB | 13 MB | 76 MB | 43 MB | 105 MB | 25 MB | **262 MB** | 91 MB |
| `jdk27` | 256 MB | 13 MB | 78 MB | 43 MB | 105 MB | 24 MB | **263 MB** | 101 MB |
| `jdk27-aot` | 256 MB | 92 MB | 76 MB | 43 MB | 49 MB | 11 MB | **272 MB** | 20 MB |
| `jdk27-aot-workload` | 256 MB | 137 MB | 70 MB | 43 MB | 23 MB | 9 MB | **281 MB** | 30 MB |
| `jdk27-parallel-aot` | 256 MB | 91 MB | 86 MB | 11 MB | 51 MB | 11 MB | **250 MB** | 87 MB |
| `jdk27-parallel-aot-nr3` | 256 MB | 91 MB | 87 MB | 11 MB | 51 MB | 12 MB | **252 MB** | 104 MB |

"Other" is everything else except JFR and NMT themselves: symbols, synchronization, threads, internal structures.

Without an archive, the classes are loaded into the metaspace instead of being mapped from the archive or the cache.
The class data in the archive or cache is mapped from a file. The kernel can reclaim those pages, and containers
started from the same image can share them.

#### JIT compilations

| | `jdk25` | `jdk27` | `jdk27-aot` | `jdk27-aot-workload` | `jdk27-parallel-aot` |
|---|---|---|---|---|---|
| Compilations during the warm-up | 27,895 | 28,008 | 26,489 | 22,481 | 26,649 |
| C2 compilations during the warm-up | 6,736 | 6,685 | 6,816 | 5,751 | 6,695 |
| Compilations, whole run | 40,034 | 39,733 | 36,837 | 33,919 | 35,285 |
| OSR compilations | 454 | 476 | 682 | 905 | 707 |
| Compiled code size (end of run) | 112 MB | 103 MB | 97 MB | 77 MB | 89 MB |

C2 compilations over the whole run, by package:

| | `jdk25` | `jdk27-aot` | `jdk27-aot-workload` | `jdk27-parallel-aot` |
|---|---|---|---|---|
| PDF and calendar libraries (openhtmltopdf, PDFBox, biweekly...) | 1,285 / 56 s | 1,322 / 53 s | 1,160 / 37 s | 1,330 / 45 s |
| alf.io | 550 / 23 s | 527 / 16 s | 529 / 13 s | 529 / 13 s |
| Spring | 936 / 31 s | 880 / 25 s | 873 / 17 s | 886 / 19 s |
| Everything else | 6,189 / 88 s | 6,075 / 75 s | 5,707 / 61 s | 5,901 / 60 s |
| **Total** | **8,960 / 198 s** | **8,804 / 168 s** | **8,269 / 129 s** | **8,646 / 137 s** |

Each cell is the number of compilations and their total duration. The durations are wall-clock times from JFR: a
compilation that waits for a CPU takes longer without doing more work, so they overstate the differences. For the cost
of the JIT, use the CPU time of the compiler threads in the tables above. With JFR enabled, the JVM also compiles some
of JFR's own code (e.g. `jdk.internal.event.SocketReadEvent`), which doesn't happen in the measured runs.

The longest C2 compilations:

| `jdk25` | `jdk27-aot` | `jdk27-aot-workload` |
|---|---|---|
| `HttpEntityMethodProcessor.handleReturnValue` (Spring) 1.58 s | `TypeMappedAnnotations$MergedAnnotationFinder.doWithAnnotations` (Spring) 0.82 s | `SocketReadEvent.commit` (JFR) 1.04 s |
| `AbstractMessageConverterMethodProcessor.writeWithMessageConverters` (Spring) 1.30 s | `DisplayListPainter.paintInlineContent` (openhtmltopdf) 0.80 s | `PdfBoxAccessibilityHelper.sortChildrenByDomOrder` (openhtmltopdf) 1.03 s |
| `EventLoader.lambda$loadEventInfo$1` (alf.io) 1.28 s | `DefaultMailer.send` (alf.io) 0.68 s | `SocketWriteEvent.emit` (JFR) 0.97 s |
| `DispatcherServlet.doDispatch` (Spring) 1.02 s | `Matcher$Mapper.mapChild` (openhtmltopdf) 0.68 s | `LambdaForm.compileToBytecode` (JDK) 0.91 s |
| `DisplayListPainter.paintInlineContent` (openhtmltopdf) 0.99 s | `PDDocument.save` (PDFBox) 0.67 s | `ICalWriter.writeComponent` (biweekly) 0.72 s |

### Sizing the heap

Before the benchmark, a few runs at 6 journeys/s on `jdk27-aot` and `jdk25` chose the heap size for the 512 MB container
(these ran before the CPU frequency was capped, so they're only comparable with each other):

- JDK 25 defaults (SerialGC, 128 MB heap, since a 2-CPU / 512 MB container is not "server class"): 17-27% of the requests failed.
- 160 MB: 164 full collections and 34% failed requests.
- 192 MB: 115 full collections.
- 256 MB: the best result, used for all the configurations.

The live data is only about 50 MB, but the attendee export and the PDF generation allocate large, short-lived objects.

## Scenario 2: a container with 2 CPUs and 1 GB

### Setup

The same as scenario 1 (machine, CPU pinning, frequency cap, PostgreSQL on its own CPUs, images), with these differences:

| | |
|---|---|
| alf.io | `--cpuset-cpus=2,3`, `--memory=1g`, no swap |
| Heap | `-Xms512m -Xmx512m`: half of the limit, like 256 MB in 512 MB |
| Configurations | `jdk25`, `jdk27-aot-workload`, `jdk27-parallel-aot` (same images as scenario 1) |
| Runs | `SCENARIO=large MEM_LIMIT=1g scripts/benchmark.sh`, no diagnostic runs |

In a 1 GB container JDK 25 still doesn't consider the machine "server class" (that needs 2 CPUs and about 1.75 GB),
so its defaults would be SerialGC and a 256 MB heap. JDK 27 would pick G1 with 256 MB.

### Capacity

| Rate (journeys/s) | `jdk25` | `jdk27-aot-workload` | `jdk27-parallel-aot` |
|---|---|---|---|
| 3 | 37 ms | 29 ms | 28 ms |
| 4 | 40 ms | 32 ms | 28 ms |
| 5 | 55 ms | 38 ms | 30 ms |
| 6 | 67 ms | 48 ms | 37 ms |
| 7 | 86 ms | 74 ms | 49 ms |
| 7.5 | | 120 ms | |
| 7.75 | | 140 ms | |
| 8 | 350 ms | **503 ms** ✗ | 102 ms |
| 8.25 | **1118 ms** ✗ | | 313 ms |
| 8.5 | **1566 ms** ✗ | | **997 ms** ✗ |
| 9 | **2205 ms** ✗ | | **1841 ms** ✗ |
| **Maximum** | **8.0** | **7.75** | **8.25** |

The three maximums are within one bisection step of each other: the capacity is about the same.
Raw data: [`capacity-probes.csv`](data/1g/capacity-probes.csv).

The measured runs use 80% of the slowest maximum: **6.2 journeys/s**, about 135 requests/s.

### Measured runs

Three runs per configuration. Runs 1 and 2 were alternated, run 3 of each configuration was added later, back to back.
Every run sent 97,745 requests, with no errors and no out-of-memory kills.

| Run | Startup | p95 | p99 | JVM CPU | GC CPU | JIT CPU (warm-up) | Full GCs | Live data | Anon memory (avg) | Peak cgroup |
|---|---|---|---|---|---|---|---|---|---|---|
| `jdk25-1` | 7.2 s | 70 ms | 150 ms | 123% | 39% | 28% | 0 | 50 MB | 788 MB | 893 MB |
| `jdk25-2` | 7.0 s | 73 ms | 156 ms | 129% | 43% | 29% | 0 | 50 MB | 787 MB | 831 MB |
| `jdk25-3` | 7.1 s | 71 ms | 150 ms | 123% | 40% | 29% | 0 | 50 MB | 790 MB | 885 MB |
| `jdk27-aot-workload-1` | 5.1 s | 51 ms | 103 ms | 94% | 16% | 27% | 0 | 47 MB | 785 MB | 932 MB |
| `jdk27-aot-workload-2` | 5.2 s | 55 ms | 112 ms | 99% | 20% | 27% | 0 | 47 MB | 785 MB | 924 MB |
| `jdk27-aot-workload-3` | 5.1 s | 57 ms | 115 ms | 104% | 23% | 27% | 0 | 47 MB | 786 MB | 951 MB |
| `jdk27-parallel-aot-1` | 5.0 s | 39 ms | 80 ms | 74% | 5% | 30% | 14 | 46 MB | 784 MB | 866 MB |
| `jdk27-parallel-aot-2` | 5.4 s | 41 ms | 87 ms | 75% | 5% | 30% | 15 | 47 MB | 789 MB | 880 MB |
| `jdk27-parallel-aot-3` | 5.1 s | 38 ms | 80 ms | 73% | 5% | 30% | 14 | 47 MB | 792 MB | 908 MB |

The peak of the cgroup includes the page cache: the mapped AOT cache of `jdk27-aot-workload` adds about 60 MB of
file-backed memory, which the kernel can reclaim. The medians of every metric are in [`load-summary.md`](data/1g/load-summary.md).

#### GC pauses (medians, measurement window)

| | `jdk25` | `jdk27-aot-workload` | `jdk27-parallel-aot` |
|---|---|---|---|
| Pauses | 3,727 | 2,085 | 1,540 |
| Full collections | 0 | 0 | 14 |
| p99 pause | 32 ms | 38 ms | 25 ms |
| Longest pause | 50 ms | 54 ms | 141 ms |
| Total pause time | 35.1 s | 20.2 s | 12.6 s |

#### Humongous allocations

With a 512 MB heap G1 uses 1 MB regions, so every object of 512 KB or more is _humongous_: it's allocated directly in
the old generation, and G1 checks whether to start a concurrent cycle, which begins with a young pause.
Most of the G1 pauses come from there. Young pauses in the GC log after the first 5 minutes:

| Run | Young pauses | Triggered by a humongous allocation | Mixed | Evacuation failures (whole run) |
|---|---|---|---|---|
| `jdk25-1` | 1,676 | 1,209 | 228 | 74 |
| `jdk25-2` | 1,772 | 1,342 | 213 | 85 |
| `jdk25-3` | 1,709 | 1,267 | 219 | 76 |
| `jdk27-aot-workload-1` | 1,129 | 526 | 313 | 266 |
| `jdk27-aot-workload-2` | 1,350 | 763 | 299 | 156 |
| `jdk27-aot-workload-3` | 1,530 | 1,013 | 268 | 166 |

JDK 27 starts fewer of these cycles, but varies a lot from run to run. The follow-up experiments
[explain why](#compact-headers-in-the-1-gb-container) and [test larger regions](#g1-region-size).

#### Warm-up (medians, the 5 minutes before the measurement window)

| | `jdk25` | `jdk27-aot-workload` | `jdk27-parallel-aot` |
|---|---|---|---|
| JIT threads CPU | 29% | 27% | 30% |
| GC threads CPU | 29% | 7% | 4% |
| p95, first 60 s | 48 ms | 42 ms | 48 ms |
| p99, first 60 s | 110 ms | 96 ms | 109 ms |
| p99, whole warm-up | 158 ms | 104 ms | 103 ms |

#### CPU frequency during the measured runs

The frequency sampler wasn't recording during runs 1 and 2 (the 3.0 GHz cap was active, so the CPUs couldn't run
faster, but there's no proof that they didn't run slower). Run 3 of each configuration was added to have that proof:

| Run | CPU 2 | CPU 3 |
|---|---|---|
| `jdk25-3` | 2990 MHz | 2958 MHz |
| `jdk27-aot-workload-3` | 2995 MHz | 2959 MHz |
| `jdk27-parallel-aot-3` | 2988 MHz | 2955 MHz |

Run 3 agrees with runs 1 and 2 for every configuration.

## Follow-up experiments

The data behind the follow-up post. The runs are in [`scripts/followup.sh`](scripts/followup.sh), the summaries in
[`data/followup`](data/followup). Two runs per configuration, medians unless stated otherwise. The CPU frequency
averaged between 2,947 and 2,997 MHz in the measurement window of every run.

### Allocations outside the TLABs

One run per JDK (`jdk25`, `jdk27-aot-workload`) in a 1.5 GB container (JFR needs memory of its own), 512 MB heap,
6.2 journeys/s, with `jdk.ObjectAllocationOutsideTLAB` and stack traces. Objects of 512 KiB or more (humongous with
1 MB regions): about 86,000 per run on both JDKs, all `byte[]`.

| Size | Count (JDK 25) | Allocated by |
|---|---|---|
| 556,024 bytes | 46,134 | PDFBox, `GlyphTable.read` of `DejaVuSans.ttf` |
| 757,096 bytes | 28,729 | PDFBox, `RandomAccessReadDataStream` (the whole `DejaVuSans.ttf`) |
| 1,310,736 bytes | 5,655 | openhtmltopdf, `OpenUtil.readAll` (the `@font-face` of the invoice template) |
| 655,376 bytes | 5,658 | openhtmltopdf, `OpenUtil.readAll` (same, before the buffer grows) |
| other | 14 | |

The fonts are registered in `TemplateProcessor.renderToPdf` for every PDF; the `Serif` font is registered with the
sans-serif file, so `DejaVuSans.ttf` is parsed twice per document.

### G1 region size

1 GB container, 512 MB heap, 6.2 journeys/s. The 1 MB columns are the medians of scenario 2 (three runs).

| | `jdk25` 1 MB | `jdk25` 2 MB | `jdk25` 4 MB | `jdk27-aot-workload` 1 MB | `jdk27-aot-workload` 2 MB | `jdk27-aot-workload` 4 MB |
|---|---|---|---|---|---|---|
| p95 | 71 ms | 39.5 ms | 38 ms | 55 ms | 38.5 ms | 38.5 ms |
| p99 | 150 ms | 81 ms | 78 ms | 112 ms | 82.5 ms | 80.5 ms |
| JVM CPU | 123% | 75% | 74% | 99% | 74% | 74% |
| GC threads CPU | 40% | 4.1% | 3.3% | 20% | 2.1% | 2.4% |
| Application threads CPU | 80% | 67% | 67% | 74% | 67% | 67% |
| GC pauses | 3,727 | 895 | 822 | 2,085 | 709 | 736 |
| Longest pause | 50 ms | 30 ms | 40 ms | 54 ms | 60 ms | 57 ms |
| Full marking cycles (whole run) | 1,453-1,626 | 115-186 | 82-104 | 317-638 | 2 | 2 |

With 2 MB regions only the 1.3 MB buffer is still humongous; with 4 MB none is. The longest pauses of
`jdk27-aot-workload` with 2 and 4 MB regions are remark pauses. The AOT cache is fully used with every region size.

### Compact headers in the 1 GB container

1 GB container, 512 MB heap, 6.2 journeys/s. `jdk25` and `jdk27-aot-workload` are runs 1 and 2 of scenario 2.

| Run | GC threads CPU | p95 | Full marking cycles | Undone cycles | Live data |
|---|---|---|---|---|---|
| `jdk25-1` | 39% | 70 ms | 1,453 | 96 | 50 MB |
| `jdk25-2` | 43% | 73 ms | 1,626 | 100 | 50 MB |
| `jdk27-nocoh-1` | 37% | 66 ms | 1,554 | 163 | 51 MB |
| `jdk27-nocoh-2` | 41% | 69 ms | 1,767 | 281 | 51 MB |
| `jdk25-coh-1` | 25% | 60 ms | 800 | 31 | 46 MB |
| `jdk25-coh-2` | 16% | 53 ms | 598 | 797 | 46 MB |
| `jdk27-1` | 26% | 61 ms | 958 | 1 | 47 MB |
| `jdk27-2` | 27% | 59 ms | 861 | 461 | 47 MB |
| `jdk27-aot-workload-1` | 16% | 51 ms | 317 | 909 | 47 MB |
| `jdk27-aot-workload-2` | 20% | 55 ms | 536 | 720 | 47 MB |

### Write barrier

512 MB container, 256 MB heap, 5.6 journeys/s, JDK 27 without the AOT cache (the images `jdk27`, `jdk27-parallel`,
`jdk27-serial`). The container runs as the current user, so that `perf stat --per-thread` can attach to the JVM during
the 8 minutes of measurement. CPUs 2 and 3 are the two hardware threads of one performance core; `perf` counts the
`cpu_core` events. Per-thread counters, grouped by thread name, are in [`perf-stat.csv`](data/followup/perf-stat.csv).

| | G1 | ParallelGC | SerialGC |
|---|---|---|---|
| CPU, Jetty threads | 40.1% | 34.5% | 36.1% |
| CPU, scheduled jobs | 32.5% | 27.5% | 27.8% |
| CPU, GC threads | 37.3% | 15.5% | 10.8% (VM Thread) |
| Instructions, application threads | 1,310 G | 1,250 G | 1,254 G |
| Cycles, application threads | 1,025 G | 875 G | 903 G |
| IPC, Jetty | 1.06 | 1.17 | 1.11 |
| IPC, scheduled jobs | 1.55 | 1.75 | 1.73 |
| p95 | 66 ms | 47 ms | 56 ms |
| Full collections | 6.5 | 116 | 164.5 |

With G1, the concurrent marking thread (`G1 Conc#0`) uses 19% of a CPU, and the two `GC Thread`s 17%.
About 58,000 requests in the window: 22.4 million instructions per request with G1, 21.4 with ParallelGC.

### Smallest heap

512 MB container, 5.6 journeys/s. "Enough" means no failed request and p95 below 100 ms. The 256 MB row is from scenario 1.

| Heap | | `jdk25` | `jdk25-coh` | `jdk27` |
|---|---|---|---|---|
| 160 MB | p95 / errors | 3,254 ms / 19.9% | 3,276 ms / 17.6% | 3,148 ms / 17.4% |
| | GC threads CPU | 110% | 108% | 104% |
| 192 MB | p95 | 228 ms | 142.5 ms | 146.5 ms |
| | GC threads CPU | 89% | 77% | 75% |
| 224 MB | p95 | 94.5 ms | 81.5 ms | 78.5 ms |
| | GC threads CPU | 60% | 51% | 49% |
| | Anon memory (avg) | 482 MB | 468 MB | 489 MB |
| 256 MB | p95 | 74 ms | 67 ms | 65.5 ms |
| | GC threads CPU | 43% | 39% | 37% |
| | Anon memory (avg) | 490 MB | 488 MB | 494 MB |

Every run reaches the 512 MB limit of the cgroup (the rest is page cache); none was killed.
