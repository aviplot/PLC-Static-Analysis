package dev.plca.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.plca.core.testing.Golden;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoldenTest {

    @TempDir
    Path dir;

    @Test
    void updateWritesThenCompareMatchesIgnoringLineEndings() throws Exception {
        Path g = dir.resolve("sub/out.txt");
        Golden.assertMatches("a\r\nb\n", g, true);
        assertThat(Files.readString(g)).isEqualTo("a\nb\n");
        Golden.assertMatches("a\nb\n", g, false);
    }

    @Test
    void mismatchAndMissingFail() {
        Path g = dir.resolve("out.txt");
        assertThatThrownBy(() -> Golden.assertMatches("x", g, false)).hasMessageContaining("-Pgolden.update");
        Golden.assertMatches("x", g, true);
        assertThatThrownBy(() -> Golden.assertMatches("y", g, false)).isInstanceOf(AssertionError.class);
    }
}
