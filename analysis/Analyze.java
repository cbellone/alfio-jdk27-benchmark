///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Extracts the metrics of every measured run (results/load-*) and compares the configurations.
 * Only the measurement window is considered, except for the "compilation (whole run)" metrics.
 * <p>
 * Usage: jbang analysis/Analyze.java results
 * Output: results/summary.csv (config,run,metric,value) and results/summary.md (medians per configuration)
 */
public class Analyze {

    private static final double MB = 1024 * 1024;

    record Window(long from, long to) {
        boolean contains(long epochMillis) {
            return epochMillis >= from && epochMillis <= to;
        }

        boolean contains(Instant instant) {
            return contains(instant.toEpochMilli());
        }
    }

    public static void main(String[] args) throws IOException {
        var root = Path.of("results");
        if (args.length > 0) {
            root = Path.of(args[0]);
        }
        List<Path> runs;
        try (Stream<Path> list = Files.list(root)) {
            runs = list.filter(p -> p.getFileName().toString().startsWith("load-") && Files.exists(p.resolve("recording.jfr")))
                .sorted()
                .toList();
        }
        // config -> run -> metric -> value
        Map<String, Map<String, Map<String, Double>>> results = new TreeMap<>();
        var csv = new StringBuilder("config,run,metric,value\n");
        var timeline = new StringBuilder("config,run,second,p95_ms,requests_per_second\n");
        for (var run : runs) {
            var name = run.getFileName().toString();
            // load-aot-workload-1 -> aot-workload
            var config = name.substring("load-".length(), name.lastIndexOf('-'));
            System.err.println("analyzing " + name);
            var metrics = analyzeRun(run, config, timeline);
            results.computeIfAbsent(config, _ -> new TreeMap<>()).put(name, metrics);
            metrics.forEach((k, v) -> csv.append(config).append(',').append(name).append(',').append(k).append(',').append(v).append('\n'));
        }
        Files.writeString(root.resolve("summary.csv"), csv);
        Files.writeString(root.resolve("timeline.csv"), timeline);
        var markdown = report(results);
        Files.writeString(root.resolve("summary.md"), markdown);
        System.out.println(markdown);
    }

    private static Map<String, Double> analyzeRun(Path run, String configName, StringBuilder timeline) throws IOException {
        var config = parseConfig(run.resolve("config.txt"));
        long loadStart = Long.parseLong(Files.readString(run.resolve("load-start-millis")).trim());
        long measureStart = loadStart + 1000L * (config.get("ramp") + config.get("warmup"));
        // the measurement window: steady state
        var window = new Window(measureStart, measureStart + 1000L * config.get("measure"));
        // ramp-up + warm-up: how fast the JVM gets to its steady state
        var warmup = new Window(loadStart, measureStart - 1);
        Map<String, Double> metrics = new LinkedHashMap<>();
        startup(run, metrics);
        jfr(run.resolve("recording.jfr"), window, warmup, metrics);
        if (Files.exists(run.resolve("pidstat.txt"))) {
            pidstat(run.resolve("pidstat.txt"), window, "cpu.", metrics);
            pidstat(run.resolve("pidstat.txt"), warmup, "warmup.cpu.", metrics);
        }
        var latencyFile = run.resolve("load-latencies.csv");
        latencies(latencyFile, window, config.get("measure"), "latency.", metrics);
        latencies(latencyFile, warmup, config.get("ramp") + config.get("warmup"), "warmup.latency.", metrics);
        timeToSteadyState(latencyFile, loadStart, measureStart, metrics.get("latency.p95_ms"), configName, run.getFileName().toString(), timeline, metrics);
        memorySamples(run.resolve("memory.csv"), window, metrics);
        return metrics;
    }

    /**
     * Startup: Spring Boot's own measurement, and the time from "docker run" to the first successful health check.
     */
    private static void startup(Path run, Map<String, Double> metrics) throws IOException {
        var log = run.resolve("container.log");
        if (Files.exists(log)) {
            var matcher = Pattern.compile("Started \\S+ in ([0-9.]+) seconds").matcher(Files.readString(log));
            if (matcher.find()) {
                metrics.put("startup.spring_seconds", Double.parseDouble(matcher.group(1)));
            }
        }
        var started = run.resolve("container-start-millis");
        var ready = run.resolve("ready-millis");
        if (Files.exists(started) && Files.exists(ready)) {
            long millis = Long.parseLong(Files.readString(ready).trim()) - Long.parseLong(Files.readString(started).trim());
            metrics.put("startup.ready_seconds", millis / 1000.0);
        }
    }

    /**
     * Splits ramp-up + warm-up into 10s buckets and computes the p95 of a rolling 30s window (3 buckets), so that a
     * single GC pause doesn't count as "not warmed up". The steady state is reached at the beginning of the first window
     * after which every window stays within 1.5x the p95 of the measurement window.
     */
    private static void timeToSteadyState(Path file, long loadStart, long measureStart, double steadyP95, String config,
                                          String run, StringBuilder timeline, Map<String, Double> metrics) throws IOException {
        int bucketSeconds = 10;
        int windowBuckets = 3;
        Map<Integer, List<Double>> buckets = new TreeMap<>();
        try (var lines = Files.lines(file)) {
            for (var line : (Iterable<String>) lines.skip(1)::iterator) {
                var fields = line.split(",");
                if (fields.length < 4 || !fields[3].equals("OK")) {
                    continue;
                }
                long epoch = Long.parseLong(fields[0]);
                if (epoch < loadStart) {
                    continue;
                }
                int bucket = (int) ((epoch - loadStart) / 1000 / bucketSeconds);
                buckets.computeIfAbsent(bucket, _ -> new ArrayList<>()).add(Double.parseDouble(fields[2]));
            }
        }
        for (var entry : buckets.entrySet()) {
            var values = entry.getValue().stream().sorted().toList();
            timeline.append(config).append(',').append(run).append(',').append(entry.getKey() * bucketSeconds).append(',')
                .append(percentile(values, 95)).append(',').append(values.size() / (double) bucketSeconds).append('\n');
        }
        int lastBucketBeforeMeasurement = (int) ((measureStart - loadStart) / 1000 / bucketSeconds) - 1;
        int steadyFrom = 0;
        for (int first = 0; first + windowBuckets - 1 <= lastBucketBeforeMeasurement; first++) {
            var window = new ArrayList<Double>();
            for (int b = first; b < first + windowBuckets; b++) {
                window.addAll(buckets.getOrDefault(b, List.of()));
            }
            window.sort(Comparator.naturalOrder());
            if (!window.isEmpty() && percentile(window, 95) > 1.5 * steadyP95) {
                steadyFrom = first + 1;
            }
        }
        metrics.put("warmup.time_to_steady_state_seconds", (double) steadyFrom * bucketSeconds);
        // latency of the very first requests, when the JIT has compiled the least
        for (int seconds : List.of(30, 60)) {
            var first = new ArrayList<Double>();
            for (int b = 0; b < seconds / bucketSeconds; b++) {
                first.addAll(buckets.getOrDefault(b, List.of()));
            }
            first.sort(Comparator.naturalOrder());
            metrics.put("warmup.first_" + seconds + "s_p95_ms", percentile(first, 95));
            metrics.put("warmup.first_" + seconds + "s_p99_ms", percentile(first, 99));
        }
    }

    private static Map<String, Long> parseConfig(Path file) throws IOException {
        Map<String, Long> config = new HashMap<>();
        var matcher = Pattern.compile("(rate|ramp|warmup|measure)=([0-9.]+)").matcher(Files.readString(file));
        while (matcher.find()) {
            config.put(matcher.group(1), (long) Double.parseDouble(matcher.group(2)));
        }
        return config;
    }

    // ---------------------------------------------------------------------------------------------------------
    // JFR
    // ---------------------------------------------------------------------------------------------------------

    private static void jfr(Path recording, Window window, Window warmup, Map<String, Double> metrics) throws IOException {
        var rss = new DoubleSummaryStatistics();
        var heapAfterGc = new DoubleSummaryStatistics();
        var heapCommitted = new DoubleSummaryStatistics();
        var metaspace = new DoubleSummaryStatistics();
        var nmt = new DoubleSummaryStatistics();
        var cpu = new DoubleSummaryStatistics();
        double gcPauses = 0;
        double gcLongest = 0;
        int gcCount = 0;
        // compilation durations, per tier: [whole run, measurement window]
        Map<String, List<Double>> compilationsWholeRun = new TreeMap<>();
        Map<String, List<Double>> compilationsWindow = new TreeMap<>();
        Map<String, List<Double>> compilationsWarmup = new TreeMap<>();
        RecordedEvent lastCompilerStatistics = null;
        try (var file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                var event = file.readEvent();
                var type = event.getEventType().getName();
                if (type.equals("jdk.Compilation")) {
                    var tier = tier(event.getInt("compileLevel"));
                    double millis = event.getDuration().toNanos() / 1e6;
                    compilationsWholeRun.computeIfAbsent(tier, _ -> new ArrayList<>()).add(millis);
                    if (window.contains(event.getStartTime())) {
                        compilationsWindow.computeIfAbsent(tier, _ -> new ArrayList<>()).add(millis);
                    }
                    if (warmup.contains(event.getStartTime())) {
                        compilationsWarmup.computeIfAbsent(tier, _ -> new ArrayList<>()).add(millis);
                    }
                    continue;
                }
                if (type.equals("jdk.CompilerStatistics")) {
                    lastCompilerStatistics = event;
                    continue;
                }
                if (!window.contains(event.getStartTime())) {
                    continue;
                }
                switch (type) {
                    case "jdk.ResidentSetSize" -> rss.accept(event.getLong("size") / MB);
                    case "jdk.GCHeapSummary" -> {
                        heapCommitted.accept(event.<jdk.jfr.consumer.RecordedObject>getValue("heapSpace").getLong("committedSize") / MB);
                        if ("After GC".equals(event.getString("when"))) {
                            heapAfterGc.accept(event.getLong("heapUsed") / MB);
                        }
                    }
                    case "jdk.MetaspaceSummary" -> metaspace.accept(event.<jdk.jfr.consumer.RecordedObject>getValue("metaspace").getLong("committed") / MB);
                    case "jdk.NativeMemoryUsageTotal" -> nmt.accept(event.getLong("committed") / MB);
                    case "jdk.CPULoad" -> cpu.accept((event.getFloat("jvmUser") + event.getFloat("jvmSystem")) * 100);
                    case "jdk.GarbageCollection" -> {
                        gcCount++;
                        gcPauses += event.getDuration("sumOfPauses").toNanos() / 1e6;
                        gcLongest = Math.max(gcLongest, event.getDuration("longestPause").toNanos() / 1e6);
                    }
                    default -> {
                    }
                }
            }
        }
        metrics.put("jfr.rss_avg_mb", rss.getAverage());
        metrics.put("jfr.rss_max_mb", rss.getMax());
        metrics.put("heap.after_gc_avg_mb", heapAfterGc.getAverage());
        metrics.put("heap.after_gc_max_mb", heapAfterGc.getMax());
        metrics.put("heap.committed_max_mb", heapCommitted.getMax());
        metrics.put("metaspace.committed_max_mb", metaspace.getMax());
        metrics.put("nmt.committed_avg_mb", nmt.getAverage());
        metrics.put("jfr.jvm_cpu_avg_pct_of_host", cpu.getAverage());
        metrics.put("gc.count", (double) gcCount);
        metrics.put("gc.pause_total_ms", gcPauses);
        metrics.put("gc.pause_max_ms", gcLongest);
        compilationMetrics("compilation_whole_run", compilationsWholeRun, metrics);
        compilationMetrics("compilation_window", compilationsWindow, metrics);
        compilationMetrics("compilation_warmup", compilationsWarmup, metrics);
        if (lastCompilerStatistics != null) {
            metrics.put("compiler_stats.total_time_ms", (double) lastCompilerStatistics.getDuration("totalTimeSpent").toMillis());
            metrics.put("compiler_stats.peak_time_ms", (double) lastCompilerStatistics.getDuration("peakTimeSpent").toMillis());
            metrics.put("compiler_stats.compile_count", (double) lastCompilerStatistics.getInt("compileCount"));
            metrics.put("compiler_stats.osr_count", (double) lastCompilerStatistics.getInt("osrCompileCount"));
            metrics.put("compiler_stats.invalidated_count", (double) lastCompilerStatistics.getInt("invalidatedCount"));
            metrics.put("compiler_stats.nmethods_size_mb", lastCompilerStatistics.getLong("nmethodsSize") / MB);
        }
    }

    private static String tier(int level) {
        if (level == 4) {
            return "c2";
        }
        return "c1";
    }

    private static void compilationMetrics(String prefix, Map<String, List<Double>> byTier, Map<String, Double> metrics) {
        var all = new ArrayList<Double>();
        byTier.values().forEach(all::addAll);
        byTier.put("all", all);
        for (var tier : List.of("c1", "c2", "all")) {
            var durations = byTier.getOrDefault(tier, List.of()).stream().sorted().toList();
            metrics.put(prefix + "." + tier + ".count", (double) durations.size());
            metrics.put(prefix + "." + tier + ".total_ms", durations.stream().mapToDouble(d -> d).sum());
            metrics.put(prefix + "." + tier + ".p99_ms", percentile(durations, 99));
            metrics.put(prefix + "." + tier + ".max_ms", percentile(durations, 100));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // pidstat: CPU usage per JVM component
    // ---------------------------------------------------------------------------------------------------------

    private static String component(String thread) {
        if (thread.startsWith("GC Thread") || thread.startsWith("G1 ")) {
            return "gc";
        }
        if (thread.startsWith("C1 Comp") || thread.startsWith("C2 Comp")) {
            return "jit";
        }
        if (thread.startsWith("JFR ")) {
            return "jfr";
        }
        if (thread.startsWith("VM ")) {
            return "vm";
        }
        if (thread.startsWith("qtp")) {
            return "app.jetty";
        }
        // Spring @Scheduled jobs (e.g. alf.io's email queue)
        if (thread.startsWith("scheduling-")) {
            return "app.scheduled";
        }
        return "app.other";
    }

    private static void pidstat(Path file, Window window, String prefix, Map<String, Double> metrics) throws IOException {
        var lines = Files.readAllLines(file);
        // "Linux 7.0.0-34-generic (karpa) 	2026-10-08 	_x86_64_	(14 CPU)", or "10/08/26" without S_TIME_FORMAT=ISO
        var dateField = lines.getFirst().split("\\s+")[3];
        var date = LocalDate.now();
        if (dateField.contains("/")) {
            date = LocalDate.parse(dateField, java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy"));
        } else {
            date = LocalDate.parse(dateField);
        }
        // sample time -> component -> %CPU (sum over the threads of the component)
        Map<Long, Map<String, Double>> samples = new TreeMap<>();
        for (var line : lines) {
            var fields = line.trim().split("\\s+");
            if (fields.length < 11 || line.startsWith("#") || !fields[0].matches("\\d\\d:\\d\\d:\\d\\d")) {
                continue;
            }
            long epoch = date.atTime(LocalTime.parse(fields[0])).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (!window.contains(epoch)) {
                continue;
            }
            double cpu = Double.parseDouble(fields[8]);
            var command = String.join(" ", Arrays.copyOfRange(fields, 10, fields.length));
            var sample = samples.computeIfAbsent(epoch, _ -> new TreeMap<>());
            if (!fields[3].equals("-")) {
                // thread line: "|__GC Thread#0"
                sample.merge(component(command.substring(3)), cpu, Double::sum);
            } else {
                sample.merge("process", cpu, Double::sum);
            }
        }
        var components = new TreeSet<String>();
        samples.values().forEach(s -> components.addAll(s.keySet()));
        for (var component : components) {
            double avg = samples.values().stream().mapToDouble(s -> s.getOrDefault(component, 0.0)).average().orElse(0);
            // pidstat reports % of one core
            metrics.put(prefix + component + "_pct_of_core", avg);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // client-side latency + memory samples
    // ---------------------------------------------------------------------------------------------------------

    private static void latencies(Path file, Window window, long windowSeconds, String prefix, Map<String, Double> metrics) throws IOException {
        var ok = new ArrayList<Double>();
        int failed = 0;
        try (var lines = Files.lines(file)) {
            for (var line : (Iterable<String>) lines.skip(1)::iterator) {
                var fields = line.split(",");
                if (fields.length < 4 || !window.contains(Long.parseLong(fields[0]))) {
                    continue;
                }
                if (fields[3].equals("OK")) {
                    ok.add(Double.parseDouble(fields[2]));
                } else {
                    failed++;
                }
            }
        }
        ok.sort(Comparator.naturalOrder());
        int total = ok.size() + failed;
        metrics.put(prefix + "throughput_rps", total / (double) windowSeconds);
        metrics.put(prefix + "error_pct", 100.0 * failed / Math.max(total, 1));
        metrics.put(prefix + "p50_ms", percentile(ok, 50));
        metrics.put(prefix + "p95_ms", percentile(ok, 95));
        metrics.put(prefix + "p99_ms", percentile(ok, 99));
        metrics.put(prefix + "max_ms", percentile(ok, 100));
    }

    private static void memorySamples(Path file, Window window, Map<String, Double> metrics) throws IOException {
        var rss = new DoubleSummaryStatistics();
        for (var line : Files.readAllLines(file).subList(1, Files.readAllLines(file).size())) {
            var fields = line.split(",");
            if (fields.length == 3 && !fields[2].isBlank() && window.contains(Long.parseLong(fields[0]) * 1000)) {
                rss.accept(Double.parseDouble(fields[2]) / 1024);
            }
        }
        if (rss.getCount() > 0) {
            metrics.put("sampled.rss_avg_mb", rss.getAverage());
            metrics.put("sampled.rss_max_mb", rss.getMax());
        }
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    // ---------------------------------------------------------------------------------------------------------
    // report: medians per configuration, JDK 25 vs JDK 27 for each heap setting
    // ---------------------------------------------------------------------------------------------------------

    private static double median(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static String report(Map<String, Map<String, Map<String, Double>>> results) {
        var configs = new ArrayList<>(results.keySet());
        // the baseline goes first
        var baseline = "old";
        if (!configs.contains(baseline)) {
            baseline = configs.getFirst();
        }
        configs.remove(baseline);
        configs.addFirst(baseline);
        var out = new StringBuilder("\nMedians per configuration (runs: ");
        out.append(String.join(", ", configs.stream().map(c -> c + "=" + results.get(c).size()).toList())).append("). Δ is relative to ").append(baseline).append(".\n\n");
        out.append("| metric |");
        configs.forEach(c -> out.append(' ').append(c).append(" |"));
        configs.stream().skip(1).forEach(c -> out.append(" Δ ").append(c).append(" |"));
        out.append("\n|---|").append("---|".repeat(configs.size() * 2 - 1)).append('\n');
        var metrics = new TreeSet<String>();
        results.values().forEach(runs -> runs.values().forEach(m -> metrics.addAll(m.keySet())));
        for (var metric : metrics) {
            var medians = configs.stream()
                .map(c -> median(results.get(c).values().stream().map(m -> m.getOrDefault(metric, Double.NaN)).toList()))
                .toList();
            out.append("| ").append(metric).append(" |");
            medians.forEach(m -> out.append(' ').append("%.1f".formatted(m)).append(" |"));
            double base = medians.getFirst();
            for (var m : medians.subList(1, medians.size())) {
                var delta = "";
                if (base != 0 && !Double.isNaN(base) && !Double.isNaN(m)) {
                    delta = "%+.1f%%".formatted((m - base) / base * 100);
                }
                out.append(' ').append(delta).append(" |");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String format(List<Double> values) {
        return String.join(" ", values.stream().map(v -> "%.1f".formatted(v)).toList());
    }
}
