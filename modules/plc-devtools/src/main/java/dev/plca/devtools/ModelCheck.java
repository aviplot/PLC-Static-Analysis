package dev.plca.devtools;

import dev.plca.analysis.Snapshot;
import dev.plca.analysis.Workspace;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.semantic.ModelPrinter;
import dev.plca.semantic.SemanticBuilder;
import dev.plca.semantic.SemanticModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The incremental semantic model (M10) on real projects: random edits to random files (a space, a deleted line, a
 * duplicated line, one identifier replaced by another of the file), and after each the model of every group compared
 * with a full build of the same units ({@link ModelPrinter#printAll}). Reports mismatches (each is a bug), how many
 * builds were incremental and why the others were not, and build times. Run with {@code ./gradlew
 * :plc-devtools:modelCheck --args="[--edits=N] [--any-share] <dir> ..."}; exits 1 on a mismatch.
 */
public final class ModelCheck {

    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]{2,}\\b");

    private ModelCheck() {}

    public static void main(String[] args) {
        int edits = 30;
        List<Path> roots = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--edits=")) {
                edits = Integer.parseInt(a.substring("--edits=".length()));
            } else if (a.equals("--any-share")) {
                SemanticBuilder.setMaxRebuiltShare(1.0);
            } else {
                roots.add(Path.of(a));
            }
        }
        int mismatches = 0;
        for (Path root : roots) {
            mismatches += check(root, edits);
        }
        System.exit(mismatches == 0 ? 0 : 1);
    }

    private static int check(Path root, int edits) {
        Workspace w = new Workspace(root);
        w.loadAll(Runtime.getRuntime().availableProcessors());
        long f0 = System.nanoTime();
        w.snapshot();
        long fullMs = (System.nanoTime() - f0) / 1_000_000;
        Random random = new Random(root.toString().hashCode());
        List<String> paths = w.paths();
        int mismatches = 0;
        int incremental = 0;
        int builds = 0;
        Map<String, Integer> reasons = new TreeMap<>();
        List<Long> incrementalMs = new ArrayList<>();
        for (int k = 0; k < edits && !paths.isEmpty(); k++) {
            String path = paths.get(random.nextInt(paths.size()));
            String text = w.state(path).orElseThrow().file().text();
            w.update(path, edit(text, random));
            long t0 = System.nanoTime();
            Snapshot s = w.snapshot();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            for (Snapshot.Group g : s.groups()) {
                SemanticModel.BuildInfo info = g.model().buildInfo();
                if (g.units().stream().noneMatch(u -> u.path().equals(path))) {
                    continue; // a group the edit did not touch is reused as it was
                }
                builds++;
                if (info.incremental()) {
                    incremental++;
                    incrementalMs.add(ms);
                } else {
                    String reason =
                            info.reason() == null ? "full" : info.reason().replaceAll("'[^']*'", "'…'");
                    reasons.merge(reason, 1, Integer::sum);
                }
                SemanticModel full = SemanticBuilder.build(g.units(), new DiagnosticCollector());
                String a = ModelPrinter.printAll(g.model(), s.registry()::resolve);
                String b = ModelPrinter.printAll(full, s.registry()::resolve);
                if (!a.equals(b)) {
                    mismatches++;
                    System.out.println("MISMATCH after editing " + path + " (" + info + "):\n" + firstDifference(a, b));
                }
            }
        }
        incrementalMs.sort(null);
        System.out.printf(
                "%s: %d files, full build %d ms; %d edits, %d of %d builds incremental (median %s ms), %d mismatches;"
                        + " not incremental: %s%n",
                root,
                paths.size(),
                fullMs,
                edits,
                incremental,
                builds,
                incrementalMs.isEmpty() ? "-" : incrementalMs.get(incrementalMs.size() / 2),
                mismatches,
                reasons);
        return mismatches;
    }

    /** A random small edit; the result may not parse, as while typing. */
    static String edit(String text, Random random) {
        String[] lines = text.split("\n", -1);
        int line = random.nextInt(lines.length);
        List<String> out = new ArrayList<>(List.of(lines));
        switch (random.nextInt(4)) {
            case 0 -> out.set(line, lines[line] + " ");
            case 1 -> out.remove(line);
            case 2 -> out.add(line, lines[line]);
            default -> {
                List<String> names = new ArrayList<>();
                Matcher m = IDENTIFIER.matcher(text);
                while (m.find() && names.size() < 2000) {
                    names.add(m.group());
                }
                Matcher here = IDENTIFIER.matcher(lines[line]);
                if (!names.isEmpty() && here.find()) {
                    String other = names.get(random.nextInt(names.size()));
                    out.set(line, lines[line].substring(0, here.start()) + other + lines[line].substring(here.end()));
                }
            }
        }
        return String.join("\n", out);
    }

    private static String firstDifference(String a, String b) {
        String[] x = a.split("\n");
        String[] y = b.split("\n");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (!x[i].equals(y[i])) {
                return "  incremental: " + x[i] + "\n  full:        " + y[i];
            }
        }
        return "  lengths differ: " + x.length + " vs " + y.length + " lines";
    }
}
