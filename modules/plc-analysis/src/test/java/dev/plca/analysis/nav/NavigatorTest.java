package dev.plca.analysis.nav;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.analysis.Snapshot;
import dev.plca.analysis.Workspace;
import dev.plca.core.source.Span;
import dev.plca.core.testing.Golden;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Definition, references and hover at chosen positions of the semantic test projects (all four languages, positions
 * inside XML CDATA and PLCopen elements included), and the outlines of their files. Golden: {@code nav/}.
 */
class NavigatorTest {

    private static final Path PROJECTS = Path.of("../plc-semantic/src/test/resources/projects");

    private static Snapshot snapshot;

    @BeforeAll
    static void load() {
        Workspace w = new Workspace(PROJECTS);
        w.loadAll(2);
        snapshot = w.snapshot();
    }

    /** A query: in {@code path}, at {@code offset} characters into the first occurrence of {@code needle}. */
    private record Query(String path, String needle, int offset) {}

    private static final List<Query> QUERIES = List.of(
            new Query("plant/MAIN.st", "fbMotor(bEnable", 0),
            new Query("plant/MAIN.st", "fbMotor(bEnable", 9),
            new Query("plant/MAIN.st", "fbMotor.Speed", 8),
            new Query("plant/MAIN.st", "fbMotor.Speed", 13), // directly after the name
            new Query("plant/MAIN.st", "F_Scale(", 2),
            new Query("plant/MAIN.st", "GVL_IO.bStart", 7),
            new Query("plant/MAIN.st", "eMode := Auto", 9),
            new Query("plant/MAIN.st", "i + 1", 2), // no symbol: the type of the expression
            new Query("plant/MAIN.st", "fbMotor : FB_Motor", 10),
            new Query("plant/FB_Motor.st", "FB_Base", 3),
            new Query("plant/FB_Motor.st", "stData : ST_Motor", 10),
            new Query("plant/FB_Motor.st", "E_State.Starting", 9),
            new Query("plant/FB_Motor.st", "fbDelay : ", 0),
            new Query("plant/FB_Motor.st", "fbDelay(IN", 8),
            new Query("plant/FB_Motor.st", "Stop();", 1),
            new Query("plant/GVL_IO.TcGVL", "bStart AT", 1),
            new Query("plant/types.st", "ST_Motor EXTENDS", 2),
            new Query("rockwell_line/Line1.L5X", "XIC(Seal)OTE(MotorRun)", 4),
            new Query("rockwell_line/Line1.L5X", "XIO(Motor1.Fault)", 11),
            new Query("rockwell_line/Line1.L5X", "TON(Tmr", 1),
            new Query("rockwell_line/Line1.L5X", "Valve(V1", 1),
            new Query("rockwell_line/Line1.L5X", "<Tag Name=\"StartPB\"", 12), // in the XML, not in rung text
            new Query("tia_line/Program_blocks/Main.scl", "\"Conveyor_DB\".Running", 15),
            new Query("tia_line/Program_blocks/Main.scl", "#scaled", 1),
            new Query("tia_line/Program_blocks/Main.scl", "\"FC_Scale\"(", 1),
            new Query("tia_line/Program_blocks/Main.scl", "Raw :=", 0),
            new Query("plcopen_mixer/Mixer.xml", "Raw) / 27", 0),
            new Query("plcopen_mixer/Mixer.xml", "Valve := TRUE", 1));

    @Test
    void queries() {
        StringBuilder out = new StringBuilder();
        Navigator nav = new Navigator(snapshot);
        for (Query q : QUERIES) {
            String text = snapshot.file(q.path()).orElseThrow().file().text();
            int at = text.indexOf(q.needle());
            assertThat(at).as("%s in %s", q.needle(), q.path()).isNotNegative();
            out.append("== ")
                    .append(q.path())
                    .append(" '")
                    .append(q.needle())
                    .append("' +")
                    .append(q.offset())
                    .append('\n');
            Optional<Navigator.Target> t = nav.symbolAt(q.path(), at + q.offset());
            if (t.isEmpty()) {
                out.append("  no symbol\n");
            } else {
                out.append("  symbol: ")
                        .append(t.get().symbol().getClass().getSimpleName())
                        .append(' ')
                        .append(t.get().symbol().name())
                        .append(" @")
                        .append(loc(t.get().occurrence()))
                        .append('\n');
                out.append("  definition: ")
                        .append(nav.definition(t.get().symbol())
                                .map(NavigatorTest::loc)
                                .orElse("none"))
                        .append('\n');
                out.append("  references:");
                for (Span s : nav.references(t.get(), false)) {
                    out.append(' ').append(loc(s));
                }
                out.append('\n');
            }
            nav.hover(q.path(), at + q.offset())
                    .ifPresent(h -> out.append("  hover @")
                            .append(loc(h.range()))
                            .append(":\n    ")
                            .append(h.markdown().replace("\n", "\n    "))
                            .append('\n'));
        }
        Golden.assertMatches(out.toString(), "nav/queries.txt");
    }

    @Test
    void outlines() {
        StringBuilder out = new StringBuilder();
        Outline outline = new Outline(snapshot);
        for (String path : List.of(
                "plant/FB_Motor.st",
                "plant/types.st",
                "plant/GVL_IO.TcGVL",
                "rockwell_line/Line1.L5X",
                "tia_line/Program_blocks/Main.scl",
                "tia_line/Program_blocks/DB_Counters.db",
                "plcopen_mixer/Mixer.xml")) {
            out.append("== ").append(path).append('\n');
            outline.document(path).forEach(i -> item(i, 1, out));
        }
        out.append("== workspace 'mot'\n");
        for (Outline.Symbol s : outline.workspace("mot", 20)) {
            out.append("  ")
                    .append(s.kind())
                    .append(' ')
                    .append(s.name())
                    .append(s.container() == null ? "" : " in " + s.container())
                    .append(" @")
                    .append(loc(s.selection()))
                    .append('\n');
        }
        Golden.assertMatches(out.toString(), "nav/outline.txt");
    }

    private static void item(Outline.Item i, int depth, StringBuilder out) {
        out.append("  ".repeat(depth))
                .append(i.kind())
                .append(' ')
                .append(i.name())
                .append(i.detail().isEmpty() ? "" : " : " + i.detail())
                .append(" @")
                .append(loc(i.selection()))
                .append('\n');
        i.children().forEach(c -> item(c, depth + 1, out));
    }

    @Test
    void reusesIndexesOfUnchangedUnits() {
        Workspace w = new Workspace(PROJECTS);
        w.loadAll(1);
        Snapshot first = w.snapshot();
        Navigator a = new Navigator(first);
        var unit = first.file("plant/types.st").orElseThrow().unit();
        NodeIndex index = a.index(unit);
        w.update(
                "plant/MAIN.st",
                first.file("plant/MAIN.st").orElseThrow().file().text());
        Navigator b = new Navigator(w.snapshot(), a);
        assertThat(b.index(unit)).isSameAs(index);
    }

    @Test
    void subsequenceMatch() {
        assertThat(Outline.matches("FB_Motor", "fbm")).isTrue();
        assertThat(Outline.matches("FB_Motor", "mfb")).isFalse();
        assertThat(Outline.matches("x", "")).isTrue();
    }

    private static String loc(Span s) {
        var l = snapshot.registry().resolve(s);
        return l.path().substring(l.path().lastIndexOf('/') + 1) + ":" + l.startLine() + ":" + l.startColumn() + "-"
                + l.endLine() + ":" + l.endColumn();
    }
}
