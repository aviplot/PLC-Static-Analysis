package dev.plca.report;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleDescriptor;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A single self-contained HTML file: inline CSS and script, no external resources and no telemetry (customer code is
 * sensitive). A summary, the rules with their counts and embedded documentation, and the findings grouped by file with
 * a few lines of source around each — for XML exports the decoded rung or ST text, numbered with the lines of the file.
 * Filters by severity, rule, language and text run in the browser; without script everything is shown.
 */
public final class HtmlReport {

    /** Source lines shown before and after a finding's first line. */
    static final int CONTEXT = 2;

    private HtmlReport() {}

    public static void write(AnalysisResult r, Writer out) throws IOException {
        write(r, out, ReportDrawings.NONE);
    }

    /** Most drawings embedded in one report; after them, findings show their source text only. */
    static final int MAX_DRAWINGS = 2_000;

    static final long MAX_DRAWING_CHARS = 20L << 20;

    /** The drawings a report embeds (M10): each part once, under its first finding, with all its findings outlined. */
    private static final class Drawn {
        final ReportDrawings drawings;
        final Map<Finding, dev.plca.ast.Statement> partOf = new java.util.IdentityHashMap<>();
        final Map<dev.plca.ast.Statement, List<ReportDrawings.Mark>> marks = new java.util.IdentityHashMap<>();
        final Map<dev.plca.ast.Statement, String> anchors = new java.util.IdentityHashMap<>();
        int count;
        long chars;
        boolean capped;

        Drawn(ReportDrawings drawings, List<Finding> findings) {
            this.drawings = drawings;
            if (drawings.isEmpty()) {
                return;
            }
            for (Finding f : findings) {
                dev.plca.ast.Statement part = drawings.partOf(f.span());
                if (part != null) {
                    partOf.put(f, part);
                    marks.computeIfAbsent(part, k -> new ArrayList<>())
                            .add(new ReportDrawings.Mark(f.span(), f.severity()));
                }
            }
        }
    }

    /** As {@link #write(AnalysisResult, Writer)}, with the drawing of the rung, network or chart of each finding. */
    public static void write(AnalysisResult r, Writer out, ReportDrawings drawings) throws IOException {
        Drawn drawn = new Drawn(drawings, r.findings());
        StringBuilder h = new StringBuilder(64 * 1024);
        Map<String, RuleDescriptor> rules = new LinkedHashMap<>();
        r.rules().forEach(d -> rules.put(d.id(), d));

        h.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta name=\"generator\" content=\"")
                .append(Html.escape(ToolInfo.NAME + " " + ToolInfo.VERSION))
                .append("\">\n<title>plca report</title>\n<style>\n")
                .append(CSS)
                .append("</style>\n</head>\n<body>\n<main>\n");

        header(h, r);
        summary(h, r);
        filters(h, r);
        findings(h, r, rules, drawn);
        if (drawn.capped) {
            h.append("<p class=\"muted\">Drawings stop after ")
                    .append(drawn.count)
                    .append(": the rest of the findings show their source text only.</p>\n");
        }
        diagnostics(h, r);
        ruleHelp(h, r, rules);

        h.append("</main>\n<script>\n").append(SCRIPT).append("</script>\n</body>\n</html>\n");
        out.write(h.toString());
        out.flush();
    }

    private static void header(StringBuilder h, AnalysisResult r) {
        h.append("<header>\n<h1>PLC analysis report</h1>\n<p class=\"muted\">")
                .append(Html.escape(r.rootUri()))
                .append(" &middot; ")
                .append(Html.escape(ToolInfo.NAME + " " + ToolInfo.VERSION))
                .append("</p>\n</header>\n");
    }

    private static void summary(StringBuilder h, AnalysisResult r) {
        Map<Severity, Integer> bySeverity = new EnumMap<>(Severity.class);
        r.findings().forEach(f -> bySeverity.merge(f.severity(), 1, Integer::sum));
        AnalysisResult.Stats s = r.stats();
        h.append("<section class=\"cards\">\n");
        card(h, "findings", String.valueOf(r.findings().size()), "Findings", "");
        for (Severity v : List.of(Severity.ERROR, Severity.WARNING, Severity.INFO)) {
            card(h, "sev-" + key(v), String.valueOf(bySeverity.getOrDefault(v, 0)), label(v), "");
        }
        int inline = r.suppressed(AnalysisResult.SuppressionKind.INLINE).size();
        int config = r.suppressed(AnalysisResult.SuppressionKind.CONFIG).size();
        card(
                h,
                "",
                String.valueOf(inline + config),
                "Suppressed",
                inline + " in the source, " + config + " by plca.yaml");
        if (r.baseline() != null) {
            card(
                    h,
                    "",
                    String.valueOf(r.baseline().unchanged()),
                    "In the baseline",
                    r.baseline().fixed() + " fixed since");
        }
        h.append("</section>\n<p class=\"muted\">")
                .append(String.format(
                        Locale.ROOT,
                        "%d files, %,d lines, %d POUs &middot; names resolved %.1f%% &middot; analysed in %.1f s",
                        s.files(),
                        s.lines(),
                        s.pous(),
                        s.resolutionRate() * 100,
                        (s.parseMillis() + s.modelMillis() + s.rulesMillis()) / 1000.0));
        if (!r.diagnostics().isEmpty()) {
            h.append(" &middot; <a href=\"#problems\">")
                    .append(r.diagnostics().size())
                    .append(" input problem")
                    .append(r.diagnostics().size() == 1 ? "" : "s")
                    .append("</a>");
        }
        if (!s.skippedFiles().isEmpty()) {
            h.append(" &middot; ").append(s.skippedFiles().size()).append(" files skipped (L5K)");
        }
        h.append("</p>\n");
    }

    private static void card(StringBuilder h, String cls, String value, String label, String note) {
        h.append("<div class=\"card ")
                .append(cls)
                .append("\"><div class=\"value\">")
                .append(Html.escape(value))
                .append("</div><div class=\"label\">")
                .append(Html.escape(label))
                .append("</div>");
        if (!note.isEmpty()) {
            h.append("<div class=\"note\">").append(Html.escape(note)).append("</div>");
        }
        h.append("</div>\n");
    }

    private static void filters(StringBuilder h, AnalysisResult r) {
        Map<String, Integer> byRule = new LinkedHashMap<>();
        r.rules().forEach(d -> byRule.put(d.id(), 0));
        Map<String, Integer> byLanguage = new LinkedHashMap<>();
        for (Finding f : r.findings()) {
            byRule.merge(f.ruleId(), 1, Integer::sum);
            byLanguage.merge(language(r.sources().resolve(f.span()).path()), 1, Integer::sum);
        }
        h.append("<section class=\"filters\" id=\"filters\" hidden>\n<label>Severity <select id=\"f-sev\">")
                .append("<option value=\"\">all</option>");
        for (Severity v : List.of(Severity.ERROR, Severity.WARNING, Severity.INFO)) {
            h.append("<option value=\"")
                    .append(key(v))
                    .append("\">")
                    .append(key(v))
                    .append(" and above</option>");
        }
        h.append("</select></label>\n<label>Rule <select id=\"f-rule\"><option value=\"\">all</option>");
        Map<String, RuleDescriptor> d = new LinkedHashMap<>();
        r.rules().forEach(x -> d.put(x.id(), x));
        byRule.forEach((id, n) -> {
            if (n > 0) {
                h.append("<option value=\"")
                        .append(Html.escape(id))
                        .append("\">")
                        .append(Html.escape(id + " " + d.get(id).title() + " (" + n + ")"))
                        .append("</option>");
            }
        });
        h.append("</select></label>\n<label>Language <select id=\"f-lang\"><option value=\"\">all</option>");
        byLanguage.forEach(
                (lang, n) -> h.append("<option>").append(Html.escape(lang)).append("</option>"));
        h.append(
                        "</select></label>\n<label>Text <input id=\"f-text\" type=\"search\" placeholder=\"message, POU, path\">")
                .append("</label>\n<span id=\"f-count\" class=\"muted\"></span>\n</section>\n");
    }

    private static void findings(StringBuilder h, AnalysisResult r, Map<String, RuleDescriptor> rules, Drawn drawn) {
        h.append("<section id=\"findings\">\n<h2>Findings</h2>\n");
        if (r.findings().isEmpty()) {
            h.append("<p>No findings.</p>\n</section>\n");
            return;
        }
        Map<String, List<Finding>> byFile = new LinkedHashMap<>();
        for (Finding f : r.findings()) {
            byFile.computeIfAbsent(r.sources().resolve(f.span()).path(), k -> new ArrayList<>())
                    .add(f);
        }
        byFile.forEach((path, list) -> {
            h.append("<details class=\"file\" open>\n<summary><span class=\"path\">")
                    .append(Html.escape(path))
                    .append("</span> <span class=\"muted\">")
                    .append(list.size())
                    .append(list.size() == 1 ? " finding" : " findings")
                    .append("</span></summary>\n");
            for (Finding f : list) {
                finding(h, r, f, rules.get(f.ruleId()), path, drawn);
            }
            h.append("</details>\n");
        });
        h.append("</section>\n");
    }

    private static void finding(
            StringBuilder h, AnalysisResult r, Finding f, RuleDescriptor d, String path, Drawn drawn) {
        Location l = r.sources().resolve(f.span());
        h.append("<article class=\"finding\" data-sev=\"")
                .append(rank(f.severity()))
                .append("\" data-rule=\"")
                .append(Html.escape(f.ruleId()))
                .append("\" data-lang=\"")
                .append(Html.escape(language(path)))
                .append("\" data-text=\"")
                .append(Html.escape(
                        (f.message() + " " + (f.pou() == null ? "" : f.pou()) + " " + path).toLowerCase(Locale.ROOT)))
                .append("\">\n<div class=\"head\"><span class=\"badge sev-")
                .append(key(f.severity()))
                .append("\">")
                .append(key(f.severity()))
                .append("</span> <a class=\"rule\" href=\"#rule-")
                .append(Html.escape(f.ruleId()))
                .append("\">")
                .append(Html.escape(f.ruleId()))
                .append("</a> <span class=\"loc\">")
                .append(l.startLine())
                .append(':')
                .append(l.startColumn())
                .append("</span>");
        if (f.place() != null) {
            h.append(" <span class=\"place\">").append(Html.escape(f.place())).append("</span>");
        } else if (f.pou() != null) {
            h.append(" <span class=\"place\">").append(Html.escape(f.pou())).append("</span>");
        }
        h.append("</div>\n<p class=\"msg\">").append(Html.escape(f.message())).append("</p>\n");
        if (d != null) {
            h.append("<p class=\"title muted\">").append(Html.escape(d.title())).append("</p>\n");
        }
        excerpt(h, r, f.span());
        drawing(h, f, drawn);
        if (!f.related().isEmpty()) {
            h.append("<ul class=\"related\">\n");
            for (Diagnostic.Related rel : f.related()) {
                Location rl = r.sources().resolve(rel.span());
                h.append("<li><span class=\"loc\">")
                        .append(Html.escape(rl.path().equals(path) ? "" : rl.path() + ":"))
                        .append(rl.startLine())
                        .append(':')
                        .append(rl.startColumn())
                        .append("</span> ")
                        .append(Html.escape(rel.message()))
                        .append("</li>\n");
            }
            h.append("</ul>\n");
        }
        h.append("</article>\n");
    }

    /** The finding's rung, network or chart: drawn under the part's first finding, a link to it under the others. */
    private static void drawing(StringBuilder h, Finding f, Drawn drawn) {
        dev.plca.ast.Statement part = drawn.partOf.get(f);
        if (part == null) {
            return;
        }
        String anchor = drawn.anchors.get(part);
        if (anchor != null) {
            h.append("<p class=\"drawn-above\"><a href=\"#")
                    .append(anchor)
                    .append("\">Drawing: ")
                    .append(Html.escape(drawn.drawings.label(part)))
                    .append("</a></p>\n");
            return;
        }
        if (drawn.count >= MAX_DRAWINGS || drawn.chars >= MAX_DRAWING_CHARS) {
            drawn.capped = true;
            return;
        }
        String svg = drawn.drawings.svg(part, drawn.marks.get(part));
        anchor = "d" + drawn.count++;
        drawn.chars += svg.length();
        drawn.anchors.put(part, anchor);
        h.append("<figure class=\"drawing\" id=\"")
                .append(anchor)
                .append("\"><figcaption class=\"muted\">")
                .append(Html.escape(drawn.drawings.label(part)))
                .append("</figcaption>")
                .append(svg)
                .append("</figure>\n");
    }

    /**
     * The finding's first line with {@link #CONTEXT} lines around it, from the text the span lives in (the decoded
     * fragment for XML exports). Lines are numbered as the physical file numbers them.
     */
    private static void excerpt(StringBuilder h, AnalysisResult r, Span span) {
        SourceText t = r.sources().text(span.textId());
        String text = t.text();
        int start = Math.min(span.start(), text.length());
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        List<int[]> lines = new ArrayList<>(); // {start, end}
        int s = lineStart;
        for (int k = 0; k < CONTEXT && s > 0; k++) {
            s = text.lastIndexOf('\n', s - 2) + 1;
        }
        for (int k = 0; k < 2 * CONTEXT + 1 && s <= text.length(); k++) {
            int e = text.indexOf('\n', s);
            if (e < 0) {
                e = text.length();
            }
            lines.add(new int[] {s, e});
            if (e >= text.length()) {
                break;
            }
            s = e + 1;
        }
        h.append("<pre class=\"src\">");
        for (int[] line : lines) {
            String content = text.substring(line[0], line[1]).replace("\r", "").replace("\t", "    ");
            boolean hit = line[0] <= start && start <= line[1];
            int number = r.sources()
                    .resolve(Span.of(span.textId(), line[0], line[0]))
                    .startLine();
            h.append("<span class=\"ln")
                    .append(hit ? " hit" : "")
                    .append("\"><span class=\"no\">")
                    .append(number)
                    .append("</span>")
                    .append(Html.escape(content))
                    .append("</span>\n");
        }
        h.append("</pre>\n");
    }

    private static void diagnostics(StringBuilder h, AnalysisResult r) {
        if (r.diagnostics().isEmpty()) {
            return;
        }
        h.append("<section id=\"problems\">\n<details>\n<summary><h2>Input problems (")
                .append(r.diagnostics().size())
                .append(")</h2></summary>\n<p class=\"muted\">Syntax errors, unreadable files and rule failures:")
                .append(" results in those places may be incomplete.</p>\n<ul class=\"problems\">\n");
        for (Diagnostic d : r.diagnostics()) {
            Location l = r.sources().resolve(d.span());
            h.append("<li><span class=\"badge sev-")
                    .append(key(d.severity()))
                    .append("\">")
                    .append(key(d.severity()))
                    .append("</span> <span class=\"path\">")
                    .append(Html.escape(l.path() + ":" + l.startLine() + ":" + l.startColumn()))
                    .append("</span> <span class=\"muted\">")
                    .append(Html.escape(d.code()))
                    .append("</span> ")
                    .append(Html.escape(d.message()))
                    .append("</li>\n");
        }
        h.append("</ul>\n</details>\n</section>\n");
    }

    private static void ruleHelp(StringBuilder h, AnalysisResult r, Map<String, RuleDescriptor> rules) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        r.findings().forEach(f -> counts.merge(f.ruleId(), 1, Integer::sum));
        h.append("<section id=\"rules\">\n<h2>Rules</h2>\n<table>\n<thead><tr><th>Rule</th><th>Title</th>")
                .append("<th>Severity</th><th>PLCopen</th><th>Findings</th></tr></thead>\n<tbody>\n");
        for (RuleDescriptor d : rules.values()) {
            h.append("<tr><td><a href=\"#rule-")
                    .append(Html.escape(d.id()))
                    .append("\">")
                    .append(Html.escape(d.id()))
                    .append("</a></td><td>")
                    .append(Html.escape(d.title()))
                    .append("</td><td>")
                    .append(key(d.severity()))
                    .append("</td><td>")
                    .append(d.plcopen() == null ? "" : Html.escape(d.plcopen()))
                    .append("</td><td class=\"num\">")
                    .append(counts.getOrDefault(d.id(), 0))
                    .append("</td></tr>\n");
        }
        h.append("</tbody>\n</table>\n");
        for (RuleDescriptor d : rules.values()) {
            h.append("<details class=\"help\" id=\"rule-")
                    .append(Html.escape(d.id()))
                    .append("\">\n<summary>")
                    .append(Html.escape(d.id() + " " + d.title()))
                    .append("</summary>\n<div class=\"md\">\n")
                    .append(Markdown.toHtml(d.markdown()))
                    .append("</div>\n</details>\n");
        }
        h.append("</section>\n");
    }

    /** A language label from the file name, for the filter. */
    static String language(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".scl") || p.endsWith(".db") || p.endsWith(".udt")) {
            return "Siemens SCL";
        }
        if (p.endsWith(".l5x")) {
            return "Rockwell L5X";
        }
        if (p.endsWith(".xml")) {
            return "PLCopen XML";
        }
        if (p.contains(".tc")) {
            return "TwinCAT";
        }
        return "Structured Text";
    }

    private static String key(Severity s) {
        return s.name().toLowerCase(Locale.ROOT);
    }

    private static String label(Severity s) {
        return switch (s) {
            case ERROR -> "Errors";
            case WARNING -> "Warnings";
            case INFO, HINT -> "Info";
        };
    }

    /** 3 error, 2 warning, 1 info/hint: filters show "this severity and above". */
    private static int rank(Severity s) {
        return switch (s) {
            case ERROR -> 3;
            case WARNING -> 2;
            case INFO, HINT -> 1;
        };
    }

    private static final String CSS = """
            :root { --bg: #ffffff; --fg: #1d2330; --muted: #5d6675; --line: #d9dee6; --card: #f5f7fa;
              --code: #f3f4f6; --hit: #fff3c4; --err: #c62828; --warn: #b26a00; --info: #1565c0; --link: #1a56db; }
            @media (prefers-color-scheme: dark) {
              :root { --bg: #14171c; --fg: #e3e7ee; --muted: #9aa3b2; --line: #2c323c; --card: #1c2028;
                --code: #1a1e25; --hit: #4a3f12; --err: #ef6b6b; --warn: #f0a83a; --info: #6aa8f0; --link: #7aa7ff; }
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--fg);
              font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
            main { max-width: 1100px; margin: 0 auto; padding: 24px 16px 64px; }
            h1 { font-size: 1.6rem; margin: 0 0 4px; } h2 { font-size: 1.25rem; margin: 32px 0 12px; }
            h4 { margin: 18px 0 6px; } a { color: var(--link); }
            .muted { color: var(--muted); }
            .cards { display: flex; flex-wrap: wrap; gap: 12px; margin: 20px 0 8px; }
            .card { background: var(--card); border: 1px solid var(--line); border-radius: 8px; padding: 12px 16px;
              min-width: 130px; }
            .card .value { font-size: 1.6rem; font-weight: 600; } .card .label { color: var(--muted); }
            .card .note { font-size: 0.8rem; color: var(--muted); }
            .card.sev-error .value { color: var(--err); } .card.sev-warning .value { color: var(--warn); }
            .card.sev-info .value { color: var(--info); }
            .filters { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; padding: 12px;
              background: var(--card); border: 1px solid var(--line); border-radius: 8px; position: sticky; top: 0; }
            .filters select, .filters input { font: inherit; color: inherit; background: var(--bg);
              border: 1px solid var(--line); border-radius: 6px; padding: 4px 6px; max-width: 100%; }
            details.file { border: 1px solid var(--line); border-radius: 8px; margin: 12px 0; }
            details.file > summary { padding: 8px 12px; cursor: pointer; background: var(--card);
              border-radius: 8px; overflow-wrap: anywhere; }
            .path { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 0.9rem; }
            .finding { padding: 10px 12px; border-top: 1px solid var(--line); }
            .finding .head { display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; }
            .msg { margin: 6px 0 2px; } .title { margin: 0 0 6px; font-size: 0.85rem; }
            .badge { font-size: 0.75rem; font-weight: 600; text-transform: uppercase; padding: 1px 6px;
              border-radius: 4px; border: 1px solid currentColor; }
            .badge.sev-error { color: var(--err); } .badge.sev-warning { color: var(--warn); }
            .badge.sev-info, .badge.sev-hint { color: var(--info); }
            .drawing { margin: 8px 0; overflow-x: auto; } .drawing figcaption { font-size: 12px; margin-bottom: 4px; }
            .drawing svg { display: block; max-width: none; background: var(--bg); }
            .drawing .plca-svg { --plca-wire: var(--muted); --plca-sym: var(--fg); --plca-text: var(--fg);
              --plca-muted: var(--muted); }
            .drawing .has-finding > .hit { stroke-width: 2; stroke-dasharray: 4 3; }
            .drawing .has-finding.sev-error > .hit { stroke: var(--err); }
            .drawing .has-finding.sev-warning > .hit { stroke: var(--warn); }
            .drawing .has-finding.sev-info > .hit, .drawing .has-finding.sev-hint > .hit { stroke: var(--info); }
            .drawn-above { font-size: 12px; margin: 4px 0; }
            .rule, .loc, .place { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 0.85rem; }
            .place { color: var(--muted); overflow-wrap: anywhere; }
            pre { background: var(--code); border: 1px solid var(--line); border-radius: 6px; padding: 8px 0;
              overflow-x: auto; font: 13px/1.45 ui-monospace, Menlo, Consolas, monospace; margin: 6px 0; }
            pre code { padding: 0 10px; display: block; }
            .src .ln { display: block; padding: 0 10px; white-space: pre; }
            .src .ln.hit { background: var(--hit); }
            .src .no { display: inline-block; min-width: 4.5em; color: var(--muted); user-select: none; }
            ul.related { margin: 4px 0; padding-left: 20px; font-size: 0.9rem; }
            ul.problems { padding-left: 0; list-style: none; } ul.problems li { margin: 4px 0; overflow-wrap: anywhere; }
            table { border-collapse: collapse; width: 100%; font-size: 0.9rem; }
            th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--line); vertical-align: top; }
            td.num { text-align: right; }
            details.help { border-bottom: 1px solid var(--line); padding: 6px 0; }
            details.help > summary { cursor: pointer; font-weight: 600; }
            .md code { background: var(--code); padding: 0 3px; border-radius: 3px; }
            summary h2 { display: inline; }
            @media (max-width: 600px) { .card { min-width: calc(50% - 6px); } table { font-size: 0.8rem; } }
            """;

    private static final String SCRIPT = """
            (function () {
              var f = document.getElementById('filters'); if (!f) return; f.hidden = false;
              var sev = document.getElementById('f-sev'), rule = document.getElementById('f-rule'),
                  lang = document.getElementById('f-lang'), text = document.getElementById('f-text'),
                  count = document.getElementById('f-count');
              var ranks = { error: 3, warning: 2, info: 1 };
              function apply() {
                var min = sev.value ? ranks[sev.value] : 0, r = rule.value, l = lang.value,
                    t = text.value.trim().toLowerCase(), shown = 0;
                document.querySelectorAll('details.file').forEach(function (file) {
                  var any = false;
                  file.querySelectorAll('article.finding').forEach(function (a) {
                    var ok = Number(a.dataset.sev) >= min && (!r || a.dataset.rule === r)
                        && (!l || a.dataset.lang === l) && (!t || a.dataset.text.indexOf(t) >= 0);
                    a.hidden = !ok; if (ok) { any = true; shown++; }
                  });
                  file.hidden = !any;
                });
                count.textContent = shown + ' shown';
              }
              [sev, rule, lang].forEach(function (e) { e.addEventListener('change', apply); });
              text.addEventListener('input', apply);
              apply();
            })();
            """;
}
