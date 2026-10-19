package dev.plca.analysis.nav;

import dev.plca.analysis.Snapshot;
import dev.plca.ast.Comment;
import dev.plca.ast.Declaration;
import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.ast.Ladder;
import dev.plca.ast.Node;
import dev.plca.ast.QualifiedName;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import dev.plca.ast.TypeRef;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.semantic.BuiltinFunction;
import dev.plca.semantic.EnumValueSymbol;
import dev.plca.semantic.GvlSymbol;
import dev.plca.semantic.InstructionSymbol;
import dev.plca.semantic.IoTagSymbol;
import dev.plca.semantic.LibrarySymbol;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Symbol;
import dev.plca.semantic.Type;
import dev.plca.semantic.TypeSymbol;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Editor queries on one {@link Snapshot}: the symbol at a position, its declaration, its references and a hover text.
 * Answers come from the semantic model (design rule 3: never from parse trees); positions are physical file offsets in
 * UTF-16 units (the LSP default) and map into XML-embedded text through the snapshot's registry.
 *
 * <p>Thread-safe for queries; the lazily built indexes are per snapshot. Node indexes of units that did not change are
 * taken over from the previous navigator.
 */
public final class Navigator {

    /**
     * A symbol found at a position: {@code occurrence} is the name's span, {@code expression} the bound expression
     * (null on a declaration or type name).
     */
    public record Target(Symbol symbol, Span occurrence, SemanticModel model, Expression expression) {}

    /** Hover text (Markdown) and the range it describes. */
    public record Hover(String markdown, Span range) {}

    private final Snapshot snapshot;
    private final Map<SourceUnit, NodeIndex> indexes = new IdentityHashMap<>();
    private final Map<SemanticModel, Map<Node, Symbol>> declarations = new IdentityHashMap<>();
    private final Map<SemanticModel, Map<Symbol, List<Span>>> references = new IdentityHashMap<>();

    public Navigator(Snapshot snapshot) {
        this(snapshot, null);
    }

    /** A navigator for {@code snapshot} that reuses {@code previous}'s indexes of units both snapshots share. */
    public Navigator(Snapshot snapshot, Navigator previous) {
        this.snapshot = snapshot;
        if (previous != null) {
            synchronized (previous) {
                for (Snapshot.Group g : snapshot.groups()) {
                    for (SourceUnit u : g.units()) {
                        NodeIndex i = previous.indexes.get(u);
                        if (i != null) {
                            indexes.put(u, i);
                        }
                    }
                }
            }
        }
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    synchronized NodeIndex index(SourceUnit unit) {
        return indexes.computeIfAbsent(unit, NodeIndex::of);
    }

    /** The symbol named at {@code fileOffset} in {@code path}. */
    public Optional<Target> symbolAt(String path, int fileOffset) {
        SourceRegistry.Position pos = snapshot.registry().positionAt(path, fileOffset);
        Optional<Snapshot.Group> group = snapshot.groupOf(path);
        if (pos == null || group.isEmpty()) {
            return Optional.empty();
        }
        SemanticModel model = group.get().model();
        NodeIndex index = index(snapshot.file(path).orElseThrow().unit());
        for (Node n : index.at(pos.text().id(), pos.offset())) {
            Target t = targetOf(n, index, model);
            if (t != null) {
                return Optional.of(t);
            }
            if (n instanceof Statement || n instanceof Declaration || n instanceof VarBlock) {
                break; // only names inside the innermost statement or declaration count
            }
        }
        return Optional.empty();
    }

    private Target targetOf(Node n, NodeIndex index, SemanticModel model) {
        switch (n) {
            case Identifier id
            when id.form() != Identifier.Form.SYNTHETIC -> {
                Node parent = index.parent(id);
                if (parent instanceof Expression.Argument a && a.name() == id) {
                    return model.parameter(a)
                            .map(v -> new Target(v, id.span(), model, null))
                            .orElse(null);
                }
                if (parent instanceof QualifiedName q
                        && isTypeName(index.parent(q))
                        && q.parts().getLast() == id) {
                    return typeSymbol(model, id.name())
                            .map(s -> new Target(s, id.span(), model, null))
                            .orElse(null);
                }
                if (parent instanceof Ladder.Instruction i && i.mnemonic() == id) {
                    // an AOI call is lowered to a call of its instance tag: the mnemonic itself is not bound
                    return model.global()
                            .pou(id.name())
                            .map(p -> new Target(p, id.span(), model, null))
                            .orElse(null);
                }
                Symbol declared = parent == null ? null : declarations(model).get(parent);
                if (declared != null && nameOf(parent) == id) {
                    return new Target(declared, id.span(), model, null);
                }
                return null;
            }
            case Expression e -> {
                Optional<Symbol> s = model.symbol(e);
                Span occurrence = occurrence(e);
                return s.isPresent() && occurrence != null ? new Target(s.get(), occurrence, model, e) : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** The span of the declared name of {@code symbol}, or empty for builtins, library symbols and made-up names. */
    public Optional<Span> definition(Symbol symbol) {
        Node d = symbol.declaration();
        if (d == null) {
            return Optional.empty();
        }
        Identifier name = nameOf(d);
        if (name != null) {
            return name.form() == Identifier.Form.SYNTHETIC ? Optional.empty() : Optional.of(name.span());
        }
        return Optional.ofNullable(d.span());
    }

    /**
     * Every place the target's symbol is named in its model, sorted by file and offset: bound names and member
     * accesses, named arguments, and type names in declarations ({@code fb : FB_Motor}, {@code EXTENDS FB_Base}).
     */
    public List<Span> references(Target target, boolean includeDeclaration) {
        List<Span> uses = referenceMap(target.model()).getOrDefault(target.symbol(), List.of());
        Optional<Span> declaration = definition(target.symbol());
        if (declaration.isEmpty()) {
            return uses;
        }
        List<Span> out = new ArrayList<>(uses.size() + 1);
        for (Span s : uses) {
            if (!s.equals(declaration.get())) {
                out.add(s);
            }
        }
        if (includeDeclaration) {
            out.add(declaration.get());
            out.sort(fileOrder());
        }
        return out;
    }

    /** By file, then by offset in the file. Keys are computed per comparison: sort small lists only. */
    private Comparator<Span> fileOrder() {
        SourceRegistry r = snapshot.registry();
        return Comparator.<Span, String>comparing(s -> r.text(s.textId()).file().path())
                .thenComparingInt(s -> r.text(s.textId()).map().toFileStart(s.start()));
    }

    /** The hover at a position: the symbol's declaration, owner and comment, or the type of an expression. */
    public Optional<Hover> hover(String path, int fileOffset) {
        Optional<Target> t = symbolAt(path, fileOffset);
        if (t.isPresent()) {
            return Optional.of(new Hover(describe(t.get()), t.get().occurrence()));
        }
        SourceRegistry.Position pos = snapshot.registry().positionAt(path, fileOffset);
        Optional<Snapshot.Group> group = snapshot.groupOf(path);
        if (pos == null || group.isEmpty()) {
            return Optional.empty();
        }
        NodeIndex index = index(snapshot.file(path).orElseThrow().unit());
        for (Node n : index.at(pos.text().id(), pos.offset())) {
            if (n instanceof Expression e && !(e instanceof Expression.Invalid)) {
                Type type = group.get().model().type(e);
                if (type instanceof Type.Unknown) {
                    return Optional.empty();
                }
                return Optional.of(new Hover("Type: `" + type.display() + "`", e.span()));
            }
            if (n instanceof Statement || n instanceof Declaration) {
                break;
            }
        }
        return Optional.empty();
    }

    // ---- hover text -----------------------------------------------------------------------------

    String describe(Target t) {
        StringBuilder md = new StringBuilder();
        switch (t.symbol()) {
            case VariableSymbol v -> {
                code(
                        md,
                        section(v) + " "
                                + (v.declaration() == null || isMarkup(v.declaration())
                                        ? v.name() + " : " + v.type().display()
                                        : oneLine(text(v.declaration().span()), 240)));
                if (v.owner() != null) {
                    md.append("\n\nin `").append(ownerName(v.owner())).append('`');
                }
                if (v.address() != null) {
                    md.append("\n\nat `").append(v.address()).append('`');
                }
            }
            case PouSymbol p
            when p.isBuiltin() -> {
                code(md, p.kind().name() + " " + p.name());
                md.append("\n\nstandard ")
                        .append(p.kind().name().toLowerCase(Locale.ROOT).replace('_', ' '));
                String params = p.variables().values().stream()
                        .filter(v -> v.section() == VarBlock.Section.INPUT
                                || v.section() == VarBlock.Section.OUTPUT
                                || v.section() == VarBlock.Section.IN_OUT)
                        .map(v -> "- `" + v.name() + " : " + v.type().display() + "` ("
                                + section(v).toLowerCase(Locale.ROOT) + ")")
                        .collect(Collectors.joining("\n"));
                if (!params.isEmpty()) {
                    md.append("\n\n").append(params);
                }
            }
            case PouSymbol p -> {
                code(md, header(p));
                if (p.owner() != null) {
                    md.append("\n\nin `").append(p.owner().qualifiedName()).append('`');
                }
            }
            case TypeSymbol ts ->
                code(
                        md,
                        ts.declaration() == null || isMarkup(ts.declaration())
                                ? "TYPE " + ts.name() + " : " + ts.kind().name()
                                : firstLines(text(ts.declaration().span()), 12));
            case EnumValueSymbol ev -> code(md, ev.enumType().name() + "." + ev.name() + " = " + ev.value());
            case GvlSymbol g -> code(md, (g.isDataBlocks() ? "data blocks " : "VAR_GLOBAL ") + g.name());
            case BuiltinFunction f -> {
                code(md, "FUNCTION " + f.name());
                md.append("\n\nstandard function");
            }
            case LibrarySymbol l ->
                md.append('`').append(l.name()).append("`: from a library that is not in the project");
            case InstructionSymbol i ->
                md.append("Rockwell instruction `")
                        .append(i.name())
                        .append('`')
                        .append(i.isKnown() ? "" : " (not in plca's instruction table)");
            case IoTagSymbol io ->
                md.append(io.isOutput() ? "output" : "input")
                        .append(" module tag `")
                        .append(io.name())
                        .append('`');
        }
        Node d = t.symbol().declaration();
        if (d != null && !(t.symbol() instanceof TypeSymbol)) {
            String doc = docComment(d);
            if (!doc.isEmpty()) {
                md.append("\n\n---\n\n").append(doc);
            }
        }
        if (t.expression() != null && t.symbol() instanceof VariableSymbol v) {
            Type at = t.model().type(t.expression());
            if (!(at instanceof Type.Unknown) && !at.display().equals(v.type().display())) {
                md.append("\n\nType here: `").append(at.display()).append('`');
            }
        }
        return md.toString();
    }

    /** Declarations of XML exports (L5X tags, PLCopen variables) span XML elements, not source code to quote. */
    private boolean isMarkup(Node decl) {
        return text(decl.span()).stripLeading().startsWith("<");
    }

    private static void code(StringBuilder md, String code) {
        md.append("```iec-st\n").append(code).append("\n```");
    }

    private static String section(VariableSymbol v) {
        VarBlock b = v.block();
        String s = switch (v.section()) {
            case VAR -> "VAR";
            case INPUT -> "VAR_INPUT";
            case OUTPUT -> "VAR_OUTPUT";
            case IN_OUT -> "VAR_IN_OUT";
            case GLOBAL -> "VAR_GLOBAL";
            case EXTERNAL -> "VAR_EXTERNAL";
            case TEMP -> "VAR_TEMP";
            case STAT -> "VAR_STAT";
            case INST -> "VAR_INST";
            case ACCESS -> "VAR_ACCESS";
            case CONFIG -> "VAR_CONFIG";
        };
        if (b != null) {
            for (VarBlock.Qualifier q : VarBlock.Qualifier.values()) {
                if (b.is(q)) {
                    s += " " + q.name();
                }
            }
        }
        return s;
    }

    private static String ownerName(Symbol owner) {
        return owner instanceof PouSymbol p ? p.qualifiedName() : owner.name();
    }

    private String header(PouSymbol p) {
        Declaration.Pou d = p.declaration();
        StringBuilder h = new StringBuilder(p.kind().name()).append(' ').append(p.name());
        if (!d.extendsTypes().isEmpty()) {
            h.append(" EXTENDS ").append(names(d.extendsTypes()));
        }
        if (!d.implementsTypes().isEmpty()) {
            h.append(" IMPLEMENTS ").append(names(d.implementsTypes()));
        }
        if (d.returnType() != null) {
            h.append(" : ").append(oneLine(text(d.returnType().span()), 120));
        }
        return h.toString();
    }

    private static String names(List<QualifiedName> names) {
        return names.stream()
                .map(q -> q.parts().stream().map(Identifier::name).collect(Collectors.joining(".")))
                .collect(Collectors.joining(", "));
    }

    /** Line comments on the declaration's line after it, else the comment block directly above it. */
    private String docComment(Node decl) {
        Span span = decl.span();
        SourceUnit unit = unitOf(span);
        if (unit == null) {
            return "";
        }
        SourceText t = snapshot.registry().text(span.textId());
        String text = t.text();
        int lineEnd = indexOrEnd(text, '\n', span.end());
        int lineStart = text.lastIndexOf('\n', Math.max(0, span.start() - 1)) + 1;
        List<Comment> above = new ArrayList<>();
        for (Comment c : unit.comments()) {
            Span s = c.span();
            if (s.textId() != span.textId()) {
                continue;
            }
            if (s.start() >= span.end() && s.start() < lineEnd) {
                return strip(text.substring(s.start(), s.end()));
            }
            if (s.end() <= lineStart) {
                above.add(c);
            }
        }
        // the comments directly above: walk back while only whitespace lies between them and the declaration
        above.sort(Comparator.comparingInt(c -> c.span().start()));
        List<String> parts = new ArrayList<>();
        int limit = lineStart;
        for (int i = above.size() - 1; i >= 0; i--) {
            Span s = above.get(i).span();
            String between = text.substring(s.end(), limit);
            if (!between.isBlank() || between.chars().filter(ch -> ch == '\n').count() > 1) {
                break;
            }
            parts.addFirst(strip(text.substring(s.start(), s.end())));
            limit = text.lastIndexOf('\n', Math.max(0, s.start() - 1)) + 1;
            if (!text.substring(limit, s.start()).isBlank()) {
                break; // the comment trails other code
            }
        }
        return String.join("\n", parts).strip();
    }

    private static String strip(String comment) {
        String c = comment.strip();
        if (c.startsWith("//")) {
            return c.substring(2).strip();
        }
        if ((c.startsWith("(*") && c.endsWith("*)")) || (c.startsWith("/*") && c.endsWith("*/"))) {
            c = c.substring(2, c.length() - 2);
        }
        return c.lines().map(String::strip).collect(Collectors.joining("\n")).strip();
    }

    private static int indexOrEnd(String s, char ch, int from) {
        int i = s.indexOf(ch, from);
        return i < 0 ? s.length() : i;
    }

    private String text(Span span) {
        SourceText t = snapshot.registry().text(span.textId());
        return t.text().substring(span.start(), span.end());
    }

    private static String oneLine(String s, int max) {
        String l = s.strip().replaceAll("\\s+", " ");
        if (l.endsWith(";")) {
            l = l.substring(0, l.length() - 1);
        }
        return l.length() <= max ? l : l.substring(0, max) + " …";
    }

    private static String firstLines(String s, int max) {
        List<String> lines = s.strip().lines().toList();
        return lines.size() <= max ? String.join("\n", lines) : String.join("\n", lines.subList(0, max)) + "\n…";
    }

    // ---- indexes --------------------------------------------------------------------------------

    /** The span that names the symbol in a bound expression: the member of {@code a.b}, a name, or a whole callee. */
    private static Span occurrence(Expression e) {
        return switch (e) {
            case Expression.MemberAccess m -> m.member().span();
            case Expression.NameRef n ->
                n.name().form() == Identifier.Form.SYNTHETIC ? null : n.name().span();
            case Expression.Call c -> occurrence(c.callee());
            default -> e.span();
        };
    }

    private static boolean isTypeName(Node parent) {
        return parent instanceof TypeRef.Named
                || parent instanceof Declaration.Pou
                || parent instanceof Declaration.TypeDecl
                || parent instanceof Declaration.DataBlock
                || parent instanceof Declaration.ProgramInstance
                || parent instanceof Expression.TypeOperand;
    }

    private static Optional<Symbol> typeSymbol(SemanticModel model, String name) {
        Optional<Symbol> s = model.global().pou(name).map(Symbol.class::cast);
        if (s.isEmpty()) {
            s = model.global().type(name).map(Symbol.class::cast);
        }
        if (s.isEmpty()) {
            s = model.global().builtin(name);
        }
        return s;
    }

    /** The name identifier of a declaring node, or null. */
    static Identifier nameOf(Node d) {
        return switch (d) {
            case VarDecl v -> v.name();
            case Declaration.Pou p -> p.name();
            case Declaration.TypeDecl t -> t.name();
            case Declaration.DataBlock b -> b.name();
            case Declaration.GlobalVars g -> g.listName();
            case Declaration.Configuration c -> c.name();
            case Declaration.Resource r -> r.name();
            case Declaration.Task t -> t.name();
            case Declaration.ProgramInstance p -> p.name();
            case TypeRef.EnumValue v -> v.name();
            default -> null;
        };
    }

    private synchronized Map<Node, Symbol> declarations(SemanticModel model) {
        return declarations.computeIfAbsent(model, m -> {
            Map<Node, Symbol> map = new IdentityHashMap<>();
            for (PouSymbol p : m.pous()) {
                if (p.declaration() != null) {
                    map.put(p.declaration(), p);
                }
                p.variables().values().forEach(v -> putDeclaration(map, v));
            }
            for (TypeSymbol t : m.global().types()) {
                putDeclaration(map, t);
                t.members().values().forEach(v -> putDeclaration(map, v));
                t.values().values().forEach(v -> putDeclaration(map, v));
            }
            for (GvlSymbol g : m.global().gvls()) {
                putDeclaration(map, g);
                g.variables().values().forEach(v -> putDeclaration(map, v));
            }
            return map;
        });
    }

    private static void putDeclaration(Map<Node, Symbol> map, Symbol s) {
        if (s.declaration() != null) {
            map.putIfAbsent(s.declaration(), s);
        }
    }

    private synchronized Map<Symbol, List<Span>> referenceMap(SemanticModel model) {
        return references.computeIfAbsent(model, m -> {
            Map<Symbol, List<Span>> map = new IdentityHashMap<>();
            m.forEachBinding((e, s) -> {
                Span o = occurrence(e);
                if (o != null) {
                    map.computeIfAbsent(s, k -> new ArrayList<>()).add(o);
                }
            });
            m.forEachParameterBinding((a, v) -> {
                if (a.name() != null) {
                    map.computeIfAbsent(v, k -> new ArrayList<>()).add(a.name().span());
                }
            });
            for (SourceUnit u : m.units()) {
                NodeIndex index = index(u);
                index.forEach(n -> {
                    if (n instanceof Ladder.Instruction i) {
                        m.global()
                                .pou(i.mnemonic().name())
                                .ifPresent(p -> map.computeIfAbsent(p, k -> new ArrayList<>())
                                        .add(i.mnemonic().span()));
                    }
                    if (n instanceof QualifiedName q && !q.parts().isEmpty() && isTypeName(index.parent(q))) {
                        Identifier last = q.parts().getLast();
                        typeSymbol(m, last.name())
                                .ifPresent(s -> map.computeIfAbsent(s, k -> new ArrayList<>())
                                        .add(last.span()));
                    }
                });
            }
            // once per model, deduplicated and in file order: a common name has tens of thousands of uses
            SourceRegistry r = snapshot.registry();
            map.replaceAll((symbol, spans) -> {
                record Keyed(Span span, String path, int offset) {}
                List<Keyed> keyed = new ArrayList<>(spans.size());
                for (Span sp : new HashSet<>(spans)) {
                    var t = r.text(sp.textId());
                    keyed.add(new Keyed(sp, t.file().path(), t.map().toFileStart(sp.start())));
                }
                keyed.sort(Comparator.comparing(Keyed::path).thenComparingInt(Keyed::offset));
                return keyed.stream().map(Keyed::span).toList();
            });
            return map;
        });
    }

    private SourceUnit unitOf(Span span) {
        String path = snapshot.pathOf(span);
        return snapshot.file(path).map(f -> f.unit()).orElse(null);
    }
}
