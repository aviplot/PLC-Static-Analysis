package dev.plca.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Expression;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.StFrontend;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticTest {

    private final SourceRegistry registry = new SourceRegistry();
    private final DiagnosticCollector diags = new DiagnosticCollector();

    private SemanticModel model(String... files) {
        StFrontend fe = new StFrontend();
        List<SourceUnit> units = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            String path = files[i].startsWith("<") ? "f" + i + ".TcGVL" : "f" + i + ".st";
            units.add(fe.parse(SourceFile.of(path, files[i]), registry, diags).unit());
        }
        assertThat(diags.hasErrors())
                .as("parse errors: %s", diags.diagnostics())
                .isFalse();
        return SemanticBuilder.build(units, diags);
    }

    private static PouSymbol pou(SemanticModel m, String qualifiedName) {
        return m.pous().stream()
                .filter(p -> p.qualifiedName().equals(qualifiedName))
                .findFirst()
                .orElseThrow();
    }

    private static List<Statement> body(SemanticModel m, String pou) {
        return pou(m, pou).declaration().body();
    }

    /** The value side of the n-th statement, which must be an assignment. */
    private static Expression value(SemanticModel m, String pou, int n) {
        return ((Statement.Assignment) body(m, pou).get(n)).value();
    }

    @Test
    void qualifiedOnlyGlobalsNeedTheListName() {
        String gvl = """
                <TcPlcObject><GVL Name="GVL_IO"><Declaration><![CDATA[{attribute 'qualified_only'}
                VAR_GLOBAL x : INT; END_VAR]]></Declaration></GVL></TcPlcObject>""";
        SemanticModel m = model(gvl, "PROGRAM P\nVAR a : INT; END_VAR\na := x;\na := GVL_IO.x;\nEND_PROGRAM");
        assertThat(m.symbol(value(m, "P", 0))).isEmpty();
        assertThat(m.symbol(value(m, "P", 1))).get().isInstanceOf(VariableSymbol.class);
        assertThat(m.type(value(m, "P", 1)).display()).isEqualTo("INT");
    }

    @Test
    void strictEnumsNeedQualificationExceptInCaseLabels() {
        SemanticModel m =
                model("{attribute 'strict'}\nTYPE E : (A, B := 7, C); END_TYPE\nTYPE F : (X, Y); END_TYPE", """
                PROGRAM P
                VAR eVal : E; fVal : F; n : INT; END_VAR
                n := A;
                eVal := E.C;
                fVal := Y;
                CASE eVal OF A: n := 1; END_CASE
                END_PROGRAM""");
        assertThat(m.symbol(value(m, "P", 0))).isEmpty();
        assertThat(m.symbol(value(m, "P", 1)))
                .get()
                .isInstanceOfSatisfying(
                        EnumValueSymbol.class, v -> assertThat(v.value()).isEqualTo(8));
        assertThat(m.symbol(value(m, "P", 2))).get().isInstanceOf(EnumValueSymbol.class);
        Statement.Case c = (Statement.Case) body(m, "P").get(3);
        assertThat(m.symbol(c.branches().getFirst().labels().getFirst().low()))
                .get()
                .isInstanceOf(EnumValueSymbol.class);
    }

    @Test
    void membersSeeTheirFunctionBlockAndItsBase() {
        SemanticModel m = model("""
                FUNCTION_BLOCK Base
                VAR baseVar : INT; END_VAR
                METHOD M : INT
                M := baseVar;
                END_METHOD
                END_FUNCTION_BLOCK
                FUNCTION_BLOCK Derived EXTENDS Base
                VAR own : INT; END_VAR
                METHOD M : INT
                M := SUPER^.M() + own + baseVar + THIS^.own;
                END_METHOD
                ACTION A:
                own := baseVar;
                END_ACTION
                END_FUNCTION_BLOCK""");
        Expression.Binary sum = (Expression.Binary) value(m, "Derived.M", 0);
        // ((SUPER^.M() + own) + baseVar) + THIS^.own
        Expression.Binary left = (Expression.Binary) sum.left();
        Expression.Binary leftLeft = (Expression.Binary) left.left();
        Expression.Call superCall = (Expression.Call) leftLeft.left();
        assertThat(m.symbol(superCall.callee())).get().isSameAs(pou(m, "Base.M"));
        assertThat(m.symbol(left.right())).get().hasToString("Base.baseVar");
        assertThat(m.symbol(sum.right())).get().hasToString("Derived.own");
        assertThat(m.symbol(value(m, "Derived.A", 0))).get().hasToString("Base.baseVar");
        assertThat(pou(m, "Derived").extendsPou()).isSameAs(pou(m, "Base"));
    }

    @Test
    void libraryPrefixesResolveToBuiltinsOrLibrarySymbols() {
        SemanticModel m = model("""
                PROGRAM P
                VAR t : Tc2_Standard.TON; p : Tc2_MC2.MC_Power; b : BOOL; END_VAR
                b := t.Q;
                b := p.Status;
                b := Tc2_Utilities.F_Foo(1);
                END_PROGRAM""");
        assertThat(pou(m, "P").variable("t").orElseThrow().type())
                .isInstanceOfSatisfying(
                        Type.Instance.class,
                        i -> assertThat(i.pou().isBuiltin()).isTrue());
        assertThat(m.type(value(m, "P", 0)).display()).isEqualTo("BOOL");
        assertThat(Type.isUnknown(m.type(value(m, "P", 1)))).isTrue();
        Expression.Call call = (Expression.Call) value(m, "P", 2);
        assertThat(m.symbol(call.callee())).get().isInstanceOf(LibrarySymbol.class);
        assertThat(m.resolutionStats().unresolved()).isZero();
    }

    @Test
    void constantsFoldIntoArrayBoundsAndExpressions() {
        SemanticModel m = model("""
                PROGRAM P
                VAR CONSTANT N : INT := 4 * 2 + 2; END_VAR
                VAR a : ARRAY[0..N - 1] OF REAL; t : TIME; r : REAL; END_VAR
                t := T#1s + T#500ms;
                r := 10 / 0;
                END_PROGRAM""");
        Type.Array a = (Type.Array) pou(m, "P").variable("a").orElseThrow().type();
        assertThat(a.dimensions().getFirst()).isEqualTo(new Type.Dimension(0L, 9L));
        assertThat(m.constant(value(m, "P", 0))).contains(new Constant.Duration(1_500_000_000L));
        assertThat(m.constant(value(m, "P", 1))).isEmpty(); // division by zero is not a value
    }

    @Test
    void typePromotionFollowsIecGenericRules() {
        SemanticModel m = model("""
                PROGRAM P
                VAR i : INT; d : DINT; r : REAL; l : LREAL; b : BOOL; w : WORD; END_VAR
                d := i + d;
                r := i * r;
                l := r + l;
                i := i + 1;
                b := i < 3;
                w := w AND 16#FF;
                r := 1.5 + 2;
                END_PROGRAM""");
        assertThat(List.of(0, 1, 2, 3, 4, 5, 6).stream()
                        .map(n -> m.type(value(m, "P", n)).display()))
                .containsExactly("DINT", "REAL", "LREAL", "INT", "BOOL", "WORD", "ANY_REAL");
    }

    @Test
    void fbCallEffectsWriteInputsAndReadOutputs() {
        SemanticModel m = model("""
                FUNCTION_BLOCK FB
                VAR_INPUT IN : BOOL; END_VAR
                VAR_OUTPUT Q : BOOL; END_VAR
                VAR_IN_OUT io : INT; END_VAR
                END_FUNCTION_BLOCK
                PROGRAM P
                VAR fb : FB; x, y : BOOL; v : INT; a : ARRAY[0..9] OF INT; p : POINTER TO INT; END_VAR
                fb(IN := x, Q => y, io := v);
                a[3] := a[v];
                p := ADR(v);
                END_PROGRAM""");
        Effects call = m.effects(body(m, "P").get(0));
        assertThat(call.accesses())
                .extracting(Access::toString)
                .containsExactly("WRITE fb.IN", "READ x", "READ fb.Q", "WRITE y", "READ_WRITE v");
        assertThat(call.calls()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(CallSite.Kind.FB_INSTANCE);
            assertThat(c.instance().target()).isEqualTo("fb");
        });
        assertThat(m.effects(body(m, "P").get(1)).accesses())
                .extracting(Access::toString)
                .containsExactly("WRITE a[3]", "READ a[*]", "READ v");
        assertThat(m.effects(body(m, "P").get(2)).accesses())
                .extracting(Access::toString)
                .containsExactly("WRITE p", "ADDRESS v");
    }

    @Test
    void propertyAccessIsAnAccessorCall() {
        SemanticModel m = model("""
                FUNCTION_BLOCK FB
                VAR v : INT; END_VAR
                PROPERTY Value : INT
                GET Value := v; END_GET
                SET v := Value; END_SET
                END_PROPERTY
                END_FUNCTION_BLOCK
                PROGRAM P
                VAR fb : FB; n : INT; END_VAR
                n := fb.Value;
                fb.Value := 3;
                END_PROGRAM""");
        assertThat(m.effects(body(m, "P").get(0)).calls())
                .extracting(CallSite::kind)
                .containsExactly(CallSite.Kind.PROPERTY_GET);
        assertThat(m.effects(body(m, "P").get(1)).calls())
                .extracting(CallSite::kind)
                .containsExactly(CallSite.Kind.PROPERTY_SET);
        // inside the accessors the property name is the value variable
        assertThat(m.symbol(((Statement.Assignment) body(m, "FB.Value.SET").getFirst()).value()))
                .get()
                .hasToString("FB.Value.Value");
    }

    @Test
    void recursionIsFoundInTheCallGraph() {
        SemanticModel m = model("""
                FUNCTION A : INT
                A := B();
                END_FUNCTION
                FUNCTION B : INT
                B := A();
                END_FUNCTION
                FUNCTION C : INT
                C := C();
                END_FUNCTION""");
        assertThat(m.callGraph().cycles())
                .extracting(c -> c.stream().map(PouSymbol::name).toList())
                .containsExactlyInAnyOrder(List.of("A", "B"), List.of("C"));
    }

    @Test
    void recursiveInstanceTypesAreCutNotLooped() {
        SemanticModel m = model("""
                FUNCTION_BLOCK Node
                VAR child : Node; END_VAR
                END_FUNCTION_BLOCK
                PROGRAM P
                VAR n : Node; END_VAR
                END_PROGRAM""");
        assertThat(m.instanceGraph().all()).extracting(Instance::path).containsExactly("P", "P.n");
        assertThat(m.instanceGraph().truncated()).isTrue();
    }

    @Test
    void iecConfigurationDefinesTasks() {
        SemanticModel m = model("""
                PROGRAM Main
                END_PROGRAM
                CONFIGURATION C
                RESOURCE R ON CPU
                    TASK Fast(INTERVAL := T#10ms, PRIORITY := 1);
                    PROGRAM inst WITH Fast : Main;
                END_RESOURCE
                END_CONFIGURATION""");
        assertThat(m.tasks().tasks()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("Fast");
            assertThat(t.priority()).isEqualTo(1);
            assertThat(t.interval()).isEqualTo(10_000_000L);
            assertThat(t.programs()).extracting(PouSymbol::name).containsExactly("Main");
        });
        assertThat(m.tasks().tasksOf(pou(m, "Main"))).hasSize(1);
    }

    @Test
    void duplicatesAreReportedNotFatal() {
        SemanticModel m = model("FUNCTION F : INT\nEND_FUNCTION", "FUNCTION F : INT\nEND_FUNCTION");
        assertThat(diags.diagnostics()).extracting(d -> d.code()).contains(SemanticBuilder.DUPLICATE);
        assertThat(m.global().pou("F")).isPresent();
        assertThat(m.pous()).filteredOn(p -> p.kind() == PouKind.FUNCTION).hasSize(2);
    }
}
