package dev.plca.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.l5x.L5xFrontend;
import dev.plca.frontend.plcopen.PlcOpenFrontend;
import dev.plca.frontend.scl.SclFrontend;
import dev.plca.frontend.st.ProjectLoader;
import dev.plca.frontend.st.StFrontend;
import dev.plca.frontend.st.kit.Frontends;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The incremental build (M10) against the oracle: after every edit, the model built from the previous one equals a full
 * build of the same units — every binding, type, parameter, owner and effect, the printed model, unresolved names,
 * statistics and diagnostics — and no table refers to a symbol of an earlier version of a file.
 */
class IncrementalModelTest {

    private static final Frontends FRONTENDS =
            Frontends.of(StFrontend::new, SclFrontend::new, L5xFrontend::new, PlcOpenFrontend::new);

    @TempDir
    Path tmp;

    private double share;

    @BeforeEach
    void allowSmallProjects() {
        share = SemanticBuilder.maxRebuiltShare;
        SemanticBuilder.maxRebuiltShare = 1.0; // the fixtures are small: every edit touches a large share
    }

    @AfterEach
    void restore() {
        SemanticBuilder.maxRebuiltShare = share;
    }

    /** A project being edited: its files, registry, units and the last model. */
    private static final class Session {
        final Map<String, String> texts = new LinkedHashMap<>();
        SourceRegistry registry = new SourceRegistry();
        final Map<String, SourceUnit> units = new LinkedHashMap<>();
        SemanticModel model;
        List<Diagnostic> diagnostics;

        Session(Path root) {
            ProjectLoader.Project p = ProjectLoader.load(root, FRONTENDS, 2);
            registry = p.registry();
            for (SourceUnit u : p.units()) {
                units.put(u.path(), u);
                texts.put(
                        u.path(),
                        registry.files().stream()
                                .filter(f -> f.path().equals(u.path()))
                                .findFirst()
                                .orElseThrow()
                                .text());
            }
            DiagnosticCollector d = new DiagnosticCollector();
            model = SemanticBuilder.buildForEditing(List.copyOf(units.values()), d, null);
            diagnostics = d.diagnostics();
        }

        /** Replaces a file's text and rebuilds from the previous model. */
        void edit(String path, UnaryOperator<String> change) {
            String text = change.apply(texts.get(path));
            texts.put(path, text);
            registry = registry.without(Set.of(path));
            SourceUnit u = FRONTENDS
                    .forPath(path)
                    .parse(SourceFile.of(path, text), registry, new DiagnosticCollector())
                    .unit();
            units.put(path, u);
            DiagnosticCollector d = new DiagnosticCollector();
            model = SemanticBuilder.buildForEditing(List.copyOf(units.values()), d, model);
            diagnostics = d.diagnostics();
        }

        /** The model a full build makes of the current units. */
        String fullDigest() {
            DiagnosticCollector d = new DiagnosticCollector();
            SemanticModel full = SemanticBuilder.build(List.copyOf(units.values()), d);
            return IncrementalModelTest.digest(full, registry, d.diagnostics());
        }

        String digest() {
            return IncrementalModelTest.digest(model, registry, diagnostics);
        }
    }

    // ---- the generated project --------------------------------------------------------------------

    private static final int FBS = 40;

    /** A chain of FBs holding the previous one, with struct and enum types, a GVL and a function. */
    private Path generated() throws IOException {
        Path root = tmp.resolve("gen");
        Files.createDirectories(root);
        Files.writeString(root.resolve("GVL.st"), """
                VAR_GLOBAL
                    gCount : DINT;
                    gLimit : INT := 10;
                END_VAR
                """);
        for (String[] gvl : List.of(new String[] {"GVL_A", "gA : INT;"}, new String[] {"GVL_B", "gB : BOOL;"})) {
            Files.writeString(root.resolve(gvl[0] + ".TcGVL"), """
                    <?xml version="1.0" encoding="utf-8"?>
                    <TcPlcObject Version="1.1.0.1">
                      <GVL Name="GVL_Shared" Id="{00000000-0000-0000-0000-00000000000%s}">
                        <Declaration><![CDATA[VAR_GLOBAL
                        %s
                    END_VAR]]></Declaration>
                      </GVL>
                    </TcPlcObject>
                    """.formatted(gvl[0].endsWith("A") ? "1" : "2", gvl[1]));
        }
        Files.writeString(root.resolve("F_Add.st"), """
                FUNCTION F_Add : INT
                VAR_INPUT a : INT; b : INT; END_VAR
                F_Add := a + b;
                END_FUNCTION
                """);
        for (int k = 0; k < FBS; k++) {
            Files.writeString(root.resolve("T_" + k + ".st"), """
                    TYPE ST_%1$d :
                    STRUCT
                        nValue : INT;
                        aItems : ARRAY[0..3] OF BOOL;
                    END_STRUCT
                    END_TYPE
                    TYPE E_%1$d : (Off_%1$d, On_%1$d); END_TYPE
                    """.formatted(k));
            String child = k == 0 ? "" : "    fbChild : FB_" + (k - 1) + ";\n";
            String call = k == 0 ? "" : "IF bIn THEN fbChild(bIn := TRUE); nOut := fbChild.nOut; END_IF\n";
            Files.writeString(root.resolve("FB_" + k + ".st"), """
                    FUNCTION_BLOCK FB_%1$d
                    VAR_INPUT bIn : BOOL; END_VAR
                    VAR_OUTPUT nOut : INT; END_VAR
                    VAR
                    %2$s    st : ST_%1$d;
                        eMode : E_%1$d;
                        fbTimer : TON;
                    END_VAR
                    %3$snOut := F_Add(nOut, %1$d);
                    st.nValue := nOut;
                    eMode := On_%1$d;
                    fbTimer(IN := bIn, PT := T#1S);
                    gCount := gCount + 1;
                    END_FUNCTION_BLOCK
                    """.formatted(k, child, call));
        }
        Files.writeString(root.resolve("MAIN.st"), """
                PROGRAM MAIN
                VAR
                    fbTop : FB_%1$d;
                    fbMid : FB_%2$d;
                    n : INT;
                END_VAR
                fbTop(bIn := TRUE);
                fbMid(bIn := gLimit > 3);
                n := fbTop.nOut + fbMid.nOut;
                END_PROGRAM
                """.formatted(FBS - 1, FBS / 2));
        return root;
    }

    @Test
    void editsInTheGeneratedProjectAreIncrementalAndExact() throws IOException {
        Session s = new Session(generated());
        assertThat(s.digest()).isEqualTo(s.fullDigest());

        // a body edit: only the file and nothing else
        s.edit("FB_20.st", t -> t.replace("st.nValue := nOut;", "st.nValue := nOut + 2;"));
        assertIncremental(s, -1, -1);

        // a new local variable: its users are not affected, but the FB's symbols are new
        s.edit("FB_20.st", t -> t.replace("    eMode : E_20;", "    eMode : E_20;\n    nExtra : INT;"));
        assertIncremental(s, -1, -1);

        // an output changes type: FB_21 reads it, FB_21 holds an FB_20, and so on up the chain
        s.edit("FB_20.st", t -> t.replace("VAR_OUTPUT nOut : INT; END_VAR", "VAR_OUTPUT nOut : DINT; END_VAR"));
        assertExact(s);

        // a struct member changes: the FB using the struct
        s.edit("T_5.st", t -> t.replace("nValue : INT;", "nValue : LREAL;"));
        assertIncremental(s, -1, -1);

        // a variable others use disappears: they get unresolved names
        s.edit("FB_30.st", t -> t.replace("VAR_OUTPUT nOut : INT; END_VAR", "VAR_OUTPUT nOutput : INT; END_VAR"));
        assertExact(s);
        assertThat(s.model.unresolved()).isNotEmpty();
        s.edit("FB_30.st", t -> t.replace("nOutput", "nOut"));
        assertExact(s);

        // the function's signature: every FB calls it
        s.edit("F_Add.st", t -> t.replace("FUNCTION F_Add : INT", "FUNCTION F_Add : DINT"));
        assertExact(s);

        // a global variable's type
        s.edit("GVL.st", t -> t.replace("gCount : DINT;", "gCount : LINT;"));
        assertExact(s);

        // a name declared twice: the second file's diagnostic follows the first file's edits
        s.edit("FB_7.st", t -> t + "\nFUNCTION_BLOCK FB_5\nVAR_INPUT bIn : BOOL; END_VAR\nEND_FUNCTION_BLOCK\n");
        assertExact(s);
        s.edit("FB_5.st", t -> t.replace("\n", "\n\n"));
        assertIncremental(s, -1, -1);
        s.edit("FB_7.st", t -> t.replace("st.nValue := nOut;", "st.nValue := 1;"));
        assertIncremental(s, -1, -1);

        // one variable list split over two files: both are declared again together
        s.edit("GVL_A.TcGVL", t -> t.replace("gA : INT;", "gA : DINT;"));
        assertIncremental(s, 2, -1);
        s.edit("GVL_B.TcGVL", t -> t.replace("gB : BOOL;", "gB : BOOL := TRUE;"));
        assertIncremental(s, 2, -1);
        // the same variable in both halves: the later file's replaces the earlier one's
        s.edit("GVL_B.TcGVL", t -> t.replace("gB : BOOL := TRUE;", "gB : BOOL := TRUE;\n    gA : INT := 5;"));
        assertExact(s);
        s.edit("GVL_A.TcGVL", t -> t.replace("gA : DINT;", "gA : DINT := 7;"));
        assertIncremental(s, 2, -1);

        // a new name: a full build
        s.edit("FB_7.st", t -> t.replace("FUNCTION_BLOCK FB_7", "FUNCTION_BLOCK FB_7b"));
        assertThat(s.model.buildInfo().incremental()).isFalse();
        assertThat(s.model.buildInfo().reason()).contains("names");
        assertThat(s.digest()).isEqualTo(s.fullDigest());

        // a syntax error and its repair
        s.edit("FB_12.st", t -> t.replace("nOut := F_Add(nOut, 12);", "nOut := F_Add(nOut, ;"));
        assertExact(s);
        s.edit("FB_12.st", t -> t.replace("nOut := F_Add(nOut, ;", "nOut := F_Add(nOut, 12);"));
        assertExact(s);
    }

    @Test
    void enumValuesAndSubrangesAreKnownWhateverTheFileOrder() throws IOException {
        Path root = tmp.resolve("order");
        Files.createDirectories(root);
        Files.writeString(root.resolve("A_Fb.st"), """
                FUNCTION_BLOCK A_Fb
                VAR aBuf : ARRAY[0..E_Size.Last] OF INT; END_VAR
                aBuf[0] := 1;
                END_FUNCTION_BLOCK
                """);
        Files.writeString(root.resolve("B_Fb.st"), """
                FUNCTION_BLOCK B_Fb
                VAR nCap : T_Cap; END_VAR
                nCap := 1;
                END_FUNCTION_BLOCK
                """);
        Files.writeString(
                root.resolve("Z_Types.st"),
                "TYPE E_Size : (S0, S1, Last := 6); END_TYPE\nTYPE T_Cap : DINT(0..100); END_TYPE\n");
        Session s = new Session(root);
        assertThat(s.fullDigest()).contains("aBuf : ARRAY[0..6] OF INT").contains("nCap : DINT(0..100)");
        s.edit("Z_Types.st", t -> t.replace("Last := 6", "Last := 9"));
        assertExact(s);
        assertThat(s.digest()).contains("aBuf : ARRAY[0..9] OF INT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plant", "tia_line", "plcopen_mixer", "rockwell_line"})
    void everyFileReparsedAsIsGivesTheSameModel(String project) {
        Session s = new Session(Path.of(System.getProperty("golden.root"), "projects", project));
        assertThat(s.digest()).isEqualTo(s.fullDigest());
        for (String path : List.copyOf(s.units.keySet())) {
            s.edit(path, t -> t);
            assertExact(s);
        }
    }

    @Test
    void editsInPlant() {
        Session s = new Session(Path.of(System.getProperty("golden.root"), "projects", "plant"));
        s.edit("MAIN.st", t -> t.replace("n := i + 1;", "n := i + 2; gnCounter := 5;"));
        assertIncremental(s, 1, 1);
        s.edit("FB_Motor.st", t -> t.replace("bRunning : BOOL;", "bRunning : INT;"));
        assertExact(s);
        s.edit("types.st", t -> t.replace("rSpeed : REAL;", "rSpeed : LREAL;"));
        assertExact(s);
        s.edit("FB_Base.st", t -> t + "\n");
        assertExact(s);
        s.edit("functions.st", t -> t + "\n");
        assertExact(s);
    }

    private static void assertExact(Session s) {
        assertThat(s.digest()).as(String.valueOf(s.model.buildInfo())).isEqualTo(s.fullDigest());
    }

    /** Incremental, exact, and (when not -1) declaring and binding that many units again. */
    private static void assertIncremental(Session s, int declared, int bound) {
        SemanticModel.BuildInfo info = s.model.buildInfo();
        assertThat(info.incremental()).as(String.valueOf(info)).isTrue();
        if (declared >= 0) {
            assertThat(info.declared()).as(String.valueOf(info)).isEqualTo(declared);
        }
        if (bound >= 0) {
            assertThat(info.bound()).as(String.valueOf(info)).isEqualTo(bound);
        }
        assertExact(s);
    }

    // ---- the oracle -------------------------------------------------------------------------------

    /** Everything a model holds ({@link ModelPrinter#printAll}) and its diagnostics, sorted. */
    static String digest(SemanticModel m, SourceRegistry registry, List<Diagnostic> diagnostics) {
        StringBuilder out = new StringBuilder(ModelPrinter.printAll(m, registry::resolve));
        List<String> diags = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            Location l;
            try {
                l = registry.resolve(d.span());
            } catch (RuntimeException e) {
                l = new Location("STALE", 0, 0, 0, 0);
            }
            diags.add(d.code() + " " + l + " " + d.message());
        }
        diags.sort(null);
        diags.forEach(d -> out.append(d).append('\n'));
        return out.toString();
    }
}
