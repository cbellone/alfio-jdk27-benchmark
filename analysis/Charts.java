///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;

/**
 * Renders the SVG charts of the blog post from the output of Analyze.java (summary.csv, timeline.csv).
 * <p>
 * Usage: jbang analysis/Charts.java &lt;results dir&gt; &lt;output dir&gt;
 */
public class Charts {

    private static final List<String> CONFIGS = List.of("old", "aot", "aot-workload");
    private static final Map<String, String> LABELS = Map.of(
        "old", "JDK 25, G1, 512 MB",
        "aot", "JDK 27 + AOT cache",
        "aot-workload", "JDK 27 + trained AOT cache");
    private static final Map<String, String> COLORS = Map.of(
        "old", "#2a78d6",
        "aot", "#eb6834",
        "aot-workload", "#1baf7a");
    private static final String SURFACE = "#fcfcfb";
    private static final String TEXT = "#0b0b0b";
    private static final String TEXT_SECONDARY = "#52514e";
    private static final String GRID = "#e4e3df";
    private static final String FONT = "-apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif";
    private static final int WIDTH = 760;

    record Metric(String label, String key, double scale, String unit) {}

    record Group(String title, List<Metric> metrics) {}

    public static void main(String[] args) throws IOException {
        var results = Path.of(args[0]);
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var medians = medians(results.resolve("summary.csv"));

        Files.writeString(out.resolve("startup.svg"), bars(
            "Startup",
            "From docker run to the first successful health check. Median of 3 runs.",
            medians,
            List.of(new Metric("Container ready", "startup.ready_seconds", 1, "s"),
                new Metric("Spring context started", "startup.spring_seconds", 1, "s"))));

        Files.writeString(out.resolve("warmup.svg"), bars(
            "Warm-up: the first 5 minutes under load",
            "Ramp-up to 6.8 journeys/s, then 3 minutes at constant rate. Median of 3 runs.",
            medians,
            List.of(new Metric("Latency p95", "warmup.latency.p95_ms", 1, "ms"),
                new Metric("Latency p99, first 30 s", "warmup.first_30s_p99_ms", 1, "ms"),
                new Metric("C2 compilation time", "compilation_warmup.c2.total_ms", 0.001, "s"),
                new Metric("Process CPU", "warmup.cpu.process_pct_of_core", 1, "% of a CPU"))));

        Files.writeString(out.resolve("memory.svg"), bars(
            "Memory at steady state",
            "8 minutes at 6.8 journeys/s, after the warm-up. Median of 3 runs.",
            medians,
            List.of(new Metric("Resident memory (avg)", "jfr.rss_avg_mb", 1, "MB"),
                new Metric("Native memory committed (NMT)", "nmt.committed_avg_mb", 1, "MB"),
                new Metric("Heap committed", "heap.committed_max_mb", 1, "MB"),
                new Metric("Live heap after GC (avg)", "heap.after_gc_avg_mb", 1, "MB"))));

        Files.writeString(out.resolve("cpu.svg"), bars(
            "CPU usage by JVM component at steady state",
            "pidstat, per thread. 100% = one CPU; the container has two (the hardware threads of one core). Median of 3 runs.",
            medians,
            List.of(new Metric("Whole process", "cpu.process_pct_of_core", 1, "%"),
                new Metric("Jetty request threads", "cpu.app.jetty_pct_of_core", 1, "%"),
                new Metric("Scheduled jobs", "cpu.app.scheduled_pct_of_core", 1, "%"),
                new Metric("GC threads", "cpu.gc_pct_of_core", 1, "%"),
                new Metric("JIT compiler threads", "cpu.jit_pct_of_core", 1, "%"))));

        Files.writeString(out.resolve("gc.svg"), bars(
            "Garbage collection at steady state",
            "G1 with 512 MB vs ParallelGC with 384 MB. Median of 3 runs.",
            medians,
            List.of(new Metric("Total pause time", "gc.pause_total_ms", 0.001, "s"),
                new Metric("Longest pause", "gc.pause_max_ms", 1, "ms"),
                new Metric("Latency p99", "latency.p99_ms", 1, "ms"))));

        Files.writeString(out.resolve("warmup-timeline.svg"), timeline(results.resolve("timeline.csv")));
    }

    private static Map<String, Map<String, Double>> medians(Path summary) throws IOException {
        Map<String, Map<String, List<Double>>> values = new LinkedHashMap<>();
        for (var line : Files.readAllLines(summary).subList(1, Files.readAllLines(summary).size())) {
            var cells = line.split(",");
            if (cells.length < 4 || cells[3].isBlank()) {
                continue;
            }
            values.computeIfAbsent(cells[0], k -> new LinkedHashMap<>())
                .computeIfAbsent(cells[2], k -> new ArrayList<>())
                .add(Double.parseDouble(cells[3]));
        }
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        values.forEach((config, metrics) -> {
            Map<String, Double> m = new LinkedHashMap<>();
            metrics.forEach((metric, list) -> m.put(metric, median(list)));
            result.put(config, m);
        });
        return result;
    }

    private static double median(List<Double> values) {
        var sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = sorted.length;
        if (n % 2 == 1) {
            return sorted[n / 2];
        }
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    // one group of horizontal bars per metric, every bar is labeled with its value.
    // Metrics with the same unit share the scale, otherwise each group has its own
    private static String bars(String title, String subtitle, Map<String, Map<String, Double>> medians, List<Metric> metrics) {
        int labelWidth = 230;
        int barMax = WIDTH - labelWidth - 120;
        int barHeight = 16;
        int barGap = 4;
        int groupGap = 22;
        int top = 84;
        int groupHeight = CONFIGS.size() * (barHeight + barGap) - barGap;
        int height = top + metrics.size() * (groupHeight + groupGap) + 4;
        var svg = new StringBuilder(header(title, subtitle, height));
        legend(svg, 66);
        int y = top;
        for (var metric : metrics) {
            double max = 0;
            for (var other : metrics) {
                if (!other.unit().equals(metric.unit())) {
                    continue;
                }
                for (var config : CONFIGS) {
                    max = Math.max(max, medians.get(config).get(other.key()) * other.scale());
                }
            }
            svg.append(text(labelWidth - 12, y + groupHeight / 2.0 + 4, metric.label(), 13, TEXT, "end", 400));
            for (var config : CONFIGS) {
                double value = medians.get(config).get(metric.key()) * metric.scale();
                double width = Math.max(2, barMax * value / max);
                svg.append("<rect x='%d' y='%d' width='%.1f' height='%d' rx='3' fill='%s'><title>%s: %s</title></rect>"
                    .formatted(labelWidth, y, width, barHeight, COLORS.get(config), LABELS.get(config), format(value, metric.unit())));
                svg.append(text(labelWidth + width + 6, y + barHeight - 4, format(value, metric.unit()) + delta(medians, metric, config), 12, TEXT, "start", 400));
                y += barHeight + barGap;
            }
            y += groupGap - barGap;
        }
        return svg.append("</svg>\n").toString();
    }

    private static String delta(Map<String, Map<String, Double>> medians, Metric metric, String config) {
        if (config.equals("old")) {
            return "";
        }
        double base = medians.get("old").get(metric.key());
        double value = medians.get(config).get(metric.key());
        long percent = Math.round((value - base) * 100 / base);
        String sign = "";
        if (percent > 0) {
            sign = "+";
        }
        return "  (" + sign + percent + "%)";
    }

    // p95 latency in 10-second buckets during the ramp-up and the warm-up, median of the runs of each configuration
    private static String timeline(Path csv) throws IOException {
        int seconds = 300;
        Map<String, TreeMap<Integer, List<Double>>> series = new LinkedHashMap<>();
        for (var line : Files.readAllLines(csv).subList(1, Files.readAllLines(csv).size())) {
            var cells = line.split(",");
            int second = Integer.parseInt(cells[2]);
            if (second >= seconds) {
                continue;
            }
            series.computeIfAbsent(cells[0], k -> new TreeMap<>())
                .computeIfAbsent(second, k -> new ArrayList<>())
                .add(Double.parseDouble(cells[3]));
        }
        int left = 56;
        int right = 30;
        int top = 84;
        int plotHeight = 240;
        int height = top + plotHeight + 46;
        int plotWidth = WIDTH - left - right;
        double yMax = 120;
        DoubleUnaryOperator x = s -> left + plotWidth * s / seconds;
        DoubleUnaryOperator y = v -> top + plotHeight - plotHeight * Math.min(v, yMax) / yMax;

        var svg = new StringBuilder(header("Latency during ramp-up and warm-up",
            "p95 per 10-second window, median of 3 runs. The load ramps up from 10% to 100% in the first 2 minutes.", height));
        legend(svg, 66);
        for (int v = 0; v <= yMax; v += 20) {
            svg.append("<line x1='%d' x2='%d' y1='%.1f' y2='%.1f' stroke='%s' stroke-width='1'/>".formatted(left, left + plotWidth, y.applyAsDouble(v), y.applyAsDouble(v), GRID));
            svg.append(text(left - 8, y.applyAsDouble(v) + 4, v + " ms", 11, TEXT_SECONDARY, "end", 400));
        }
        for (int s = 0; s <= seconds; s += 60) {
            svg.append(text(x.applyAsDouble(s), top + plotHeight + 18, (s / 60) + " min", 11, TEXT_SECONDARY, "middle", 400));
        }
        double rampEnd = x.applyAsDouble(120);
        svg.append("<line x1='%.1f' x2='%.1f' y1='%d' y2='%d' stroke='%s' stroke-width='1' stroke-dasharray='4 4'/>".formatted(rampEnd, rampEnd, top, top + plotHeight, TEXT_SECONDARY));
        svg.append(text(rampEnd + 6, top + 12, "full load", 11, TEXT_SECONDARY, "start", 400));
        for (var config : CONFIGS) {
            var points = series.get(config).entrySet().stream()
                .map(e -> "%.1f,%.1f".formatted(x.applyAsDouble(e.getKey() + 5), y.applyAsDouble(median(e.getValue()))))
                .collect(Collectors.joining(" "));
            svg.append("<polyline points='%s' fill='none' stroke='%s' stroke-width='2' stroke-linejoin='round'><title>%s</title></polyline>"
                .formatted(points, COLORS.get(config), LABELS.get(config)));
        }
        svg.append(text(left + plotWidth / 2.0, height - 8, "time since the first request", 11, TEXT_SECONDARY, "middle", 400));
        return svg.append("</svg>\n").toString();
    }

    private static String header(String title, String subtitle, int height) {
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 %d %d' width='%d' height='%d' role='img' font-family='%s'><title>%s</title><rect width='%d' height='%d' rx='8' fill='%s'/>"
            .formatted(WIDTH, height, WIDTH, height, FONT, title, WIDTH, height, SURFACE)
            + text(24, 26, title, 15, TEXT, "start", 600)
            + text(24, 46, subtitle, 12, TEXT_SECONDARY, "start", 400);
    }

    private static void legend(StringBuilder svg, int y) {
        double x = 24;
        for (var config : CONFIGS) {
            svg.append("<rect x='%.1f' y='%d' width='10' height='10' rx='2' fill='%s'/>".formatted(x, y - 9, COLORS.get(config)));
            svg.append(text(x + 16, y, LABELS.get(config), 12, TEXT, "start", 400));
            x += 32 + LABELS.get(config).length() * 6.6;
        }
    }

    private static String text(double x, double y, String content, int size, String fill, String anchor, int weight) {
        return "<text x='%.1f' y='%.1f' font-size='%d' fill='%s' text-anchor='%s' font-weight='%d'>%s</text>"
            .formatted(x, y, size, fill, anchor, weight, content.replace("&", "&amp;").replace("<", "&lt;"));
    }

    private static String format(double value, String unit) {
        String number;
        if (value >= 100 || value == Math.rint(value) || unit.startsWith("%")) {
            number = String.format(Locale.ROOT, "%.0f", value);
        } else {
            number = String.format(Locale.ROOT, "%.1f", value);
        }
        if (unit.startsWith("%")) {
            return number + unit;
        }
        return number + " " + unit;
    }
}
