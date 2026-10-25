package dev.plca.rules.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.ast.Expression;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.StFrontend;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticBuilder;
import dev.plca.semantic.SemanticModel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.IntegerFormulaManager;
import org.sosy_lab.java_smt.api.NumeralFormula;
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.RationalFormulaManager;
import org.sosy_lab.java_smt.api.SolverContext;

/**
 * The condition reasoner against an SMT solver (SMTInterpol through JavaSMT, test scope only). Random ST conditions
 * over typed variables are parsed with the real frontend; each one goes through {@link Normalizer} and
 * {@link Reasoner}, and independently from its AST into the solver. The reasoner must never call a satisfiable
 * condition {@code UNSAT} (that would be a false finding) and never call an unsatisfiable one {@code SAT} (L011 relies
 * on that). A disagreement prints the seed and the condition as ST.
 *
 * <p>{@code -Plogic.iterations=N} (default 10,000) and {@code -Plogic.seed=S} for longer runs.
 */
class DifferentialTest {

    private static final String VARS = """
            VAR
                x : INT(0..10);
                y : DINT;
                u : USINT;
                r : REAL;
                b : BOOL;
                c : BOOL;
                s : STRING(3);
            END_VAR
            VAR CONSTANT
                cK : INT := 5;
            END_VAR
            """;

    private static SolverContext solver;

    @BeforeAll
    static void start() throws Exception {
        solver = SolverContextFactory.createSolverContext(
                Configuration.defaultConfiguration(),
                LogManager.createNullLogManager(),
                ShutdownNotifier.createDummy(),
                Solvers.SMTINTERPOL);
    }

    @AfterAll
    static void stop() {
        solver.close();
    }

    @Test
    void reasonerAgreesWithSolver() throws Exception {
        int iterations = Integer.getInteger("logic.iterations", 10_000);
        long seed = Long.getLong("logic.seed", 61_131L);
        Random rnd = new Random(seed);
        int batch = 500;
        int[] answers = new int[2]; // UNSAT, SAT
        for (int done = 0; done < iterations; done += batch) {
            List<String> conds = new ArrayList<>();
            for (int i = 0; i < Math.min(batch, iterations - done); i++) {
                conds.add(cond(rnd, 0));
            }
            check(conds, "seed " + seed + ", condition " + done + "+", answers);
        }
        // the generator must exercise both answers, or the test proves nothing
        assertThat(answers[0]).isGreaterThan(iterations / 50);
        assertThat(answers[1]).isGreaterThan(iterations / 10);
    }

    /** Disagreements found while the engine was written. */
    @Test
    void regressions() throws Exception {
        check(
                List.of(
                        // x + u has the type of the subrange x but not its range
                        "NOT ((x = y OR NOT (x + u = 12)))"),
                "regression ",
                new int[2]);
    }

    private static void check(List<String> conds, String where, int[] answers) throws Exception {
        StringBuilder body = new StringBuilder();
        conds.forEach(c -> body.append("IF ").append(c).append(" THEN b := TRUE; END_IF\n"));
        Parsed p = parse("PROGRAM P_Diff\n" + VARS + body + "END_PROGRAM\n");
        Normalizer norm = new Normalizer(p.model(), p.pou(), Set.of());
        for (int i = 0; i < conds.size(); i++) {
            Expression e = ((Statement.If) p.pou().declaration().body().get(i))
                    .branches()
                    .getFirst()
                    .condition();
            Reasoner.Tri engine = Reasoner.sat(norm.cond(e));
            boolean oracleUnsat = unsat(e);
            String at = where + i + ": " + conds.get(i);
            if (engine == Reasoner.Tri.UNSAT) {
                assertThat(oracleUnsat)
                        .as("engine says never true, solver finds a model: %s", at)
                        .isTrue();
                answers[0]++;
            } else if (engine == Reasoner.Tri.SAT) {
                assertThat(oracleUnsat)
                        .as("engine says satisfiable (exact), solver says never true: %s", at)
                        .isFalse();
                answers[1]++;
            }
        }
    }

    // ------------------------------------------------------------------ generator

    private static final String[] INTS = {"x", "y", "u"};
    private static final String[] OPS = {"=", "<>", "<", "<=", ">", ">="};
    private static final String[] REALS = {"-1.5", "0.0", "0.5", "2.25", "10.0"};
    private static final String[] TEXTS = {"'a'", "'b'", "'abc'", "'abcd'", "'A'"};

    private static String cond(Random rnd, int depth) {
        if (depth >= 3 || rnd.nextInt(10) < 4) {
            return atom(rnd);
        }
        return switch (rnd.nextInt(5)) {
            case 0, 1 -> "(" + cond(rnd, depth + 1) + " AND " + cond(rnd, depth + 1) + ")";
            case 2 -> "(" + cond(rnd, depth + 1) + " OR " + cond(rnd, depth + 1) + ")";
            case 3 -> "NOT (" + cond(rnd, depth + 1) + ")";
            default -> "(" + cond(rnd, depth + 1) + " XOR " + cond(rnd, depth + 1) + ")";
        };
    }

    private static String atom(Random rnd) {
        String v = INTS[rnd.nextInt(INTS.length)];
        String op = OPS[rnd.nextInt(OPS.length)];
        return switch (rnd.nextInt(12)) {
            case 0 -> rnd.nextBoolean() ? "b" : "c";
            case 1 ->
                (rnd.nextBoolean() ? "b" : "c") + " " + (rnd.nextBoolean() ? "=" : "<>") + " "
                        + (rnd.nextBoolean() ? "c" : "TRUE");
            case 2, 3, 4 -> v + " " + op + " " + number(rnd);
            case 5 -> number(rnd) + " " + op + " " + v;
            case 6 -> v + " " + op + " " + INTS[rnd.nextInt(INTS.length)];
            case 7 -> v + " " + op + " cK";
            case 8 -> "r " + op + " " + REALS[rnd.nextInt(REALS.length)];
            case 9 -> "s " + (rnd.nextBoolean() ? "=" : "<>") + " " + TEXTS[rnd.nextInt(TEXTS.length)];
            case 10 -> v + " + " + INTS[rnd.nextInt(INTS.length)] + " " + op + " " + number(rnd);
            default -> "r " + op + " " + v;
        };
    }

    private static String number(Random rnd) {
        int k = rnd.nextInt(20);
        return k < 16 ? Integer.toString(k - 3) : k == 16 ? "255" : k == 17 ? "256" : k == 18 ? "-1" : "11";
    }

    // ------------------------------------------------------------------ oracle

    private record Parsed(SemanticModel model, PouSymbol pou) {}

    private static Parsed parse(String text) {
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        SourceUnit u = new StFrontend()
                .parse(SourceFile.of("diff.st", text), registry, diags)
                .unit();
        SemanticModel m = SemanticBuilder.build(List.of(u), diags);
        assertThat(diags.diagnostics()).noneMatch(d -> d.severity() == Severity.ERROR);
        PouSymbol pou = m.pous().stream()
                .filter(x -> x.name().equals("P_Diff"))
                .findFirst()
                .orElseThrow();
        return new Parsed(m, pou);
    }

    private static boolean unsat(Expression e) throws Exception {
        Oracle o = new Oracle();
        BooleanFormula f = o.bmgr.and(o.domains(), o.bool(e));
        try (ProverEnvironment prover = solver.newProverEnvironment()) {
            prover.addConstraint(f);
            return prover.isUnsat();
        }
    }

    /** Encodes an ST condition over the variables of {@link #VARS} as an SMT formula. */
    private static final class Oracle {
        final BooleanFormulaManager bmgr = solver.getFormulaManager().getBooleanFormulaManager();
        final IntegerFormulaManager imgr = solver.getFormulaManager().getIntegerFormulaManager();
        final RationalFormulaManager rmgr = solver.getFormulaManager().getRationalFormulaManager();
        final Map<String, Integer> codes = new HashMap<>();

        BooleanFormula domains() {
            IntegerFormula x = imgr.makeVariable("x");
            IntegerFormula y = imgr.makeVariable("y");
            IntegerFormula u = imgr.makeVariable("u");
            return bmgr.and(List.of(
                    imgr.greaterOrEquals(x, imgr.makeNumber(0)),
                    imgr.lessOrEquals(x, imgr.makeNumber(10)),
                    imgr.greaterOrEquals(y, imgr.makeNumber(Integer.MIN_VALUE)),
                    imgr.lessOrEquals(y, imgr.makeNumber(Integer.MAX_VALUE)),
                    imgr.greaterOrEquals(u, imgr.makeNumber(0)),
                    imgr.lessOrEquals(u, imgr.makeNumber(255))));
        }

        BooleanFormula bool(Expression e) {
            return switch (e) {
                case Expression.NameRef n -> bmgr.makeVariable(n.name().name().toLowerCase(Locale.ROOT));
                case Expression.Literal l
                when l.kind() == Expression.LiteralKind.BOOL ->
                    bmgr.makeBoolean(l.text().equalsIgnoreCase("TRUE"));
                case Expression.Unary u when u.op() == Expression.UnaryOp.NOT -> bmgr.not(bool(u.operand()));
                case Expression.Binary b ->
                    switch (b.op()) {
                        case AND -> bmgr.and(bool(b.left()), bool(b.right()));
                        case OR -> bmgr.or(bool(b.left()), bool(b.right()));
                        case XOR -> bmgr.xor(bool(b.left()), bool(b.right()));
                        default -> compare(b);
                    };
                default -> throw new IllegalArgumentException("not a condition: " + e);
            };
        }

        private BooleanFormula compare(Expression.Binary b) {
            if (isBool(b.left()) || isBool(b.right())) {
                BooleanFormula eq = bmgr.equivalence(bool(b.left()), bool(b.right()));
                return b.op() == Expression.BinaryOp.EQ ? eq : bmgr.not(eq);
            }
            if (isText(b.left()) || isText(b.right())) {
                BooleanFormula eq = textEq(b.left(), b.right());
                return b.op() == Expression.BinaryOp.EQ ? eq : bmgr.not(eq);
            }
            NumeralFormula l = number(b.left());
            NumeralFormula r = number(b.right());
            return switch (b.op()) {
                case EQ -> rmgr.equal(l, r);
                case NE -> bmgr.not(rmgr.equal(l, r));
                case LT -> rmgr.lessThan(l, r);
                case LE -> rmgr.lessOrEquals(l, r);
                case GT -> rmgr.greaterThan(l, r);
                case GE -> rmgr.greaterOrEquals(l, r);
                default -> throw new IllegalArgumentException(b.op().toString());
            };
        }

        /** {@code s = 'lit'}: a STRING(3) never holds a longer text; texts are distinct codes. */
        private BooleanFormula textEq(Expression a, Expression b) {
            String lit = Normalizer.text(a instanceof Expression.Literal ? a : b);
            if (lit.length() > 3) {
                return bmgr.makeFalse();
            }
            int code = codes.computeIfAbsent(lit, k -> codes.size());
            return imgr.equal(imgr.makeVariable("s"), imgr.makeNumber(code));
        }

        private NumeralFormula number(Expression e) {
            return switch (e) {
                case Expression.NameRef n when n.name().name().equalsIgnoreCase("cK") -> imgr.makeNumber(5);
                case Expression.NameRef n when n.name().name().equalsIgnoreCase("r") -> rmgr.makeVariable("r");
                case Expression.NameRef n -> imgr.makeVariable(n.name().name().toLowerCase(Locale.ROOT));
                case Expression.Literal l
                when l.kind() == Expression.LiteralKind.REAL ->
                    rmgr.makeNumber(new BigDecimal((double) (float) Double.parseDouble(l.text())));
                case Expression.Literal l -> imgr.makeNumber(Long.parseLong(l.text()));
                case Expression.Unary u when u.op() == Expression.UnaryOp.NEGATE -> rmgr.negate(number(u.operand()));
                case Expression.Binary b
                when b.op() == Expression.BinaryOp.ADD -> rmgr.add(number(b.left()), number(b.right()));
                default -> throw new IllegalArgumentException("not a number: " + e);
            };
        }

        private static boolean isBool(Expression e) {
            return e instanceof Expression.NameRef n
                            && (n.name().name().equals("b") || n.name().name().equals("c"))
                    || e instanceof Expression.Literal l && l.kind() == Expression.LiteralKind.BOOL
                    || e instanceof Expression.Binary b && Normalizer.isLogical(b)
                    || e instanceof Expression.Unary u && u.op() == Expression.UnaryOp.NOT;
        }

        private static boolean isText(Expression e) {
            return e instanceof Expression.NameRef n && n.name().name().equals("s")
                    || e instanceof Expression.Literal l && l.kind() == Expression.LiteralKind.STRING;
        }
    }
}
