package dev.plca.report;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.LineIndex;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.Span;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleDescriptor;
import java.io.IOException;
import java.io.Writer;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Human-readable output: {@code path:line:col: severity RULE message}, the source line with the span underlined,
 * related locations, then a summary per rule. Input problems (syntax errors, …) come first, capped at
 * {@link #MAX_DIAGNOSTICS}.
 */
public final class ConsoleReport {

    static final int MAX_DIAGNOSTICS = 20;

    private final boolean color;

    public ConsoleReport(boolean color) {
        this.color = color;
    }

    public void write(AnalysisResult r, Writer out) throws IOException {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Diagnostic d : r.diagnostics()) {
            if (shown++ == MAX_DIAGNOSTICS) {
                sb.append("... ")
                        .append(r.diagnostics().size() - MAX_DIAGNOSTICS)
                        .append(" more input problems (use --format json for all)\n\n");
                break;
            }
            entry(sb, r, d.span(), d.severity(), d.code(), d.message());
            sb.append('\n');
        }
        for (Finding f : r.findings()) {
            entry(sb, r, f.span(), f.severity(), f.ruleId(), f.message());
            for (Diagnostic.Related rel : f.related()) {
                sb.append("      ")
                        .append(dim(r.sources().resolve(rel.span()).path() + ":" + lineCol(r, rel.span())))
                        .append(' ')
                        .append(rel.message())
                        .append('\n');
            }
            sb.append('\n');
        }
        summary(sb, r);
        out.write(sb.toString());
        out.flush();
    }

    private void entry(StringBuilder sb, AnalysisResult r, Span span, Severity s, String code, String message) {
        Location l = r.sources().resolve(span);
        sb.append(bold(l.path() + ":" + l.startLine() + ":" + l.startColumn() + ":"))
                .append(' ')
                .append(severity(s))
                .append(' ')
                .append(dim(code))
                .append(' ')
                .append(message)
                .append('\n');
        SourceFile file = r.sources().text(span.textId()).file();
        LineIndex li = file.lineIndex();
        if (l.startLine() > li.lineCount()) {
            return;
        }
        int start = li.lineStart(l.startLine());
        String text = file.text();
        int end = start;
        while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
            end++;
        }
        String line = text.substring(start, end).stripTrailing();
        String gutter = String.format(Locale.ROOT, "%5d | ", l.startLine());
        sb.append(dim(gutter)).append(line).append('\n');
        StringBuilder under = new StringBuilder();
        for (int i = 0; i < l.startColumn() - 1 && i < line.length(); i++) {
            under.append(line.charAt(i) == '\t' ? '\t' : ' ');
        }
        int width = l.endLine() == l.startLine()
                ? l.endColumn() - l.startColumn()
                : Math.max(1, line.length() - (l.startColumn() - 1));
        under.append("^".repeat(Math.max(1, width)));
        sb.append(dim("      | ")).append(colorFor(s, under.toString())).append('\n');
    }

    private static String lineCol(AnalysisResult r, Span span) {
        Location l = r.sources().resolve(span);
        return l.startLine() + ":" + l.startColumn();
    }

    private void summary(StringBuilder sb, AnalysisResult r) {
        Map<Severity, Integer> bySeverity = new EnumMap<>(Severity.class);
        Map<String, Integer> byRule = new LinkedHashMap<>();
        r.rules().forEach(d -> byRule.put(d.id(), 0));
        for (Finding f : r.findings()) {
            bySeverity.merge(f.severity(), 1, Integer::sum);
            byRule.merge(f.ruleId(), 1, Integer::sum);
        }
        AnalysisResult.Stats s = r.stats();
        sb.append(bold(r.findings().size() + (r.findings().size() == 1 ? " finding" : " findings")));
        if (!r.findings().isEmpty()) {
            StringBuilder parts = new StringBuilder();
            for (Map.Entry<Severity, Integer> e : bySeverity.entrySet()) {
                parts.append(parts.isEmpty() ? "" : ", ")
                        .append(e.getValue())
                        .append(' ')
                        .append(e.getKey().name().toLowerCase(Locale.ROOT))
                        .append(e.getValue() == 1 || e.getKey() == Severity.INFO ? "" : "s");
            }
            sb.append(" (").append(parts).append(')');
        }
        sb.append(String.format(Locale.ROOT, " in %d files, %,d lines, %d POUs%n", s.files(), s.lines(), s.pous()));
        for (RuleDescriptor d : r.rules()) {
            int n = byRule.getOrDefault(d.id(), 0);
            if (n > 0) {
                sb.append(String.format(Locale.ROOT, "  %-10s %-45s %5d%n", d.id(), d.title(), n));
            }
        }
        if (!r.diagnostics().isEmpty()) {
            sb.append(r.diagnostics().size())
                    .append(" input problem")
                    .append(r.diagnostics().size() == 1 ? "" : "s")
                    .append(" (syntax errors, unreadable files): results in those places may be incomplete\n");
        }
        if (!s.skippedFiles().isEmpty()) {
            sb.append(s.skippedFiles().size())
                    .append(" file")
                    .append(s.skippedFiles().size() == 1 ? "" : "s")
                    .append(" skipped (Siemens, Rockwell and PLCopen XML are not supported yet)\n");
        }
        sb.append(String.format(
                Locale.ROOT,
                "Analysed in %.1f s (parse %.1f s, model %.1f s, rules %.1f s)%n",
                (s.parseMillis() + s.modelMillis() + s.rulesMillis()) / 1000.0,
                s.parseMillis() / 1000.0,
                s.modelMillis() / 1000.0,
                s.rulesMillis() / 1000.0));
    }

    private String severity(Severity s) {
        return colorFor(s, s.name().toLowerCase(Locale.ROOT) + ":");
    }

    private String colorFor(Severity s, String text) {
        if (!color) {
            return text;
        }
        String code = switch (s) {
            case ERROR -> "31";
            case WARNING -> "33";
            case INFO, HINT -> "36";
        };
        return "\u001b[" + code + "m" + text + "\u001b[0m";
    }

    private String bold(String text) {
        return color ? "\u001b[1m" + text + "\u001b[0m" : text;
    }

    private String dim(String text) {
        return color ? "\u001b[2m" + text + "\u001b[0m" : text;
    }
}
