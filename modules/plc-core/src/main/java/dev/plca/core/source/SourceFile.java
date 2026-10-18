package dev.plca.core.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A physical input file as the engineer sees it in the IDE: display path and decoded text. Offsets into {@link #text()}
 * are what findings are finally reported against.
 */
public final class SourceFile {

    private final String path;
    private final String text;
    private final Charset charset;
    private volatile LineIndex lineIndex;

    public SourceFile(String path, String text, Charset charset) {
        this.path = Objects.requireNonNull(path, "path");
        this.text = Objects.requireNonNull(text, "text");
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    /** Reads and decodes a file; {@code displayPath} is what reports show (normally relative to the project root). */
    public static SourceFile read(Path file, String displayPath) {
        try {
            return fromBytes(displayPath, Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static SourceFile fromBytes(String displayPath, byte[] data) {
        TextDecoder.Decoded d = TextDecoder.decode(data);
        return new SourceFile(displayPath, d.text(), d.charset());
    }

    public static SourceFile of(String displayPath, String text) {
        return new SourceFile(displayPath, text, StandardCharsets.UTF_8);
    }

    public String path() {
        return path;
    }

    public String text() {
        return text;
    }

    public Charset charset() {
        return charset;
    }

    public LineIndex lineIndex() {
        LineIndex li = lineIndex;
        if (li == null) {
            // benign race: LineIndex is immutable, at worst built twice
            li = LineIndex.of(text);
            lineIndex = li;
        }
        return li;
    }

    @Override
    public String toString() {
        return path;
    }
}
