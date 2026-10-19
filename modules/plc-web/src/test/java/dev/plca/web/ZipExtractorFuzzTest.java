package dev.plca.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Fuzzes the upload extractor (M9) with archives built from fuzzed entry names and contents, and with raw fuzzed bytes.
 * Invariants: it refuses or extracts, never anything else; every file it writes is inside the project directory, is a
 * regular file, and is one it reports; the extracted total respects the limit. {@code JAZZER_FUZZ=1} to fuzz; the build
 * runs the seeds.
 */
class ZipExtractorFuzzTest {

    private static final WebOptions.Limits LIMITS = new WebOptions.Limits(1 << 20, 4 << 20, 64, 100, 4);
    private static final List<String> PREFIXES =
            List.of("", "../", "/", "a/../", "a/../../", "C:/", "a\\..\\", "./", "a//b/", "__MACOSX/", "a/./");

    @FuzzTest(maxDuration = "5m")
    void extractsOnlyInsideTheDirectory(FuzzedDataProvider data) throws Exception {
        byte[] archive;
        if (data.consumeBoolean()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(out)) {
                int n = data.consumeInt(0, 8);
                for (int i = 0; i < n; i++) {
                    String name =
                            data.pickValue(PREFIXES) + data.consumeString(40) + (data.consumeBoolean() ? ".st" : "");
                    try {
                        z.putNextEntry(new ZipEntry(name));
                    } catch (Exception e) {
                        continue; // a duplicate or invalid name for the writer
                    }
                    z.write(data.consumeBytes(data.consumeInt(0, 4096)));
                    z.closeEntry();
                }
            } catch (IOException | IllegalArgumentException e) {
                return;
            }
            archive = out.toByteArray();
        } else {
            archive = data.consumeRemainingAsBytes();
        }
        Path root = Files.createTempDirectory("plca-zip-fuzz");
        Path dir = Files.createDirectory(root.resolve("p"));
        try {
            ZipExtractor.Result r;
            try {
                r = ZipExtractor.extract(new ByteArrayInputStream(archive), dir, LIMITS, p -> true);
            } catch (ZipExtractor.UploadException e) {
                r = null;
            }
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.toList()) {
                    assertThat(p.equals(root) || p.startsWith(dir))
                            .as(p.toString())
                            .isTrue();
                    if (!Files.isDirectory(p)) {
                        assertThat(Files.isSymbolicLink(p)).isFalse();
                        if (r != null) {
                            assertThat(r.files())
                                    .contains(dir.relativize(p).toString().replace('\\', '/'));
                        }
                    }
                }
            }
            if (r != null) {
                assertThat(r.bytes()).isLessThanOrEqualTo(LIMITS.maxExtractedBytes());
            }
        } finally {
            Projects.deleteTree(root);
        }
    }
}
