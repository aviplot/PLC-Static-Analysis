package dev.plca.lsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UrisTest {

    @TempDir
    Path root;

    @Test
    void mappedUrisRoundTripAndStayInsideTheRoot() {
        Uris u = Uris.mapped("file:///project/", root);
        String uri = u.uri(root, "Line 1/Main #2 (ä).st");
        assertThat(uri).isEqualTo("file:///project/Line%201/Main%20%232%20(%C3%A4).st");
        assertThat(u.path(uri)).isEqualTo(root.resolve("Line 1/Main #2 (ä).st"));
        assertThat(u.path("file:///project/a+b.st")).isEqualTo(root.resolve("a+b.st"));
        assertThat(u.path("file:///project")).isEqualTo(root.toAbsolutePath().normalize());

        assertThat(u.path("file:///project/../etc/passwd")).isNull();
        assertThat(u.path("file:///project/%2E%2E/etc/passwd")).isNull();
        assertThat(u.path("file:///project/a/../../x")).isNull();
        assertThat(u.path("file:///project//etc/passwd")).isNull();
        assertThat(u.path("file:///project/a%5C..%5C..%5Cx")).isNull();
        assertThat(u.path("file:///project/a%00b")).isNull();
        assertThat(u.path("file:///etc/passwd")).isNull();
        assertThat(u.path("untitled:Untitled-1")).isNull();
        assertThat(u.path(null)).isNull();
    }

    @Test
    void masksTheRootInMessages() {
        Uris u = Uris.mapped("file:///project/", root);
        String r = root.toAbsolutePath().normalize().toString();
        assertThat(u.mask("cannot read " + r + "/base.json: no such file"))
                .isEqualTo("cannot read base.json: no such file");
        assertThat(u.mask("loaded " + r + " in 3 ms")).isEqualTo("loaded . in 3 ms");
        assertThat(Uris.files().mask("x " + r)).isEqualTo("x " + r);
    }
}
