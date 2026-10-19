package dev.plca.rules.quality;

import dev.plca.ast.Declaration;
import dev.plca.ast.Expression;
import dev.plca.ast.Pragma;
import dev.plca.ast.Statement;
import dev.plca.ast.TypeRef;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import dev.plca.core.Dialect;
import dev.plca.rules.ProjectContext;
import dev.plca.rules.ProjectRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.GvlSymbol;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Statements;
import dev.plca.semantic.VariableSymbol;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * PLCA-Q001 (PLCopen CP24): a local variable (VAR, VAR_TEMP, VAR_STAT, VAR_INST) — and optionally a global one — that
 * no body, initial value or type declaration refers to.
 *
 * <p>Errs towards silence: variables with an attribute pragma (HMI/OPC UA/ADS exposure), an {@code AT} address (I/O
 * mapping) or a spare-variable name are exempt, and so is every variable of a POU whose code we could not fully read
 * (graphical bodies, syntax errors), since the missing code may use it. Names used in declarations (array bounds,
 * FB_init arguments) count as uses by name.
 *
 * <p>Siemens: static variables of an FB live in its instance DB, which HMIs and other PLCs can read and write unless
 * the declaration says {@code ExternalAccessible := 'False'} (TIA writes the attribute only when it differs from the
 * default). So statics are reported only when hidden that way; temporaries and constants always. Siemens attributes are
 * therefore no exposure exemption, and data blocks are never reported (they exist to be accessed from outside).
 */
public final class UnusedVariable implements ProjectRule {

    private static final Set<VarBlock.Section> LOCAL_SECTIONS =
            EnumSet.of(VarBlock.Section.VAR, VarBlock.Section.TEMP, VarBlock.Section.STAT, VarBlock.Section.INST);

    @Override
    public String id() {
        return "PLCA-Q001";
    }

    @Override
    public void check(ProjectContext ctx) {
        SemanticModel m = ctx.model();
        Pattern spare = ctx.patternParam("sparePattern");

        Set<VariableSymbol> used = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> namesInDeclarations = new HashSet<>();
        Set<PouSymbol> opaque = Collections.newSetFromMap(new IdentityHashMap<>());
        Consumer<Expression> markUsed = e -> {
            if (m.symbol(e).orElse(null) instanceof VariableSymbol v) {
                used.add(v);
            }
        };

        for (PouSymbol p : m.pous()) {
            Declaration.Pou d = p.declaration();
            if (d == null) {
                continue;
            }
            boolean[] invalid = {d.body() == null && d.bodyLanguage() != null};
            Statements.forEachDeep(d.body(), st -> {
                invalid[0] |= st instanceof Statement.Invalid;
                Walk.ownExpressions(
                        st,
                        top -> Walk.deep(top, e -> {
                            invalid[0] |= e instanceof Expression.Invalid;
                            markUsed.accept(e);
                        }));
            });
            if (invalid[0]) {
                opaque.add(p.instanceOwner() == null ? p : p.instanceOwner());
            }
            for (VarBlock b : d.varBlocks()) {
                for (VarDecl v : b.variables()) {
                    Walk.expressions(v.init(), markUsed);
                    typeNames(v.type(), namesInDeclarations);
                }
            }
        }
        for (GvlSymbol g : m.global().gvls()) {
            for (VariableSymbol v : g.variables().values()) {
                Walk.expressions(v.initializer(), markUsed);
                if (v.declaration() != null) {
                    typeNames(v.declaration().type(), namesInDeclarations);
                }
            }
        }
        m.units().forEach(u -> u.declarations().forEach(d -> typeDeclarationNames(d, namesInDeclarations)));

        for (PouSymbol p : m.pous()) {
            Declaration.Pou d = p.declaration();
            if (d == null
                    || p.kind() == Declaration.PouKind.INTERFACE
                    || p.has(Declaration.Modifier.ABSTRACT)
                    || opaque.contains(p)
                    || opaque.contains(p.instanceOwner())
                    || d.body() == null && p.members().isEmpty()) {
                continue;
            }
            boolean siemens = p.dialect() == Dialect.SIEMENS;
            for (VariableSymbol v : p.variables().values()) {
                if (siemens && reachableFromOutside(v)) {
                    continue;
                }
                if (LOCAL_SECTIONS.contains(v.section()) && candidate(v, used, namesInDeclarations, spare, siemens)) {
                    ctx.report(
                            p,
                            v.declaration().name().span(),
                            "Variable '" + v.name() + "' is declared but never used",
                            List.of());
                }
            }
        }

        if (ctx.boolParam("includeGlobals") && opaque.isEmpty()) {
            for (GvlSymbol g : m.global().gvls()) {
                if (g.isDataBlocks()) {
                    continue;
                }
                for (VariableSymbol v : g.variables().values()) {
                    if (candidate(v, used, namesInDeclarations, spare, false)) {
                        ctx.report(
                                (PouSymbol) null,
                                v.declaration().name().span(),
                                "Global variable '" + v.name() + "' is declared but never used",
                                List.of());
                    }
                }
            }
        }
    }

    /**
     * A Siemens static that an HMI or another PLC may access: not hidden with {@code ExternalAccessible := 'False'}.
     */
    private static boolean reachableFromOutside(VariableSymbol v) {
        boolean isStatic = v.section() == VarBlock.Section.VAR || v.section() == VarBlock.Section.STAT;
        return isStatic
                && !v.isConstant()
                && v.pragmas().stream()
                        .noneMatch(p -> p.isAttribute("ExternalAccessible") && "false".equalsIgnoreCase(p.value()));
    }

    private static boolean candidate(
            VariableSymbol v,
            Set<VariableSymbol> used,
            Set<String> namesInDeclarations,
            Pattern spare,
            boolean siemens) {
        return v.declaration() != null
                && !v.isResult()
                && !used.contains(v)
                && v.address() == null
                && (siemens || v.pragmas().stream().noneMatch(p -> p.kind() == Pragma.Kind.ATTRIBUTE))
                && !namesInDeclarations.contains(v.name().toLowerCase(Locale.ROOT))
                && !spare.matcher(v.name()).find();
    }

    private static void typeDeclarationNames(Declaration d, Set<String> names) {
        switch (d) {
            case Declaration.TypeDecl t -> {
                typeNames(t.type(), names);
                Walk.expressions(t.init(), e -> exprNames(e, names));
            }
            case Declaration.Namespace n -> n.members().forEach(x -> typeDeclarationNames(x, names));
            case Declaration.DataBlock db -> {
                typeNames(db.layout(), names);
                db.varBlocks().forEach(b -> b.variables().forEach(v -> typeNames(v.type(), names)));
                db.initialValues().forEach(s -> Walk.ownExpressions(s, e -> exprNames(e, names)));
            }
            default -> {}
        }
    }

    /** Names used inside a type: bounds, lengths, subranges, generic and FB_init arguments, member initial values. */
    private static void typeNames(TypeRef t, Set<String> names) {
        Consumer<Expression> add = e -> exprNames(e, names);
        switch (t) {
            case null -> {}
            case TypeRef.Named n -> {
                n.genericArguments().forEach(x -> Walk.deep(x, add));
                Walk.deep(n.rangeLow(), add);
                Walk.deep(n.rangeHigh(), add);
                if (n.init() != null) {
                    n.init().arguments().forEach(a -> Walk.deep(a.value(), add));
                }
            }
            case TypeRef.StringType s -> Walk.deep(s.length(), add);
            case TypeRef.ArrayType a -> {
                a.dimensions().forEach(dim -> {
                    Walk.deep(dim.low(), add);
                    Walk.deep(dim.high(), add);
                });
                a.elementInits().forEach(ci -> ci.arguments().forEach(arg -> Walk.deep(arg.value(), add)));
                typeNames(a.element(), names);
            }
            case TypeRef.PointerType p -> typeNames(p.target(), names);
            case TypeRef.StructType s ->
                s.members().forEach(v -> {
                    typeNames(v.type(), names);
                    Walk.expressions(v.init(), add);
                });
            case TypeRef.UnionType u -> u.members().forEach(v -> typeNames(v.type(), names));
            case TypeRef.EnumType e -> e.values().forEach(v -> Walk.deep(v.value(), add));
            case TypeRef.Invalid i -> {}
        }
    }

    private static void exprNames(Expression e, Set<String> names) {
        switch (e) {
            case Expression.NameRef n -> names.add(n.name().key());
            case Expression.MemberAccess ma -> names.add(ma.member().key());
            default -> {}
        }
    }
}
