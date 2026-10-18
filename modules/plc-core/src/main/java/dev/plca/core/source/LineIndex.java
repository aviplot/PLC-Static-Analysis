package dev.plca.core.source;

import java.util.Arrays;

/**
 * Offset to (line, column) lookup over a text. Recognises {@code \n}, {@code \r\n} and a bare {@code \r} (old Mac-style
 * exports still occur) as line terminators. Lines and columns are 1-based; columns count UTF-16 code units.
 */
public final class LineIndex {

    private final int[] lineStarts;
    private final int length;

    private LineIndex(int[] lineStarts, int length) {
        this.lineStarts = lineStarts;
        this.length = length;
    }

    public static LineIndex of(CharSequence text) {
        int n = text.length();
        int[] starts = new int[Math.max(16, n / 32)];
        int count = 0;
        starts[count++] = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, count * 2);
                }
                starts[count++] = i + 1;
            }
        }
        return new LineIndex(Arrays.copyOf(starts, count), n);
    }

    /** Number of lines; a trailing terminator starts a final empty line, as editors show it. */
    public int lineCount() {
        return lineStarts.length;
    }

    /** 1-based line containing {@code offset}; {@code offset == length} (end of text) is allowed. */
    public int line(int offset) {
        checkOffset(offset);
        int i = Arrays.binarySearch(lineStarts, offset);
        return (i >= 0 ? i : -i - 2) + 1;
    }

    /** 1-based column of {@code offset} within its line. */
    public int column(int offset) {
        return offset - lineStarts[line(offset) - 1] + 1;
    }

    /** Offset of the first character of a 1-based line. */
    public int lineStart(int line) {
        if (line < 1 || line > lineStarts.length) {
            throw new IndexOutOfBoundsException("line " + line + " of " + lineStarts.length);
        }
        return lineStarts[line - 1];
    }

    /** Inverse of {@link #line}/{@link #column}; columns past the line end are not clamped. */
    public int offset(int line, int column) {
        return lineStart(line) + column - 1;
    }

    private void checkOffset(int offset) {
        if (offset < 0 || offset > length) {
            throw new IndexOutOfBoundsException("offset " + offset + " of " + length);
        }
    }
}
