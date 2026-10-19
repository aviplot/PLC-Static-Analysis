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
 *
 * <p>Siemens S7-1200/1500 (TIA "Timer operations", "Counter operations"): typed timers ({@code TON_TIME}, …) and
 * counters ({@code CTU_INT}, …) with TIA's parameter names ({@code R}, {@code LD}), and the instance types
 * {@code IEC_TIMER}/{@code IEC_COUNTER} whose {@code "T_DB".TON(…)} calls are modelled as methods. TODO(verify-format):
 * the exact member lists of IEC_TIMER/IEC_COUNTER against the TIA help.
 */
final class Builtins {

    private final Map<String, Symbol> symbols = new HashMap<>();

    private static final Pattern CONVERSION = Pattern.compile("(?i)^(?:([A-Z][A-Z0-9_]*)_)?TO_([A-Z][A-Z0-9_]*)$");
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

        siemens();

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

    private static final List<ElementaryKind> SIEMENS_COUNTER_TYPES = List.of(
            ElementaryKind.SINT,
            ElementaryKind.INT,
            ElementaryKind.DINT,
            ElementaryKind.LINT,
            ElementaryKind.USINT,
            ElementaryKind.UINT,
            ElementaryKind.UDINT,
            ElementaryKind.ULINT);

    private void siemens() {
        // instances of the typed timers accept the timer calls too: `#t.TON(IN := …, PT := …)` on a TON_TIME
        for (String t : List.of("TON", "TOF", "TP")) {
            timerMethods(timer(t + "_TIME", ElementaryKind.TIME), ElementaryKind.TIME);
            timerMethods(timer(t + "_LTIME", ElementaryKind.LTIME), ElementaryKind.LTIME);
        }
        timerMethods(tonr("TONR_TIME", ElementaryKind.TIME), ElementaryKind.TIME);
        timerMethods(tonr("TONR_LTIME", ElementaryKind.LTIME), ElementaryKind.LTIME);
        timerType("IEC_TIMER", ElementaryKind.TIME);
        timerType("IEC_LTIMER", ElementaryKind.LTIME);
        for (ElementaryKind k : SIEMENS_COUNTER_TYPES) {
            String suffix = k.name();
            fb("CTU_" + suffix, ctuIn(k), List.of(out("Q", ElementaryKind.BOOL), out("CV", k)));
            fb("CTD_" + suffix, ctdIn(k), List.of(out("Q", ElementaryKind.BOOL), out("CV", k)));
            fb("CTUD_" + suffix, ctudIn(k), ctudOut(k));
        }
        counterType("IEC_SCOUNTER", ElementaryKind.SINT);
        counterType("IEC_COUNTER", ElementaryKind.INT);
        counterType("IEC_DCOUNTER", ElementaryKind.DINT);
        counterType("IEC_LCOUNTER", ElementaryKind.LINT);
        counterType("IEC_USCOUNTER", ElementaryKind.USINT);
        counterType("IEC_UCOUNTER", ElementaryKind.UINT);
        counterType("IEC_UDCOUNTER", ElementaryKind.UDINT);
        counterType("IEC_ULCOUNTER", ElementaryKind.ULINT);

        for (String f : List.of("MOVE_BLK", "UMOVE_BLK", "FILL_BLK", "UFILL_BLK", "SWAP")) {
            function(f, args -> f.equals("SWAP") ? like(args, 0) : Type.VOID);
        }
        for (String f : List.of(
                "MOVE_BLK_VARIANT",
                "Serialize",
                "Deserialize",
                "RD_SYS_T",
                "RD_LOC_T",
                "WR_SYS_T",
                "WR_LOC_T",
                "DPRD_DAT",
                "DPWR_DAT",
                "GETIO",
                "SETIO",
                "GET_ERR_ID",
                "CountOfElements")) {
            function(f, args -> Type.of(ElementaryKind.INT));
        }
        // the most widespread unresolved names of the corpus check (TIA "Basic instructions" / "Extended
        // instructions"); string conversions and block moves report through outputs, so their result is VOID
        for (String f : List.of(
                "VAL_STRG",
                "STRG_VAL",
                "CHARS_TO_STRG",
                "STRG_TO_CHARS",
                "RESET_TIMER",
                "PRESET_TIMER",
                "INC",
                "DEC",
                "S_CONV",
                "T_CONV",
                "SCATTER_BLK",
                "GATHER_BLK")) {
            function(f, args -> Type.VOID);
        }
        for (String f : List.of(
                "RD_SINFO",
                "RT_INFO",
                "READ_CLK",
                "SET_CLK",
                "BLKMOV",
                "UBLKMOV",
                "FILL",
                "SCATTER",
                "GATHER",
                "GEN_DIAG",
                "GET_DIAG",
                "LED",
                "Get_IM_Data",
                "DeviceStates",
                "ModuleStates",
                "QRY_CINT",
                "SET_CINT",
                "CAN_TINT",
                "ACT_TINT",
                "SET_TINT",
                "QRY_TINT",
                "SRT_DINT",
                "CAN_DINT",
                "DIS_AIRT",
                "EN_AIRT",
                "RE_TRIGR",
                "STP",
                "WR_USMSG",
                "LOG2GEO",
                "GEO2LOG")) {
            function(f, args -> Type.of(ElementaryKind.INT));
        }
        for (String f : List.of("IS_ARRAY", "IS_NULL", "NOT_NULL", "IN_RANGE", "OUT_RANGE")) {
            function(f, args -> Type.BOOL);
        }
        for (String f : List.of("ROUND", "CEIL", "FLOOR")) {
            function(f, args -> Type.of(ElementaryKind.DINT));
        }
        for (String f : List.of("SQR", "FRAC", "INV", "DECO", "ENCO")) {
            function(f, args -> like(args, 0));
        }
        function("TIME_TCK", args -> Type.of(ElementaryKind.TIME));
        function("VariantGet", args -> Type.VOID);
        function("VariantPut", args -> Type.VOID);
        // classic STEP 7 (SFC 83/84 and indirect DB access); fuller classic support is an M4 follow-up
        function("READ_DBL", args -> Type.of(ElementaryKind.INT));
        function("WRIT_DBL", args -> Type.of(ElementaryKind.INT));
        function("T_ADD", args -> like(args, 0));
        function("T_SUB", args -> like(args, 0));
        function("T_DIFF", args -> Type.of(ElementaryKind.TIME));
        function("T_COMBINE", args -> Type.of(ElementaryKind.DATE_AND_TIME));
        // type queries compare with type names: TypeOf(#v) = Real
        function("TypeOf", args -> Type.UNKNOWN);
        function("TypeOfElements", args -> Type.UNKNOWN);
        function("TypeOfDB", args -> Type.UNKNOWN);
        function("NULL", args -> Type.UNKNOWN);
        function("NORM_X", args -> Type.of(ElementaryKind.REAL));
        function("SCALE_X", args -> like(args, 0));
        function("RUNTIME", args -> Type.of(ElementaryKind.LREAL));
        function("PEEK", args -> Type.of(ElementaryKind.BYTE));
        function("PEEK_BOOL", args -> Type.BOOL);
        function("POKE", args -> Type.VOID);
        function("POKE_BOOL", args -> Type.VOID);
    }

    /** {@code TON}/{@code TOF}/{@code TP}/{@code TONR} called on a timer instance. */
    private static void timerMethods(PouSymbol t, ElementaryKind time) {
        for (String m : List.of("TON", "TOF", "TP")) {
            method(
                    t,
                    m,
                    List.of(in("IN", ElementaryKind.BOOL), in("PT", time)),
                    List.of(out("Q", ElementaryKind.BOOL), out("ET", time)));
        }
        method(
                t,
                "TONR",
                List.of(in("IN", ElementaryKind.BOOL), in("R", ElementaryKind.BOOL), in("PT", time)),
                List.of(out("Q", ElementaryKind.BOOL), out("ET", time)));
    }

    private static List<Param> ctuIn(ElementaryKind k) {
        return List.of(in("CU", ElementaryKind.BOOL), in("R", ElementaryKind.BOOL), in("PV", k));
    }

    private static List<Param> ctdIn(ElementaryKind k) {
        return List.of(in("CD", ElementaryKind.BOOL), in("LD", ElementaryKind.BOOL), in("PV", k));
    }

    private static List<Param> ctudIn(ElementaryKind k) {
        return List.of(
                in("CU", ElementaryKind.BOOL),
                in("CD", ElementaryKind.BOOL),
                in("R", ElementaryKind.BOOL),
                in("LD", ElementaryKind.BOOL),
                in("PV", k));
    }

    private static List<Param> ctudOut(ElementaryKind k) {
        return List.of(out("QU", ElementaryKind.BOOL), out("QD", ElementaryKind.BOOL), out("CV", k));
    }

    private PouSymbol tonr(String name, ElementaryKind time) {
        return fb(
                name,
                List.of(in("IN", ElementaryKind.BOOL), in("R", ElementaryKind.BOOL), in("PT", time)),
                List.of(out("Q", ElementaryKind.BOOL), out("ET", time)));
    }

    /** {@code IEC_TIMER}: the instance data; {@code "T_DB".TON(IN := …, PT := …)} calls a timer function on it. */
    private void timerType(String name, ElementaryKind time) {
        timerMethods(
                fb(
                        name,
                        List.of(in("IN", ElementaryKind.BOOL), in("PT", time)),
                        List.of(out("Q", ElementaryKind.BOOL), out("ET", time))),
                time);
    }

    /** {@code IEC_COUNTER}: instance data with {@code CTU}/{@code CTD}/{@code CTUD} calls on it. */
    private void counterType(String name, ElementaryKind k) {
        PouSymbol c = fb(name, ctudIn(k), ctudOut(k));
        method(c, "CTU", ctuIn(k), List.of(out("Q", ElementaryKind.BOOL), out("CV", k)));
        method(c, "CTD", ctdIn(k), List.of(out("Q", ElementaryKind.BOOL), out("CV", k)));
        method(c, "CTUD", ctudIn(k), ctudOut(k));
    }

    private static void method(PouSymbol owner, String name, List<Param> inputs, List<Param> outputs) {
        PouSymbol m = new PouSymbol(PouKind.METHOD, name, null, owner, null, true);
        addParams(m, inputs, outputs);
        owner.addMember(m);
    }

    /** A builtin FB or function by name, including conversion functions ({@code INT_TO_REAL}, {@code TO_DINT}). */
    Optional<Symbol> lookup(String name) {
        String key = PouSymbol.key(name);
        Symbol s = symbols.get(key);
        if (s != null) {
            return Optional.of(s);
        }
        Optional<Symbol> typed = typedVariant(name);
        if (typed.isPresent()) {
            return typed;
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

    /**
     * TIA writes generic instructions with an explicit type: {@code SCALE_X_REAL}, {@code SHR_DWORD}, {@code ABS_DINT}
     * are {@code SCALE_X}, {@code SHR}, {@code ABS} for that type.
     */
    private Optional<Symbol> typedVariant(String name) {
        int u = name.lastIndexOf('_');
        if (u <= 0 || ElementaryKind.byName(name.substring(u + 1)).isEmpty()) {
            return Optional.empty();
        }
        Symbol base = symbols.get(PouSymbol.key(name.substring(0, u)));
        return base instanceof BuiltinFunction ? Optional.of(base) : Optional.empty();
    }

    /** Siemens conversion endpoints that are not elementary IEC types ({@code DTL_TO_TOD}, {@code BCD16_TO_INT}). */
    private static final Set<String> OTHER_CONVERSION_TYPES =
            Set.of("DTL", "BCD", "BCD16", "BCD32", "BCD64", "S5TIME", "VARIANT");

    private static Optional<Type> conversionTarget(String name) {
        String n = name.toUpperCase(java.util.Locale.ROOT);
        if (OTHER_CONVERSION_TYPES.contains(n)) {
            return Optional.of(Type.UNKNOWN);
        }
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

    private PouSymbol timer(String name, ElementaryKind time) {
        return fb(
                name,
                List.of(in("IN", ElementaryKind.BOOL), in("PT", time)),
                List.of(out("Q", ElementaryKind.BOOL), out("ET", time)));
    }

    private PouSymbol fb(String name, List<Param> inputs, List<Param> outputs) {
        PouSymbol fb = new PouSymbol(PouKind.FUNCTION_BLOCK, name, null, null, null, true);
        addParams(fb, inputs, outputs);
        symbols.put(PouSymbol.key(name), fb);
        return fb;
    }

    private static void addParams(PouSymbol pou, List<Param> inputs, List<Param> outputs) {
        for (List<Param> ps : List.of(inputs, outputs)) {
            for (Param p : ps) {
                VariableSymbol v = new VariableSymbol(p.name(), null, null, p.section(), pou, false);
                v.setType(Type.of(p.kind()));
                pou.addVariable(v);
            }
        }
    }
}
