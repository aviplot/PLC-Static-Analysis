package dev.plca.rules.logic;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class NumSetTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void integerSetsTightenOpenEnds() {
        assertThat(NumSet.of(Rel.GT, d("5"), true).toString()).isEqualTo("[6, +inf)");
        assertThat(NumSet.of(Rel.LT, d("5.5"), true).toString()).isEqualTo("(-inf, 5]");
        assertThat(NumSet.of(Rel.EQ, d("2.5"), true).isEmpty()).isTrue();
        assertThat(NumSet.of(Rel.NE, d("3"), true).toString()).isEqualTo("(-inf, 2] | [4, +inf)");
    }

    @Test
    void denseSetsKeepOpenEnds() {
        assertThat(NumSet.of(Rel.GT, d("5"), false).toString()).isEqualTo("(5, +inf)");
        NumSet gap = NumSet.of(Rel.LT, d("0"), false)
                .union(NumSet.of(Rel.GT, d("0"), false))
                .complement();
        assertThat(gap.points(3)).containsExactly(d("0"));
    }

    @Test
    void intersectUnionComplement() {
        NumSet small = NumSet.closed(d("0"), d("10"), true);
        assertThat(small.intersect(NumSet.of(Rel.GT, d("10"), true)).isEmpty()).isTrue();
        assertThat(small.intersect(NumSet.of(Rel.GE, d("10"), true)).points(3)).containsExactly(d("10"));
        // touching integer ranges merge; dense ones only when an end is closed
        assertThat(NumSet.closed(d("1"), d("3"), true)
                        .union(NumSet.closed(d("4"), d("5"), true))
                        .toString())
                .isEqualTo("[1, 5]");
        assertThat(NumSet.of(Rel.LT, d("1"), false)
                        .union(NumSet.of(Rel.GT, d("1"), false))
                        .toString())
                .isEqualTo("(-inf, 1) | (1, +inf)");
        assertThat(NumSet.of(Rel.LE, d("1"), false)
                        .union(NumSet.of(Rel.GT, d("1"), false))
                        .isAll())
                .isTrue();
        assertThat(NumSet.all(true).complement().isEmpty()).isTrue();
        assertThat(NumSet.empty(false).complement().isAll()).isTrue();
    }

    @Test
    void pointsAreBounded() {
        assertThat(NumSet.closed(d("1"), d("3"), true).points(3)).containsExactly(d("1"), d("2"), d("3"));
        assertThat(NumSet.closed(d("1"), d("4"), true).points(3)).isNull();
        assertThat(NumSet.of(Rel.GE, d("1"), true).points(3)).isNull();
        assertThat(NumSet.closed(d("0"), d("1"), false).points(3)).isNull();
        assertThat(NumSet.empty(true).points(3)).isEqualTo(List.of());
    }
}
