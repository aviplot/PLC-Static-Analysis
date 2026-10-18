package dev.plca.core.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Golden-file (snapshot) assertions. Expected output lives under the module's {@code src/test/resources}; run
 * {@code ./gradlew test -Pgolden.update} to (re)write the files, then review the diff before committing.
 *
 * <p>Line endings are normalised to {@code \n} so goldens are stable across platforms.
 */
public final class Golden {

    private Golden() {}

    /** Compares against {@code src/test/resources/<relativePath>} of the module under test. */
    public static void assertMatches(String actual, String relativePath) {
        String root = System.getProperty("golden.root");
        if (root == null) {
            fail("system property golden.root not set; apply the plca.java convention plugin");
        }
        assertMatches(actual, Path.of(root).resolve(relativePath), Boolean.getBoolean("golden.update"));
    }

    public static void assertMatches(String actual, Path golden, boolean update) {
        String normalised = normalise(actual);
        try {
            if (update) {
                Files.createDirectories(golden.toAbsolutePath().getParent());
                Files.writeString(golden, normalised, StandardCharsets.UTF_8);
                return;
            }
            if (!Files.exists(golden)) {
                fail("golden file " + golden + " does not exist; run with -Pgolden.update to create it");
            }
            String expected = normalise(Files.readString(golden, StandardCharsets.UTF_8));
            assertThat(normalised)
                    .as("output differs from %s (run with -Pgolden.update to accept)", golden)
                    .isEqualTo(expected);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalise(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }
}
