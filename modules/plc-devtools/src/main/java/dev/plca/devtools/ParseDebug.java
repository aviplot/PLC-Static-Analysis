package dev.plca.devtools;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.l5x.L5xFrontend;
import dev.plca.frontend.scl.SclFrontend;
import dev.plca.frontend.st.StFrontend;
import dev.plca.frontend.st.kit.Frontend;
import dev.plca.frontend.st.kit.Frontends;
import java.nio.file.Files;
import java.nio.file.Path;

/** Developer tool: prints the diagnostics of the given files with the offending source line. */
public final class ParseDebug {

    private ParseDebug() {}

    public static void main(String[] args) throws Exception {
        Frontends frontends = Frontends.of(StFrontend::new, SclFrontend::new, L5xFrontend::new);
        java.util.List<String> files = new java.util.ArrayList<>();
        for (String a : args) {
            if (a.startsWith("@")) {
                files.addAll(Files.readAllLines(Path.of(a.substring(1))));
            } else {
                files.add(a);
            }
        }
        for (String a : files) {
            Path p = Path.of(a);
            SourceFile f = SourceFile.fromBytes(p.toString(), Files.readAllBytes(p));
            SourceRegistry reg = new SourceRegistry();
            DiagnosticCollector diags = new DiagnosticCollector();
            Frontend fe = frontends.forPath(p.toString());
            if (fe == null) {
                System.out.println("== " + a + ": no frontend reads this extension");
                continue;
            }
            fe.parse(f, reg, diags);
            System.out.println("== " + a + " (" + diags.size() + " diagnostics)");
            int n = 0;
            for (Diagnostic d : diags.diagnostics()) {
                if (n++ >= 3) {
                    break;
                }
                Location l = reg.resolve(d.span());
                String line =
                        f.text().lines().skip(l.startLine() - 1).findFirst().orElse("");
                System.out.println("  " + l.startLine() + ":" + l.startColumn() + " " + d.code() + " " + d.message());
                System.out.println("    | " + line.strip());
            }
        }
    }
}
