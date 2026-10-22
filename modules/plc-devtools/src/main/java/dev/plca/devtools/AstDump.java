package dev.plca.devtools;

import dev.plca.ast.AstPrinter;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.l5x.L5xFrontend;
import dev.plca.frontend.plcopen.PlcOpenFrontend;
import dev.plca.frontend.scl.SclFrontend;
import dev.plca.frontend.st.StFrontend;
import dev.plca.frontend.st.kit.Frontend;
import dev.plca.frontend.st.kit.Frontends;
import java.nio.file.Files;
import java.nio.file.Path;

/** Developer tool: prints the AST (with lowerings) and diagnostics of the given files, as the golden tests do. */
public final class AstDump {

    private AstDump() {}

    public static void main(String[] args) throws Exception {
        Frontends frontends = Frontends.of(StFrontend::new, SclFrontend::new, L5xFrontend::new, PlcOpenFrontend::new);
        for (String a : args) {
            Path p = Path.of(a);
            Frontend fe = frontends.forPath(p.toString());
            if (fe == null) {
                System.out.println("== " + a + ": no frontend reads this extension");
                continue;
            }
            SourceFile f = SourceFile.fromBytes(p.toString(), Files.readAllBytes(p));
            SourceRegistry reg = new SourceRegistry();
            DiagnosticCollector diags = new DiagnosticCollector();
            Frontend.Result r = fe.parse(f, reg, diags);
            System.out.println("== " + a);
            System.out.print(AstPrinter.print(r.unit(), reg::resolve));
            System.out.println("diagnostics:");
            for (Diagnostic d : diags.diagnostics()) {
                Location l = reg.resolve(d.span());
                System.out.println("  " + d.severity() + " " + d.code() + " @" + l.startLine() + ":" + l.startColumn()
                        + " " + d.message());
            }
        }
    }
}
