///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;

/**
 * Renders the SVG charts of the blog post from the results of scenario 1 (the 512 MB container):
 * <ul>
 *     <li>warmup-timeline.svg: p95 latency in 30-second windows during the ramp-up and the warm-up,
 *     from the latency logs of the runs (results/load-&lt;config&gt;-&lt;n&gt;/load-latencies.csv)</li>
 *     <li>cpu.svg: CPU usage per JVM component at steady state, from results/load-summary.csv (Analyze.java)</li>
 * </ul>
 * Usage: jbang analysis/Charts.java &lt;results dir&gt; &lt;output dir&gt;
 */
public class Charts {

    private static final List<String> TIMELINE_CONFIGS = List.of("jdk25", "jdk27", "jdk27-aot-workload");
    private static final List<String> CPU_CONFIGS = List.of("jdk25", "jdk25-coh", "jdk27", "jdk27-aot-workload", "jdk27-parallel-aot");
    private static final Map<String, String> LABELS = Map.of(
        "jdk25", "jdk25 (AppCDS)",
        "jdk25-coh", "jdk25-coh",
        "jdk27", "jdk27 (no archive)",
        "jdk27-aot-workload", "jdk27-aot-workload",
        "jdk27-parallel-aot", "jdk27-parallel-aot");
    private static final Map<String, String> COLORS = Map.of(
        "jdk25", "#2a78d6",
        "jdk27", "#eb6834",
        "jdk27-aot-workload", "#1baf7a");
    // CPU components: metric key in load-summary.csv, label, color. "other" is the rest of the process
    private static final List<List<String>> COMPONENTS = List.of(
        List.of("cpu.app.jetty_pct_of_core", "Jetty request threads", "#2a78d6"),
        List.of("cpu.app.scheduled_pct_of_core", "Scheduled jobs", "#1baf7a"),
        List.of("cpu.gc_pct_of_core", "GC threads", "#eb6834"),
        List.of("cpu.jit_pct_of_core", "JIT compiler threads", "#8e6bd1"),
        List.of("other", "Other", "#b8b6b0"));
    private static final String SURFACE = "#fcfcfb";
    private static final String TEXT = "#0b0b0b";
    private static final String TEXT_SECONDARY = "#52514e";
    private static final String GRID = "#e4e3df";
    private static final String FONT = "-apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif";
    private static final String MONO = "SFMono-Regular, Menlo, Consolas, monospace";
    private static final int WIDTH = 760;
    // the load ramps up for 2 minutes, then runs at constant rate for 3 minutes before the measurement
    private static final int RAMP_SECONDS = 120;
    private static final int WARMUP_SECONDS = 300;
    private static final int WINDOW_SECONDS = 30;

    public static void main(String[] args) throws IOException {
        var results = Path.of(args[0]);
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        Files.writeString(out.resolve("warmup-timeline.svg"), timeline(results));
        Files.writeString(out.resolve("cpu.svg"), cpu(medians(results.resolve("load-summary.csv"))));
    }

    private static Map<String, Map<String, Double>> medians(Path summary) throws IOException {
        Map<String, Map<String, List<Double>>> values = new LinkedHashMap<>();
        var lines = Files.readAllLines(summary);
        for (var line : lines.subList(1, lines.size())) {
            var cells = line.split(",");
            if (cells.length < 4 || cells[3].isBlank() || cells[3].equals("NaN")) {
                continue;
            }
            values.computeIfAbsent(cells[0], _ -> new LinkedHashMap<>())
                .computeIfAbsent(cells[2], _ -> new ArrayList<>())
                .add(Double.parseDouble(cells[3]));
        }
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        values.forEach((config, metrics) -> {
            Map<String, Double> m = new LinkedHashMap<>();
            metrics.forEach((metric, list) -> m.put(metric, percentile(list, 50)));
            result.put(config, m);
        });
        return result;
    }

    private static double percentile(List<Double> values, double p) {
        var sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = sorted.length;
        if (p == 50 && n % 2 == 0) {
            return (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
        }
        int index = (int) Math.ceil(p / 100 * n) - 1;
        return sorted[Math.max(0, Math.min(n - 1, index))];
    }

    // one stacked bar per configuration: how the CPU of the JVM splits between its components
    private static String cpu(Map<String, Map<String, Double>> medians) {
        int labelWidth = 170;
        int barMax = WIDTH - labelWidth - 70;
        int barHeight = 22;
        int barGap = 12;
        int top = 100;
        double scale = 130;
        int height = top + CPU_CONFIGS.size() * (barHeight + barGap) + 30;
        var svg = new StringBuilder(header("Where the CPU goes at steady state",
            "512 MB container, 5.6 journeys/s, median of 2 runs. 100% = one CPU; the container has two.", height));
        double x = 24;
        for (var component : COMPONENTS) {
            svg.append("<rect x='%.1f' y='%d' width='10' height='10' rx='2' fill='%s'/>".formatted(x, 66 - 9, component.get(2)));
            svg.append(text(x + 16, 66, component.get(1), 12, TEXT, "start", 400, FONT));
            x += 32 + component.get(1).length() * 6.4;
        }
        DoubleUnaryOperator width = v -> barMax * v / scale;
        for (int v = 0; v <= scale; v += 20) {
            double gx = labelWidth + width.applyAsDouble(v);
            svg.append("<line x1='%.1f' x2='%.1f' y1='%d' y2='%d' stroke='%s' stroke-width='1'/>".formatted(gx, gx, top - 8, height - 26, GRID));
            svg.append(text(gx, height - 10, v + "%", 11, TEXT_SECONDARY, "middle", 400, FONT));
        }
        int y = top;
        for (var config : CPU_CONFIGS) {
            var metrics = medians.get(config);
            double process = metrics.get("cpu.process_pct_of_core");
            svg.append(text(labelWidth - 12, y + barHeight / 2.0 + 4, config, 12, TEXT, "end", 400, MONO));
            double offset = 0;
            for (var component : COMPONENTS) {
                double value;
                if (component.get(0).equals("other")) {
                    value = Math.max(0, process - offset);
                } else {
                    value = metrics.get(component.get(0));
                }
                svg.append("<rect x='%.1f' y='%d' width='%.1f' height='%d' fill='%s'><title>%s, %s: %s</title></rect>"
                    .formatted(labelWidth + width.applyAsDouble(offset), y, width.applyAsDouble(value), barHeight, component.get(2),
                        config, component.get(1), format(value, "%")));
                offset += value;
            }
            svg.append(text(labelWidth + width.applyAsDouble(process) + 6, y + barHeight / 2.0 + 4, format(process, "%"), 12, TEXT, "start", 600, FONT));
            y += barHeight + barGap;
        }
        return svg.append("</svg>\n").toString();
    }

    // p95 latency in 30-second windows during the ramp-up and the warm-up, all the requests of the runs of a configuration
    private static String timeline(Path results) throws IOException {
        Map<String, TreeMap<Integer, List<Double>>> series = new LinkedHashMap<>();
        for (var config : TIMELINE_CONFIGS) {
            var windows = new TreeMap<Integer, List<Double>>();
            List<Path> runs;
            try (var list = Files.list(results)) {
                runs = list.filter(p -> p.getFileName().toString().matches("load-" + config + "-\\d+"))
                    .filter(p -> Files.exists(p.resolve("load-latencies.csv")))
                    .toList();
            }
            for (var run : runs) {
                long start = Long.parseLong(Files.readString(run.resolve("load-start-millis")).trim());
                var lines = Files.readAllLines(run.resolve("load-latencies.csv"));
                for (var line : lines.subList(1, lines.size())) {
                    var cells = line.split(",");
                    long second = (Long.parseLong(cells[0]) - start) / 1000;
                    if (second < 0 || second >= WARMUP_SECONDS || !cells[3].equals("OK")) {
                        continue;
                    }
                    windows.computeIfAbsent((int) (second / WINDOW_SECONDS), _ -> new ArrayList<>()).add(Double.parseDouble(cells[2]));
                }
            }
            series.put(config, windows);
        }
        int left = 56;
        int right = 30;
        int top = 84;
        int plotHeight = 240;
        int height = top + plotHeight + 46;
        int plotWidth = WIDTH - left - right;
        double yMax = 120;
        DoubleUnaryOperator x = s -> left + plotWidth * s / WARMUP_SECONDS;
        DoubleUnaryOperator y = v -> top + plotHeight - plotHeight * Math.min(v, yMax) / yMax;

        var svg = new StringBuilder(header("Latency during ramp-up and warm-up",
            "p95 per 30-second window, 512 MB container, 2 runs per configuration. The load ramps up from 10% to 100% in 2 minutes.", height));
        double lx = 24;
        for (var config : TIMELINE_CONFIGS) {
            svg.append("<rect x='%.1f' y='%d' width='10' height='10' rx='2' fill='%s'/>".formatted(lx, 66 - 9, COLORS.get(config)));
            svg.append(text(lx + 16, 66, LABELS.get(config), 12, TEXT, "start", 400, MONO));
            lx += 40 + LABELS.get(config).length() * 7.2;
        }
        for (int v = 0; v <= yMax; v += 20) {
            svg.append("<line x1='%d' x2='%d' y1='%.1f' y2='%.1f' stroke='%s' stroke-width='1'/>".formatted(left, left + plotWidth, y.applyAsDouble(v), y.applyAsDouble(v), GRID));
            svg.append(text(left - 8, y.applyAsDouble(v) + 4, v + " ms", 11, TEXT_SECONDARY, "end", 400, FONT));
        }
        for (int s = 0; s <= WARMUP_SECONDS; s += 60) {
            svg.append(text(x.applyAsDouble(s), top + plotHeight + 18, (s / 60) + " min", 11, TEXT_SECONDARY, "middle", 400, FONT));
        }
        double rampEnd = x.applyAsDouble(RAMP_SECONDS);
        svg.append("<line x1='%.1f' x2='%.1f' y1='%d' y2='%d' stroke='%s' stroke-width='1' stroke-dasharray='4 4'/>".formatted(rampEnd, rampEnd, top, top + plotHeight, TEXT_SECONDARY));
        svg.append(text(rampEnd + 6, top + 12, "full load", 11, TEXT_SECONDARY, "start", 400, FONT));
        for (var config : TIMELINE_CONFIGS) {
            var points = series.get(config).entrySet().stream()
                .map(e -> "%.1f,%.1f".formatted(x.applyAsDouble(e.getKey() * WINDOW_SECONDS + WINDOW_SECONDS / 2.0),
                    y.applyAsDouble(percentile(e.getValue(), 95))))
                .collect(Collectors.joining(" "));
            svg.append("<polyline points='%s' fill='none' stroke='%s' stroke-width='2.5' stroke-linejoin='round'><title>%s</title></polyline>"
                .formatted(points, COLORS.get(config), LABELS.get(config)));
        }
        svg.append(text(left + plotWidth / 2.0, height - 8, "time since the first request", 11, TEXT_SECONDARY, "middle", 400, FONT));
        return svg.append("</svg>\n").toString();
    }

    private static String header(String title, String subtitle, int height) {
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 %d %d' width='%d' height='%d' role='img' font-family='%s'><title>%s</title><rect width='%d' height='%d' rx='8' fill='%s'/>"
            .formatted(WIDTH, height, WIDTH, height, FONT, title, WIDTH, height, SURFACE)
            + text(24, 26, title, 15, TEXT, "start", 600, FONT)
            + text(24, 46, subtitle, 12, TEXT_SECONDARY, "start", 400, FONT);
    }

    private static String text(double x, double y, String content, int size, String fill, String anchor, int weight, String font) {
        return "<text x='%.1f' y='%.1f' font-size='%d' fill='%s' text-anchor='%s' font-weight='%d' font-family='%s'>%s</text>"
            .formatted(x, y, size, fill, anchor, weight, font, content.replace("&", "&amp;").replace("<", "&lt;"));
    }

    private static String format(double value, String unit) {
        String number = String.format(Locale.ROOT, "%.0f", value);
        if (unit.startsWith("%")) {
            return number + unit;
        }
        return number + " " + unit;
    }
}
