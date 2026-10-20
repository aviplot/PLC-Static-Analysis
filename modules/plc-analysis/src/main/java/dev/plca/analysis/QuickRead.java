package dev.plca.analysis;

import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.l5x.L5xFrontend;
import dev.plca.frontend.st.kit.Frontend;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * One file read on its own, for drawing it while the project still loads (M10): the full load of a 43 MB controller
 * export takes seconds, a quick read of it under half a second. L5X files are read by {@link L5xFrontend#forDrawing}
 * (rungs of one routine parsed), other files by their usual frontend. The unit is for drawings only, never analysis.
 */
public final class QuickRead {

    /** The unit (null if no frontend gave one) and the registry its spans resolve in. */
    public record Result(SourceUnit unit, SourceRegistry registry) {}

    private QuickRead() {}

    /**
     * Reads {@code file} ({@code displayPath} as the workspace names it); {@code routine}: the one ladder routine whose
     * rungs an L5X read parses (null: the first, which a viewer shows first after listing the routines).
     */
    public static Result read(Path file, String displayPath, String routine) throws IOException {
        Frontend frontend = displayPath.toLowerCase(Locale.ROOT).endsWith(".l5x")
                ? L5xFrontend.forDrawing(routine)
                : Analyzer.FRONTENDS.forPath(displayPath);
        SourceRegistry registry = new SourceRegistry();
        if (frontend == null) {
            return new Result(null, registry);
        }
        SourceFile source = SourceFile.fromBytes(displayPath, Files.readAllBytes(file));
        SourceUnit unit =
                frontend.parse(source, registry, new DiagnosticCollector()).unit();
        return new Result(unit, registry);
    }
}
