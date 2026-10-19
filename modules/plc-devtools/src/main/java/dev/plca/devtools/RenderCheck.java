package dev.plca.devtools;

import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.l5x.L5xFrontend;
import dev.plca.frontend.plcopen.PlcOpenFrontend;
import dev.plca.frontend.st.kit.Frontend;
import dev.plca.frontend.st.kit.Frontends;
import dev.plca.report.Diagrams;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Draws every rung and network of the Rockwell and PLCopen files of the local corpus (M9): no exception, every SVG
 * well-formed, and drawing times per part and per diagram (a routine is what the workbench draws at once). Run with
 * {@code ./gradlew :plc-devtools:renderCheck}; {@code --sample=<file.html>} also writes 200 random drawings to look at.
 * Exits 1 on a failure.
 */
public final class RenderCheck {

    private static final Frontends FRONTENDS = Frontends.of(L5xFrontend::new, PlcOpenFrontend::new);
    private static final XMLInputFactory XML = XMLInputFactory.newFactory();

    static {
        XML.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    private record FileResult(
            String path,
            int diagrams,
            int parts,
            long[] partNanos,
            long maxDiagramNanos,
            List<String> failures,
            List<String> samples) {}

    private RenderCheck() {}

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of("corpus");
        Path sample = null;
        for (String a : args) {
            if (a.startsWith("--sample=")) {
                sample = Path.of(a.substring("--sample=".length()));
            } else {
                corpus = Path.of(a);
            }
        }
        List<String[]> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(corpus.resolve("MANIFEST.csv"));
        for (String line : lines.subList(1, lines.size())) {
            String[] r = CorpusCheck.parseCsv(line);
            if (r.length > 3 && (r[3].equals("Rockwell") || r[3].equals("PlcOpen")) && seen.add(r[0] + "/" + r[2])) {
                rows.add(r);
            }
        }
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<FileResult>> futures = new ArrayList<>();
        AtomicLong done = new AtomicLong();
        Path root = corpus;
        boolean sampling = sample != null;
        for (String[] r : rows) {
            futures.add(pool.submit(() -> {
                FileResult f = renderFile(root, r, sampling);
                long n = done.incrementAndGet();
                if (n % 200 == 0) {
                    System.err.printf("%d/%d files%n", n, rows.size());
                }
                return f;
            }));
        }
        int files = 0;
        int diagrams = 0;
        int parts = 0;
        long maxDiagram = 0;
        String maxDiagramFile = null;
        List<Long> times = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        List<String> samples = new ArrayList<>();
        for (Future<FileResult> fu : futures) {
            FileResult f = fu.get();
            if (f == null) {
                continue;
            }
            files++;
            diagrams += f.diagrams();
            parts += f.parts();
            for (long t : f.partNanos()) {
                times.add(t);
            }
            if (f.maxDiagramNanos() > maxDiagram) {
                maxDiagram = f.maxDiagramNanos();
                maxDiagramFile = f.path();
            }
            for (String e : f.failures()) {
                failures.putIfAbsent(f.path() + ": " + e, e);
            }
            samples.addAll(f.samples());
        }
        pool.shutdown();
        long[] sorted = times.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.printf(
                "%d files, %d diagrams, %d parts; per part p50 %.2f ms, p99 %.2f ms, max %.1f ms; slowest diagram"
                        + " %.0f ms (%s); %d failures%n",
                files,
                diagrams,
                parts,
                pct(sorted, 0.5),
                pct(sorted, 0.99),
                sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1e6,
                maxDiagram / 1e6,
                maxDiagramFile,
                failures.size());
        failures.keySet().stream().limit(20).forEach(f -> System.out.println("  " + f));
        if (sample != null) {
            Collections.shuffle(samples, new Random(1));
            StringBuilder h = new StringBuilder("<!DOCTYPE html><meta charset=utf-8><body>");
            samples.stream().limit(200).forEach(h::append);
            Files.writeString(sample, h);
            System.out.println("samples: " + sample.toAbsolutePath());
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static double pct(long[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, sorted.length * p)] / 1e6;
    }

    private static FileResult renderFile(Path corpus, String[] r, boolean sampling) {
        String rel = r[0].replace("https://github.com/", "github/").replace("https://gitlab.com/", "gitlab/");
        Path file = corpus.resolve("external").resolve(rel).resolve(r[2]);
        Frontend frontend = FRONTENDS.forPath(r[2]);
        if (frontend == null || !Files.isRegularFile(file)) {
            return null;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
        SourceRegistry registry = new SourceRegistry();
        SourceUnit unit = frontend.parse(SourceFile.fromBytes(r[2], data), registry, new DiagnosticCollector())
                .unit();
        List<String> failures = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        int count = 0;
        long maxDiagram = 0;
        List<Diagrams.Diagram> ds = Diagrams.of(unit);
        Random random = new Random(r[2].hashCode());
        for (Diagrams.Diagram d : ds) {
            long t0 = System.nanoTime();
            List<Diagrams.Part> parts;
            try {
                parts = Diagrams.render(d, registry);
            } catch (RuntimeException | StackOverflowError e) {
                failures.add(d.name() + ": " + e);
                continue;
            }
            long t = System.nanoTime() - t0;
            maxDiagram = Math.max(maxDiagram, t);
            for (Diagrams.Part p : parts) {
                times.add(t / Math.max(1, parts.size()));
                count++;
                String error = wellFormed(p.svg());
                if (error != null) {
                    failures.add(d.name() + " #" + p.number() + ": " + error);
                }
                if (sampling && random.nextInt(50) == 0) {
                    samples.add("<h4>" + r[0] + " " + r[2] + " " + d.name() + " #" + p.number() + "</h4><div>" + p.svg()
                            + "</div>");
                }
            }
        }
        return new FileResult(
                r[0] + "/" + r[2],
                ds.size(),
                count,
                times.stream().mapToLong(Long::longValue).toArray(),
                maxDiagram,
                failures,
                samples);
    }

    private static String wellFormed(String svg) {
        try {
            XMLStreamReader x = XML.createXMLStreamReader(new StringReader(svg));
            while (x.hasNext()) {
                x.next();
            }
            return null;
        } catch (XMLStreamException e) {
            return e.getMessage();
        }
    }
}
