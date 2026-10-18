package dev.plca.core.source;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns every {@link SourceText} of an analysis run and resolves {@link Span}s to physical {@link Location}s. Safe for
 * concurrent registration from parser threads.
 */
public final class SourceRegistry {

    private final AtomicInteger nextId = new AtomicInteger();
    private final ConcurrentMap<Integer, SourceText> texts = new ConcurrentHashMap<>();

    /** Registers a whole file as one text. */
    public SourceText register(SourceFile file) {
        return add(file, file.text(), SourceMap.verbatim(0, file.text().length()));
    }

    /** Registers a fragment of {@code file}; {@code map} leads from fragment offsets back into the file. */
    public SourceText registerFragment(SourceFile file, String text, SourceMap map) {
        return add(file, text, map);
    }

    /** Decodes the XML character content {@code file.text()[start, end)} and registers it as a fragment. */
    public SourceText registerXmlContent(SourceFile file, int start, int end) {
        XmlText.Fragment f = XmlText.decode(file.text(), start, end);
        return add(file, f.text(), f.map());
    }

    public SourceText text(int id) {
        SourceText t = texts.get(id);
        if (t == null) {
            throw new IllegalArgumentException("unknown text id " + id);
        }
        return t;
    }

    public int size() {
        return texts.size();
    }

    /** Maps a span to the physical file and converts it to lines and columns. */
    public Location resolve(Span span) {
        Objects.requireNonNull(span, "span");
        SourceText t = text(span.textId());
        int start = t.map().toFileStart(span.start());
        int end = span.isEmpty() ? start : Math.max(start, t.map().toFileEnd(span.end()));
        LineIndex li = t.file().lineIndex();
        return new Location(t.file().path(), li.line(start), li.column(start), li.line(end), li.column(end));
    }

    private SourceText add(SourceFile file, String text, SourceMap map) {
        int id = nextId.getAndIncrement();
        SourceText t = new SourceText(id, file, text, map);
        texts.put(id, t);
        return t;
    }
}
