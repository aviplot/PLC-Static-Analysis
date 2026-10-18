package dev.plca.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LineIndexTest {

    @Test
    void mixedLineEndings() {
        // a0 \n1 | b2 c3 \r4 \n5 | d6 \r7 | e8
        LineIndex li = LineIndex.of("a\nbc\r\nd\re");
        assertThat(li.lineCount()).isEqualTo(4);
        assertThat(pos(li, 0)).isEqualTo("1:1");
        assertThat(pos(li, 3)).isEqualTo("2:2");
        assertThat(pos(li, 4)).isEqualTo("2:3");
        assertThat(pos(li, 5)).isEqualTo("2:4");
        assertThat(pos(li, 6)).isEqualTo("3:1");
        assertThat(pos(li, 8)).isEqualTo("4:1");
        assertThat(pos(li, 9)).isEqualTo("4:2"); // end of text
    }

    @Test
    void trailingNewlineStartsEmptyLastLine() {
        LineIndex li = LineIndex.of("x\n");
        assertThat(li.lineCount()).isEqualTo(2);
        assertThat(pos(li, 2)).isEqualTo("2:1");
    }

    @Test
    void emptyText() {
        LineIndex li = LineIndex.of("");
        assertThat(li.lineCount()).isEqualTo(1);
        assertThat(pos(li, 0)).isEqualTo("1:1");
    }

    @Test
    void columnsCountUtf16CodeUnits() {
        LineIndex li = LineIndex.of("a😀b"); // a, U+1F600 (two code units), b
        assertThat(li.column(3)).isEqualTo(4);
    }

    @Test
    void offsetIsInverseOfLineAndColumn() {
        LineIndex li = LineIndex.of("PROGRAM P\r\nVAR\r\nEND_VAR\r\n");
        for (int off = 0; off <= 25; off++) {
            assertThat(li.offset(li.line(off), li.column(off))).isEqualTo(off);
        }
    }

    @Test
    void rejectsOffsetsOutsideText() {
        LineIndex li = LineIndex.of("abc");
        assertThatThrownBy(() -> li.line(4)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> li.line(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> li.lineStart(2)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void manyLinesGrowTheIndex() {
        LineIndex li = LineIndex.of("x\n".repeat(10_000));
        assertThat(li.lineCount()).isEqualTo(10_001);
        assertThat(li.line(2 * 9_999)).isEqualTo(10_000);
    }

    private static String pos(LineIndex li, int offset) {
        return li.line(offset) + ":" + li.column(offset);
    }
}
