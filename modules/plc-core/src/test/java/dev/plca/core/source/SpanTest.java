package dev.plca.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SpanTest {

    @Test
    void basics() {
        Span s = Span.of(1, 3, 7);
        assertThat(s.length()).isEqualTo(4);
        assertThat(s.contains(3)).isTrue();
        assertThat(s.contains(7)).isFalse();
        assertThat(Span.at(1, 5).isEmpty()).isTrue();
        assertThat(s.union(Span.of(1, 10, 12))).isEqualTo(Span.of(1, 3, 12));
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> Span.of(0, 5, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Span.of(0, -1, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Span.of(0, 1, 2).union(Span.of(1, 1, 2))).isInstanceOf(IllegalArgumentException.class);
    }
}
