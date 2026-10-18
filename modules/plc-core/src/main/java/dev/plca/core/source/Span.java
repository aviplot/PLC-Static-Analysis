package dev.plca.core.source;

/**
 * A half-open range {@code [start, end)} in the {@link SourceText} identified by {@code textId}.
 *
 * <p>Offsets are UTF-16 code units, i.e. Java {@code String} indices — the same unit as ANTLR token offsets (for BMP
 * text), LSP's default position encoding and SARIF's default {@code columnKind}. Spans stay in the coordinates of the
 * text the lexer saw; {@link SourceRegistry#resolve(Span)} maps them to the physical file only when reporting.
 */
public record Span(int textId, int start, int end) {

    public Span {
        if (textId < 0 || start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span " + textId + ":[" + start + "," + end + ")");
        }
    }

    public static Span of(int textId, int start, int end) {
        return new Span(textId, start, end);
    }

    /** An empty span at {@code offset}, e.g. for "missing token" diagnostics. */
    public static Span at(int textId, int offset) {
        return new Span(textId, offset, offset);
    }

    public int length() {
        return end - start;
    }

    public boolean isEmpty() {
        return start == end;
    }

    public boolean contains(int offset) {
        return offset >= start && offset < end;
    }

    /** Smallest span covering both; both must belong to the same text. */
    public Span union(Span other) {
        if (other.textId != textId) {
            throw new IllegalArgumentException("spans belong to different texts: " + this + ", " + other);
        }
        return new Span(textId, Math.min(start, other.start), Math.max(end, other.end));
    }
}
