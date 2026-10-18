package dev.plca.core.source;

import java.util.Objects;

/**
 * Text handed to a lexer: a whole {@link SourceFile}, or a fragment extracted from one (ST inside TwinCAT CDATA, an L5X
 * rung {@code <Text>}, a PLCopen {@code <ST>} body) together with the {@link SourceMap} back to the file. Created and
 * numbered by {@link SourceRegistry}; {@link Span}s refer to it by {@link #id()}.
 */
public final class SourceText {

    private final int id;
    private final SourceFile file;
    private final String text;
    private final SourceMap map;

    SourceText(int id, SourceFile file, String text, SourceMap map) {
        if (map.fragmentLength() != text.length()) {
            throw new IllegalArgumentException(
                    "source map covers " + map.fragmentLength() + " chars, text has " + text.length());
        }
        this.id = id;
        this.file = Objects.requireNonNull(file, "file");
        this.text = text;
        this.map = map;
    }

    public int id() {
        return id;
    }

    public SourceFile file() {
        return file;
    }

    public String text() {
        return text;
    }

    public SourceMap map() {
        return map;
    }

    /** A span over {@code [start, end)} of this text. */
    public Span span(int start, int end) {
        return new Span(id, start, end);
    }

    @Override
    public String toString() {
        return "SourceText#" + id + "(" + file.path() + ", " + text.length() + " chars)";
    }
}
