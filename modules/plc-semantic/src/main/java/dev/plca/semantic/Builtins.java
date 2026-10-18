package dev.plca.semantic;

import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.TypeRef.PointerKind;
import dev.plca.ast.VarBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IEC 61131-3 standard function blocks and functions, and CODESYS operators, as far as the semantic model needs them:
 * parameter names/sections/types of the standard FBs, result-type rules of functions. Library POUs beyond these (Tc2_*,
 * MC_*) are not modelled; they resolve to {@link LibrarySymbol}.
 *
 * <p>Counter parameters use {@code WORD} as in the CODESYS Standard library and Tc2_Standard (IEC uses INT).
 */
final class Builtins {

    private final Map<String, Symbol> symbols = new HashMap<>();

    private static final Pattern CONVERSION = Pattern.compile("(?i)^(?:([A-Z_]+)_)?TO_([A-Z_]+)$");
    private static final Set<String> LIKE_FIRST = Set.of(
            "ABS", "SQRT", "LN", "LOG", "EXP", "SIN", "COS", "TAN", "ASIN", "ACOS", "ATAN", "ATAN2", "EXPT", "MOVE",
            "SHL", "SHR", "ROL", "ROR", "ADD", "SUB", "MUL", "DIV", "MOD", "AND", "OR", "XOR", "NOT", "MAX", "MIN",
            "LEFT", "RIGHT", "MID", "CONCAT", "INSERT", "DELETE", "REPLACE");
    private static final Set<String> BOOL_RESULT =
            Set.of("EQ", "NE", "GT", "GE", "LT", "LE", "__QUERYINTERFACE", "__QUERYPOINTER", "__ISVALIDREF");

    Builtins() {
        timer("TON", ElementaryKind.TIME);
        timer("TOF", ElementaryKind.TIME);
        timer("TP", ElementaryKind.TIME);
        timer("LTON", ElementaryKind.LTIME);
        timer("LTOF", ElementaryKind.LTIME);
        timer("LTP", ElementaryKind.LTIME);
        fb("R_TRIG", List.of(in("CLK", ElementaryKind.BOOL)), List.of(out("Q", ElementaryKind.BOOL)));
        fb("F_TRIG", List.of(in("CLK", ElementaryKind.BOOL)), List.of(out("Q", ElementaryKind.BOOL)));
        fb(
                "CTU",
                List.of(in("CU", ElementaryKind.BOOL), in("RESET", ElementaryKind.BOOL), in("PV", ElementaryKind.WORD)),
                List.of(out("Q", ElementaryKind.BOOL), out("CV", ElementaryKind.WORD)));
        fb(
                "CTD",
                List.of(in("CD", ElementaryKind.BOOL), in("LOAD", ElementaryKind.BOOL), in("PV", ElementaryKind.WORD)),
                List.of(out("Q", ElementaryKind.BOOL), out("CV", ElementaryKind.WORD)));
        fb(
                "CTUD",
                List.of(
                        in("CU", ElementaryKind.BOOL),
                        in("CD", ElementaryKind.BOOL),
                        in("RESET", ElementaryKind.BOOL),
                        in("LOAD", ElementaryKind.BOOL),
                        in("PV", ElementaryKind.WORD)),
                List.of(
                        out("QU", ElementaryKind.BOOL),
                        out("QD", ElementaryKind.BOOL),
                        out("CV", ElementaryKind.WORD)));
        fb(
                "SR",
                List.of(in("SET1", ElementaryKind.BOOL), in("RESET", ElementaryKind.BOOL)),
                List.of(out("Q1", ElementaryKind.BOOL)));
        fb(
                "RS",
                List.of(in("SET", ElementaryKind.BOOL), in("RESET1", ElementaryKind.BOOL)),
                List.of(out("Q1", ElementaryKind.BOOL)));

        for (String f : LIKE_FIRST) {
            function(f, args -> like(args, 0));
        }
        for (String f : BOOL_RESULT) {
            function(f, args -> Type.BOOL);
        }
        function("LIMIT", args -> like(args, 1));
        function("SEL", args -> like(args, 1));
        function("MUX", args -> like(args, 1));
        function("LEN", args -> Type.of(ElementaryKind.INT));
        function("FIND", args -> Type.of(ElementaryKind.INT));
        function("TRUNC", args -> Type.of(ElementaryKind.DINT));
        function("TRUNC_INT", args -> Type.of(ElementaryKind.INT));
        function("SIZEOF", args -> Type.of(ElementaryKind.UDINT));
        function("INDEXOF", args -> Type.of(ElementaryKind.INT));
        function("BITADR", args -> Type.of(ElementaryKind.DWORD));
        function("UPPER_BOUND", args -> Type.of(ElementaryKind.DINT));
        function("LOWER_BOUND", args -> Type.of(ElementaryKind.DINT));
        function("ADR", args -> new Type.Pointer(PointerKind.POINTER, concrete(arg(args, 0))));
        function("REF", args -> new Type.Pointer(PointerKind.REF_TO, concrete(arg(args, 0))));
        function("__NEW", args -> new Type.Pointer(PointerKind.POINTER, concrete(arg(args, 0))));
        function("__DELETE", args -> Type.VOID);
    }

    /** A builtin FB or function by name, including conversion functions ({@code INT_TO_REAL}, {@code TO_DINT}). */
    Optional<Symbol> lookup(String name) {
        String key = PouSymbol.key(name);
        Symbol s = symbols.get(key);
        if (s != null) {
            return Optional.of(s);
        }
        Matcher m = CONVERSION.matcher(name);
        if (m.matches()) {
            Optional<Type> target = conversionTarget(m.group(2));
            if (target.isPresent()
                    && (m.group(1) == null || conversionTarget(m.group(1)).isPresent())) {
                Type t = target.get();
                BuiltinFunction f = new BuiltinFunction(name, args -> t);
                symbols.put(key, f);
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    private static Optional<Type> conversionTarget(String name) {
        String n = name.toUpperCase(java.util.Locale.ROOT);
        if (n.equals("STRING")) {
            return Optional.of(new Type.StringType(false, 255));
        }
        if (n.equals("WSTRING")) {
            return Optional.of(new Type.StringType(true, 255));
        }
        return ElementaryKind.byName(n).map(Type::of);
    }

    private static Type arg(List<Type> args, int i) {
        return i < args.size() ? args.get(i) : Type.UNKNOWN;
    }

    private static Type like(List<Type> args, int i) {
        return concrete(arg(args, i));
    }

    /** Untyped literals become their default type (DINT/LINT, LREAL) when they determine a result. */
    static Type concrete(Type t) {
        if (t instanceof Type.IntLiteral l) {
            return Type.of(
                    l.value() >= Integer.MIN_VALUE && l.value() <= Integer.MAX_VALUE
                            ? ElementaryKind.DINT
                            : ElementaryKind.LINT);
        }
        if (t instanceof Type.RealLiteral) {
            return Type.of(ElementaryKind.LREAL);
        }
        return t;
    }

    private void function(String name, Function<List<Type>, Type> result) {
        symbols.put(PouSymbol.key(name), new BuiltinFunction(name, result));
    }

    private record Param(String name, VarBlock.Section section, ElementaryKind kind) {}

    private static Param in(String name, ElementaryKind k) {
        return new Param(name, VarBlock.Section.INPUT, k);
    }

    private static Param out(String name, ElementaryKind k) {
        return new Param(name, VarBlock.Section.OUTPUT, k);
    }

    private void timer(String name, ElementaryKind time) {
        fb(
                name,
                List.of(in("IN", ElementaryKind.BOOL), in("PT", time)),
                List.of(out("Q", ElementaryKind.BOOL), out("ET", time)));
    }

    private void fb(String name, List<Param> inputs, List<Param> outputs) {
        PouSymbol fb = new PouSymbol(PouKind.FUNCTION_BLOCK, name, null, null, null, true);
        for (List<Param> ps : List.of(inputs, outputs)) {
            for (Param p : ps) {
                VariableSymbol v = new VariableSymbol(p.name(), null, null, p.section(), fb, false);
                v.setType(Type.of(p.kind()));
                fb.addVariable(v);
            }
        }
        symbols.put(PouSymbol.key(name), fb);
    }
}
