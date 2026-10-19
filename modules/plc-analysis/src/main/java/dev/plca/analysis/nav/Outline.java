package dev.plca.analysis.nav;

import dev.plca.analysis.Snapshot;
import dev.plca.ast.Declaration;
import dev.plca.ast.Identifier;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.TypeRef;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.semantic.GvlSymbol;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.TypeSymbol;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The document outline of a file and the workspace symbol search, from the AST and the semantic model. */
public final class Outline {

    /** What an item is; the LSP layer maps it to a {@code SymbolKind}. */
    public enum Kind {
        PROGRAM,
        FUNCTION_BLOCK,
        FUNCTION,
        CLASS,
        INTERFACE,
        METHOD,
        PROPERTY,
        ACTION,
        ROUTINE,
        VARIABLE,
        CONSTANT,
        STRUCT,
        ENUM,
        ENUM_VALUE,
        TYPE,
        NAMESPACE,
        VARIABLE_LIST,
        DATA_BLOCK,
        CONFIGURATION,
        TASK
    }

    /** One outline entry: {@code range} covers the whole declaration, {@code selection} its name. */
    public record Item(String name, Kind kind, String detail, Span range, Span selection, List<Item> children) {
        public Item {
            children = List.copyOf(children);
        }
    }

    /** A workspace symbol: {@code container} is the owning POU or list, or null. */
    public record Symbol(String name, Kind kind, String container, Span selection) {}

    private final Snapshot snapshot;

    public Outline(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** The outline of {@code path}: POUs with their variables and members, types, variable lists, configurations. */
    public List<Item> document(String path) {
        SourceUnit unit = snapshot.file(path).map(f -> f.unit()).orElse(null);
        if (unit == null) {
            return List.of();
        }
        List<Item> out = new ArrayList<>();
        unit.declarations().forEach(d -> declaration(d, out));
        return out;
    }

    private void declaration(Declaration d, List<Item> out) {
        switch (d) {
            case Declaration.Pou p -> out.add(pou(p));
            case Declaration.TypeDecl t -> {
                List<Item> children = new ArrayList<>();
                Kind kind = Kind.TYPE;
                switch (t.type()) {
                    case TypeRef.StructType s -> {
                        kind = Kind.STRUCT;
                        s.members().forEach(m -> children.add(variable(m, null)));
                    }
                    case TypeRef.UnionType u -> {
                        kind = Kind.STRUCT;
                        u.members().forEach(m -> children.add(variable(m, null)));
                    }
                    case TypeRef.EnumType e -> {
                        kind = Kind.ENUM;
                        e.values()
                                .forEach(v -> children.add(new Item(
                                        v.name().name(),
                                        Kind.ENUM_VALUE,
                                        "",
                                        v.span(),
                                        v.name().span(),
                                        List.of())));
                    }
                    default -> {}
                }
                out.add(new Item(
                        t.name().name(),
                        kind,
                        kind == Kind.TYPE && t.type() != null ? typeText(t.type()) : "",
                        t.span(),
                        t.name().span(),
                        children));
            }
            case Declaration.DataBlock b -> {
                List<Item> children = new ArrayList<>();
                b.varBlocks().forEach(vb -> vb.variables().forEach(v -> children.add(variable(v, vb))));
                if (b.layout() instanceof TypeRef.StructType s) {
                    s.members().forEach(m -> children.add(variable(m, null)));
                }
                String detail =
                        b.basedOn() == null ? "" : b.basedOn().parts().getLast().name();
                out.add(new Item(
                        b.name().name(),
                        Kind.DATA_BLOCK,
                        detail,
                        b.span(),
                        b.name().span(),
                        children));
            }
            case Declaration.GlobalVars g -> {
                List<Item> vars = new ArrayList<>();
                g.block().variables().forEach(v -> vars.add(variable(v, g.block())));
                if (g.listName() != null) {
                    out.add(new Item(
                            g.listName().name(),
                            Kind.VARIABLE_LIST,
                            "",
                            g.span(),
                            g.listName().span(),
                            vars));
                } else {
                    out.addAll(vars);
                }
            }
            case Declaration.Namespace n -> {
                List<Item> members = new ArrayList<>();
                n.members().forEach(m -> declaration(m, members));
                Identifier last = n.name().parts().getLast();
                out.add(new Item(
                        n.name().parts().stream()
                                .map(Identifier::name)
                                .collect(java.util.stream.Collectors.joining(".")),
                        Kind.NAMESPACE,
                        "",
                        n.span(),
                        last.span(),
                        members));
            }
            case Declaration.Configuration c -> {
                List<Item> children = new ArrayList<>();
                c.varBlocks().forEach(vb -> vb.variables().forEach(v -> children.add(variable(v, vb))));
                c.elements().forEach(e -> configElement(e, children));
                out.add(new Item(
                        c.name().name(),
                        Kind.CONFIGURATION,
                        "",
                        c.span(),
                        c.name().span(),
                        children));
            }
            case Declaration.Using u -> {}
        }
    }

    private void configElement(Declaration.ConfigElement e, List<Item> out) {
        switch (e) {
            case Declaration.Resource r -> {
                List<Item> children = new ArrayList<>();
                r.varBlocks().forEach(vb -> vb.variables().forEach(v -> children.add(variable(v, vb))));
                r.elements().forEach(x -> configElement(x, children));
                out.add(new Item(
                        r.name().name(),
                        Kind.CONFIGURATION,
                        "RESOURCE",
                        r.span(),
                        r.name().span(),
                        children));
            }
            case Declaration.Task t ->
                out.add(new Item(
                        t.name().name(), Kind.TASK, "TASK", t.span(), t.name().span(), List.of()));
            case Declaration.ProgramInstance p ->
                out.add(new Item(
                        p.name().name(),
                        Kind.VARIABLE,
                        p.type() == null ? "" : p.type().parts().getLast().name(),
                        p.span(),
                        p.name().span(),
                        List.of()));
        }
    }

    private Item pou(Declaration.Pou p) {
        List<Item> children = new ArrayList<>();
        for (VarBlock vb : p.varBlocks()) {
            vb.variables().forEach(v -> children.add(variable(v, vb)));
        }
        p.members().forEach(m -> children.add(pou(m)));
        String detail = p.returnType() == null ? "" : typeText(p.returnType());
        return new Item(
                p.name().name(), kind(p.kind()), detail, p.span(), p.name().span(), children);
    }

    private Item variable(VarDecl v, VarBlock block) {
        boolean constant = block != null && block.is(VarBlock.Qualifier.CONSTANT);
        String section = block == null || block.section() == VarBlock.Section.VAR
                ? ""
                : block.section().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " ";
        String type = v.type() == null ? "" : typeText(v.type());
        return new Item(
                v.name().name(),
                constant ? Kind.CONSTANT : Kind.VARIABLE,
                (section + type).strip(),
                v.span(),
                v.name().span(),
                List.of());
    }

    /**
     * Symbols of every model whose name contains the query's characters in order, case-insensitively ({@code fbm} finds
     * {@code FB_Motor}); at most {@code limit}.
     */
    public List<Symbol> workspace(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Symbol> out = new ArrayList<>();
        for (Snapshot.Group g : snapshot.groups()) {
            SemanticModel m = g.model();
            for (PouSymbol p : m.pous()) {
                if (!p.isBuiltin() && p.declaration() != null && matches(p.name(), q)) {
                    String container = p.owner() == null ? null : p.owner().qualifiedName();
                    out.add(new Symbol(
                            p.name(),
                            kind(p.kind()),
                            container,
                            p.declaration().name().span()));
                }
            }
            for (TypeSymbol t : m.global().types()) {
                Identifier name = t.declaration() == null ? null : Navigator.nameOf(t.declaration());
                if (name != null && matches(t.name(), q)) {
                    Kind k = switch (t.kind()) {
                        case STRUCT, UNION -> Kind.STRUCT;
                        case ENUM -> Kind.ENUM;
                        default -> Kind.TYPE;
                    };
                    out.add(new Symbol(t.name(), k, null, name.span()));
                }
            }
            for (GvlSymbol gvl : m.global().gvls()) {
                for (VariableSymbol v : gvl.variables().values()) {
                    if (v.declaration() != null
                            && v.declaration().name().form() != Identifier.Form.SYNTHETIC
                            && matches(v.name(), q)) {
                        out.add(new Symbol(
                                v.name(),
                                v.isConstant() ? Kind.CONSTANT : Kind.VARIABLE,
                                gvl.isNamed() ? gvl.name() : null,
                                v.declaration().name().span()));
                    }
                }
            }
        }
        out.sort(java.util.Comparator.comparing(
                        (Symbol s) -> !s.name().toLowerCase(Locale.ROOT).startsWith(q))
                .thenComparing(s -> s.name().toLowerCase(Locale.ROOT)));
        return out.size() <= limit ? out : out.subList(0, limit);
    }

    static boolean matches(String name, String query) {
        String n = name.toLowerCase(Locale.ROOT);
        int i = 0;
        for (int j = 0; j < n.length() && i < query.length(); j++) {
            if (n.charAt(j) == query.charAt(i)) {
                i++;
            }
        }
        return i == query.length();
    }

    private static Kind kind(Declaration.PouKind k) {
        return switch (k) {
            case PROGRAM, ORGANIZATION_BLOCK -> Kind.PROGRAM;
            case FUNCTION -> Kind.FUNCTION;
            case FUNCTION_BLOCK -> Kind.FUNCTION_BLOCK;
            case CLASS -> Kind.CLASS;
            case INTERFACE -> Kind.INTERFACE;
            case METHOD -> Kind.METHOD;
            case PROPERTY, PROPERTY_GET, PROPERTY_SET -> Kind.PROPERTY;
            case ACTION, TRANSITION -> Kind.ACTION;
            case ROUTINE -> Kind.ROUTINE;
        };
    }

    /** The type as written; for XML exports (PLCopen {@code <type>} elements) a short form built from the AST. */
    private String typeText(TypeRef t) {
        String s = oneLine(t.span());
        if (!s.startsWith("<")) {
            return s;
        }
        return switch (t) {
            case TypeRef.Named n ->
                n.name().parts().stream().map(Identifier::name).collect(java.util.stream.Collectors.joining("."));
            case TypeRef.ArrayType a -> "ARRAY OF " + typeText(a.element());
            case TypeRef.PointerType p -> p.kind().name().replace('_', ' ') + " TO " + typeText(p.target());
            case TypeRef.StringType st -> st.wide() ? "WSTRING" : "STRING";
            case TypeRef.StructType st -> "STRUCT";
            case TypeRef.UnionType u -> "UNION";
            case TypeRef.EnumType e -> "ENUM";
            case TypeRef.Invalid i -> "";
        };
    }

    private String oneLine(Span span) {
        SourceText t = snapshot.registry().text(span.textId());
        String s = t.text().substring(span.start(), span.end()).strip().replaceAll("\\s+", " ");
        return s.length() <= 80 ? s : s.substring(0, 80) + " …";
    }
}
