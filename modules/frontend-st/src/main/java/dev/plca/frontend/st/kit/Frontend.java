package dev.plca.frontend.st.kit;

import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import java.util.Locale;
import java.util.Set;

/**
 * A language frontend: one file in, one {@link SourceUnit} out, problems reported to the sink. Implementations never
 * throw for bad input (design rule 2) and are not thread-safe: use one instance per thread ({@link Frontends} does).
 */
public interface Frontend {

    /** Parse statistics of one file, for the corpus check and benchmarks. */
    record Stats(int texts, int sllFallbacks, int cleanFallbacks, int syntaxErrors) {
        public static final Stats NONE = new Stats(0, 0, 0, 0);

        public Stats plus(Stats o) {
            return new Stats(
                    texts + o.texts,
                    sllFallbacks + o.sllFallbacks,
                    cleanFallbacks + o.cleanFallbacks,
                    syntaxErrors + o.syntaxErrors);
        }
    }

    record Result(SourceUnit unit, Stats stats) {}

    /** Extensions this frontend reads (lower case, with dot). */
    Set<String> extensions();

    Result parse(SourceFile file, SourceRegistry registry, DiagnosticSink sink);

    /** The lower-case extension of {@code path} with its dot, or {@code ""}. */
    static String extension(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot).toLowerCase(Locale.ROOT) : "";
    }
}
