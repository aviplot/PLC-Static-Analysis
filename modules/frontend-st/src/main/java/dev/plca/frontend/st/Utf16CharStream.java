package dev.plca.frontend.st;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.misc.Interval;

/**
 * A {@link CharStream} indexed in UTF-16 code units, unlike ANTLR's {@code CodePointCharStream}. Token offsets then
 * equal {@code String} indices and {@code Span} offsets, also when comments contain characters outside the BMP. Our
 * grammars only need ASCII for tokens and treat everything else as identifier letters or comment content, so surrogate
 * halves arriving separately is harmless.
 */
final class Utf16CharStream implements CharStream {

    private final String text;
    private final String sourceName;
    private int index;

    Utf16CharStream(String text, String sourceName) {
        this.text = text;
        this.sourceName = sourceName;
    }

    @Override
    public void consume() {
        if (index >= text.length()) {
            throw new IllegalStateException("cannot consume EOF");
        }
        index++;
    }

    @Override
    public int LA(int i) {
        if (i == 0) {
            return 0;
        }
        int pos = i < 0 ? index + i : index + i - 1;
        if (pos < 0 || pos >= text.length()) {
            return IntStream.EOF;
        }
        return text.charAt(pos);
    }

    @Override
    public int mark() {
        return -1;
    }

    @Override
    public void release(int marker) {}

    @Override
    public int index() {
        return index;
    }

    @Override
    public void seek(int index) {
        this.index = Math.min(Math.max(index, 0), text.length());
    }

    @Override
    public int size() {
        return text.length();
    }

    @Override
    public String getSourceName() {
        return sourceName;
    }

    @Override
    public String getText(Interval interval) {
        int start = Math.max(interval.a, 0);
        int stop = Math.min(interval.b, text.length() - 1);
        return start > stop ? "" : text.substring(start, stop + 1);
    }
}
