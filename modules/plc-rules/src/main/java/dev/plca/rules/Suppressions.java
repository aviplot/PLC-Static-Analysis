package dev.plca.rules;

import dev.plca.ast.Comment;
import dev.plca.ast.Declaration;
import dev.plca.ast.Pragma;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import dev.plca.core.Dialect;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.Span;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inline and configured suppressions. Inline forms, in any comment of any dialect:
 *
 * <ul>
 *   <li>{@code plca-disable-next-line PLCA-Q003 reason} — findings starting on the next line;
 *   <li>{@code plca-disable-line PLCA-Q003} — findings starting on the comment's line;
 *   <li>{@code plca-disable PLCA-P001} … {@code plca-enable PLCA-P001} — a region of the same file (to its end when not
 *       re-enabled);
 *   <li>{@code {attribute 'plca-disable' := 'PLCA-Q001, PLCA-Q006'}} on a POU (its body, members included) or on a
 *       variable declaration (findings on the declaration);
 *   <li>Rockwell: a rung comment containing {@code plca-disable PLCA-P001} — findings in the rung it belongs to.
 * </ul>
 *
 * <p>Rules are named by id, category or {@code all}; a directive naming none is ignored with a warning, since
 * suppressing every rule hides too much by accident.
 */
public final class Suppressions {

    /** A suppression comment or attribute that names no known rule. */
    public static final String BAD_DIRECTIVE = "PLC-SUP-001";

    /** A suppressed finding and why: {@code inline} or {@code config}, with the directive's reason if any. */
    public record Suppressed(Finding finding, String kind, String reason) {}

    private static final Pattern DIRECTIVE =
            Pattern.compile("(?i)\\bplca-(disable-next-line|disable-line|disable|enable)\\b([^\\r\\n]*)");
    private static final Pattern SELECTOR = Pattern.compile("(?i)^(PLCA-[QPS]\\d{3}|quality|plc|security|all)$");
    private static final String ATTRIBUTE = "plca-disable";

    /** Lines [from, to] of a file where rules are off. */
    private record LineRange(String path, int from, int to, Set<String> rules, String reason) {}

    private record DeclRange(Span span, Set<String> rules) {}

    private final RuleRegistry registry;
    private final SourceRegistry sources;
    private final List<LineRange> lines = new ArrayList<>();
    private final List<DeclRange> declarations = new ArrayList<>();
    private final Map<String, Set<String>> pous = new HashMap<>();
    private final List<RuleConfig.Suppression> configured;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    Suppressions(RuleRegistry registry, SemanticModel model, SourceRegistry sources, RuleConfig config) {
        this.registry = registry;
        this.sources = sources;
        this.configured = config.suppressions();
        Map<String, List<int[]>> rungLines = rungLines(model);
        for (SourceUnit u : model.units()) {
            Map<String, LineRange> open = new HashMap<>();
            for (Comment c : u.comments()) {
                comment(u, c, open, rungLines);
            }
            open.values()
                    .forEach(r ->
                            lines.add(new LineRange(r.path(), r.from(), Integer.MAX_VALUE, r.rules(), r.reason())));
            for (Declaration d : u.declarations()) {
                declaration(d);
            }
        }
        for (PouSymbol p : model.pous()) {
            if (p.declaration() != null) {
                Set<String> rules = attribute(p.declaration().pragmas());
                if (!rules.isEmpty()) {
                    pous.computeIfAbsent(p.qualifiedName().toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                            .addAll(rules);
                }
            }
        }
    }

    List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** Why {@code f} is suppressed, or null when it is not. */
    Suppressed check(Finding f) {
        Location l = sources.resolve(f.span());
        for (LineRange r : lines) {
            if (r.path().equals(l.path())
                    && l.startLine() >= r.from()
                    && l.startLine() <= r.to()
                    && covers(r.rules(), f)) {
                return new Suppressed(f, "inline", r.reason());
            }
        }
        for (DeclRange r : declarations) {
            Span s = r.span();
            if (s.textId() == f.span().textId()
                    && f.span().start() >= s.start()
                    && f.span().end() <= s.end()
                    && covers(r.rules(), f)) {
                return new Suppressed(f, "inline", "");
            }
        }
        if (f.pou() != null) {
            String name = f.pou().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Set<String>> e : pous.entrySet()) {
                if ((name.equals(e.getKey()) || name.startsWith(e.getKey() + ".")) && covers(e.getValue(), f)) {
                    return new Suppressed(f, "inline", "");
                }
            }
        }
        for (RuleConfig.Suppression s : configured) {
            if (covers(selectors(s.rule()), f)
                    && (s.path() == null || glob(s.path()).matches(Path.of(l.path())))
                    && (s.pou() == null
                            || f.pou() != null
                                    && globRegex(s.pou()).matcher(f.pou()).matches())) {
                return new Suppressed(f, "config", s.reason() == null ? "" : s.reason());
            }
        }
        return null;
    }

    private boolean covers(Set<String> ruleIds, Finding f) {
        return ruleIds.contains(f.ruleId().toUpperCase(Locale.ROOT));
    }

    private void comment(SourceUnit u, Comment c, Map<String, LineRange> open, Map<String, List<int[]>> rungLines) {
        String text = sources.text(c.span().textId())
                .text()
                .substring(c.span().start(), c.span().end());
        Matcher m = DIRECTIVE.matcher(text);
        if (!m.find()) {
            return;
        }
        Location l = sources.resolve(c.span());
        String kind = m.group(1).toLowerCase(Locale.ROOT);
        String rest = m.group(2).replaceAll("\\*\\)\\s*$|\\*/\\s*$|-->\\s*$", "");
        Set<String> rules = new LinkedHashSet<>();
        StringBuilder reason = new StringBuilder();
        for (String word : rest.trim().split("[\\s,]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (reason.isEmpty() && SELECTOR.matcher(word).matches()) {
                rules.addAll(selectors(word));
            } else {
                reason.append(reason.isEmpty() ? "" : " ").append(word);
            }
        }
        if (rules.isEmpty()) {
            diagnostics.add(Diagnostic.warning(
                    BAD_DIRECTIVE,
                    "plca-" + kind + " names no rule (an id such as PLCA-Q003, a category, or all); ignored",
                    c.span()));
            return;
        }
        String why = reason.toString();
        boolean rungComment = u.dialect() == Dialect.ROCKWELL && !looksLikeStComment(text);
        if (rungComment) {
            // a rung's <Comment> precedes its <Text>: the comment belongs to the next rung below it
            int end = sources.text(c.span().textId()).map().toFileStart(c.span().end());
            for (int[] rung : rungLines.getOrDefault(l.path(), List.of())) {
                if (rung[0] >= end) {
                    lines.add(new LineRange(l.path(), rung[1], rung[2], rules, why));
                    break;
                }
            }
            return;
        }
        switch (kind) {
            case "disable-next-line" ->
                lines.add(new LineRange(l.path(), l.endLine() + 1, l.endLine() + 1, rules, why));
            case "disable-line" -> lines.add(new LineRange(l.path(), l.startLine(), l.endLine(), rules, why));
            case "disable" -> {
                for (String r : rules) {
                    open.putIfAbsent(r, new LineRange(l.path(), l.startLine(), 0, Set.of(r), why));
                }
            }
            default -> { // enable
                for (String r : rules) {
                    LineRange from = open.remove(r);
                    if (from != null) {
                        lines.add(new LineRange(from.path(), from.from(), l.endLine(), from.rules(), from.reason()));
                    }
                }
            }
        }
    }

    private static boolean looksLikeStComment(String text) {
        String t = text.strip();
        return t.startsWith("//") || t.startsWith("(*") || t.startsWith("/*");
    }

    private void declaration(Declaration d) {
        switch (d) {
            case Declaration.Pou p -> {
                p.varBlocks().forEach(this::block);
                p.members().forEach(this::declaration);
            }
            case Declaration.GlobalVars g -> block(g.block());
            case Declaration.DataBlock db -> db.varBlocks().forEach(this::block);
            case Declaration.Namespace n -> n.members().forEach(this::declaration);
            default -> {}
        }
    }

    private void block(VarBlock b) {
        Set<String> blockRules = attribute(b.pragmas());
        for (VarDecl v : b.variables()) {
            Set<String> rules = new LinkedHashSet<>(blockRules);
            rules.addAll(attribute(v.pragmas()));
            if (!rules.isEmpty()) {
                declarations.add(new DeclRange(v.span(), rules));
            }
        }
    }

    private Set<String> attribute(List<Pragma> pragmas) {
        Set<String> rules = new LinkedHashSet<>();
        for (Pragma p : pragmas) {
            if (p.isAttribute(ATTRIBUTE)) {
                Set<String> named = new LinkedHashSet<>();
                if (p.value() != null) {
                    for (String w : p.value().trim().split("[\\s,]+")) {
                        if (SELECTOR.matcher(w).matches()) {
                            named.addAll(selectors(w));
                        }
                    }
                }
                if (named.isEmpty()) {
                    diagnostics.add(Diagnostic.warning(
                            BAD_DIRECTIVE,
                            "attribute 'plca-disable' names no rule (an id such as PLCA-Q001, a category, or all);"
                                    + " ignored",
                            p.span()));
                }
                rules.addAll(named);
            }
        }
        return rules;
    }

    /** Upper-case rule ids for an id, category or {@code all}; unknown ids give an empty set. */
    private Set<String> selectors(String word) {
        try {
            Set<String> out = new LinkedHashSet<>();
            RuleConfig.expand(registry, List.of(word)).forEach(id -> out.add(id.toUpperCase(Locale.ROOT)));
            return out;
        } catch (IllegalArgumentException e) {
            return Set.of();
        }
    }

    /** File offset and physical line range of every ladder rung, per file, in file order. */
    private Map<String, List<int[]>> rungLines(SemanticModel model) {
        Map<String, List<int[]>> out = new HashMap<>();
        for (PouSymbol p : model.pous()) {
            if (p.dialect() != Dialect.ROCKWELL
                    || p.declaration() == null
                    || p.declaration().body() == null) {
                continue;
            }
            for (var s : p.declaration().body()) {
                if (s instanceof dev.plca.ast.Statement.Rung r) {
                    Location l = sources.resolve(r.span());
                    int start = sources.text(r.span().textId())
                            .map()
                            .toFileStart(r.span().start());
                    out.computeIfAbsent(l.path(), k -> new ArrayList<>())
                            .add(new int[] {start, l.startLine(), l.endLine()});
                }
            }
        }
        out.values().forEach(v -> v.sort((a, b) -> Integer.compare(a[0], b[0])));
        return out;
    }

    private final Map<String, PathMatcher> globs = new HashMap<>();
    private final Map<String, Pattern> regexes = new HashMap<>();

    private PathMatcher glob(String pattern) {
        return globs.computeIfAbsent(pattern, p -> FileSystems.getDefault().getPathMatcher("glob:" + p));
    }

    /** A POU glob: {@code *} any characters, {@code ?} one; case-insensitive. */
    private Pattern globRegex(String pattern) {
        return regexes.computeIfAbsent(pattern, p -> {
            StringBuilder sb = new StringBuilder();
            for (char ch : p.toCharArray()) {
                sb.append(ch == '*' ? ".*" : ch == '?' ? "." : Pattern.quote(String.valueOf(ch)));
            }
            return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
        });
    }
}
