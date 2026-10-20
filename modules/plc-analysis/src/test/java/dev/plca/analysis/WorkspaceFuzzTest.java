package dev.plca.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.plca.analysis.nav.Navigator;
import dev.plca.analysis.nav.Outline;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.Span;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleConfig;
import dev.plca.rules.RuleEngine;
import dev.plca.rules.RuleRegistry;
import dev.plca.semantic.ModelPrinter;
import dev.plca.semantic.SemanticBuilder;
import dev.plca.semantic.SemanticModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Fuzzes the editor session (M8): random edits to a small project (ST and TwinCAT XML), each followed by a snapshot, a
 * fast or full rule run, and queries at random positions. Invariants: nothing throws, no rule fails, and every span an
 * answer holds resolves in its snapshot. {@code JAZZER_FUZZ=1} to fuzz; the build runs the seeds.
 */
class WorkspaceFuzzTest {

    private static final RuleRegistry RULES = RuleRegistry.standard();

    private static final Map<String, String> PROJECT =
            Map.of("MAIN.st", """
            PROGRAM MAIN
            VAR
                fb : FB_A;
                r : REAL;
                e : E_Mode;
            END_VAR
            fb(bIn := r > 1.0, stData := GVL.st);
            IF r = 2.0 THEN e := E_Mode.Auto; END_IF
            FOR GVL.i := 1 TO 10 DO r := r + F_Half(r); END_FOR
            END_PROGRAM
            """, "FB_A.st", """
            // one input, one in-out
            FUNCTION_BLOCK FB_A
            VAR_INPUT bIn : BOOL; END_VAR
            VAR_IN_OUT stData : ST_Data; END_VAR
            stData.n := stData.n + 1;
            METHOD M : INT
            M := 1;
            END_METHOD
            END_FUNCTION_BLOCK
            FUNCTION F_Half : REAL
            VAR_INPUT x : REAL; END_VAR
            F_Half := x / 2;
            END_FUNCTION
            """, "types.st", """
            TYPE E_Mode : (Manual, Auto); END_TYPE
            TYPE ST_Data : STRUCT n : DINT; a : ARRAY[0..3] OF INT; END_STRUCT END_TYPE
            """, "GVL.TcGVL", """
            <?xml version="1.0" encoding="utf-8"?>
            <TcPlcObject Version="1.1.0.1">
              <GVL Name="GVL" Id="{00000000-0000-0000-0000-000000000001}">
                <Declaration><![CDATA[VAR_GLOBAL
                st : ST_Data;
                i : INT;
            END_VAR]]></Declaration>
              </GVL>
            </TcPlcObject>
            """);

    private static final List<String> SNIPPETS = List.of(
            "fb.", "E_Mode.", "IF ", " THEN ", "END_IF", ";", ":=", "(*", "*)", "//", "'", "]]>", "&lt;", "VAR",
            "END_VAR", "\n", "GVL.", "F_Half(", ")", "METHOD", "16#FF", "T#1s", "^", "REF=", "\"", "#", "{");

    @BeforeAll
    static void incrementalEvenForSmallProjects() {
        SemanticBuilder.setMaxRebuiltShare(1.0);
    }

    @AfterAll
    static void restore() {
        SemanticBuilder.setMaxRebuiltShare(1 / 3.0);
    }

    @FuzzTest(maxDuration = "5m")
    void fuzzEditsAndQueries(FuzzedDataProvider data) {
        Workspace w = new Workspace(Path.of("fuzz"));
        Map<String, String> texts = new java.util.TreeMap<>(PROJECT);
        texts.forEach(w::update);
        List<String> paths = new ArrayList<>(texts.keySet());
        Navigator nav = null;
        for (int step = 0; step < 12 && data.remainingBytes() > 0; step++) {
            String path = data.pickValue(paths);
            String text = texts.get(path);
            switch (data.consumeInt(0, 3)) {
                case 0, 1 -> {
                    int at = data.consumeInt(0, text.length());
                    int del = data.consumeInt(0, Math.min(8, text.length() - at));
                    String insert = data.consumeBoolean() ? data.pickValue(SNIPPETS) : data.consumeString(6);
                    text = text.substring(0, at) + insert + text.substring(at + del);
                    texts.put(path, text);
                    w.update(path, text);
                }
                case 2 -> {
                    Snapshot s = w.snapshot();
                    Snapshot.Analysis a = s.analyze(
                            RULES,
                            RuleConfig.only("all"),
                            null,
                            data.consumeBoolean() ? null : Set.of(path),
                            1,
                            () -> false);
                    check(s.registry(), a);
                    sameAsFullBuild(s);
                }
                default -> {
                    nav = new Navigator(w.snapshot(), nav);
                    query(nav, path, data.consumeInt(0, text.length()));
                }
            }
        }
    }

    /** The incremental model of every group (M10) holds exactly what a full build of its units holds. */
    private static void sameAsFullBuild(Snapshot s) {
        for (Snapshot.Group g : s.groups()) {
            SemanticModel full = SemanticBuilder.build(g.units(), new DiagnosticCollector());
            assertThat(ModelPrinter.printAll(g.model(), s.registry()::resolve))
                    .as(String.valueOf(g.model().buildInfo()))
                    .isEqualTo(ModelPrinter.printAll(full, s.registry()::resolve));
        }
    }

    private static void check(SourceRegistry r, Snapshot.Analysis a) {
        assertThat(a.diagnostics()).noneMatch(d -> d.code().equals(RuleEngine.INTERNAL));
        a.diagnostics().forEach(d -> r.resolve(d.span()));
        for (Finding f : a.findings()) {
            r.resolve(f.span());
            f.related().forEach(rel -> r.resolve(rel.span()));
        }
    }

    private static void query(Navigator nav, String path, int offset) {
        SourceRegistry r = nav.snapshot().registry();
        nav.symbolAt(path, offset).ifPresent(t -> {
            r.resolve(t.occurrence());
            nav.definition(t.symbol()).ifPresent(r::resolve);
            nav.references(t, true).forEach(r::resolve);
        });
        nav.hover(path, offset).ifPresent(h -> {
            assertThat(h.markdown()).isNotBlank();
            r.resolve(h.range());
        });
        Outline outline = new Outline(nav.snapshot());
        outline.document(path).forEach(i -> resolve(r, i));
        outline.workspace("", 50).forEach(s -> r.resolve(s.selection()));
    }

    private static void resolve(SourceRegistry r, Outline.Item i) {
        r.resolve(i.range());
        Span sel = i.selection();
        r.resolve(sel);
        i.children().forEach(c -> resolve(r, c));
    }
}
