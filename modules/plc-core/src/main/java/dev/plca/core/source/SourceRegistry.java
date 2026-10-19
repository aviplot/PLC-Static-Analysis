package dev.plca.core.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns every {@link SourceText} of an analysis run and resolves {@link Span}s to physical {@link Location}s. Safe for
 * concurrent registration from parser threads.
 *
 * <p>An editor session replaces files one at a time: {@link #without(Set)} derives a registry for the next version that
 * keeps the texts of every other file and shares the id counter, so ids are never reused and spans held by an older
 * snapshot still resolve in that snapshot's registry.
 */
public final class SourceRegistry {

    private final AtomicInteger nextId;
    private final ConcurrentMap<Integer, SourceText> texts = new ConcurrentHashMap<>();

    public SourceRegistry() {
        this(new AtomicInteger());
    }

    private SourceRegistry(AtomicInteger nextId) {
        this.nextId = nextId;
    }

    /** A new registry with every text of this one except those of the files whose path is in {@code paths}. */
    public SourceRegistry without(Set<String> paths) {
        SourceRegistry r = new SourceRegistry(nextId);
        texts.forEach((id, t) -> {
            if (!paths.contains(t.file().path())) {
                r.texts.put(id, t);
            }
        });
        return r;
    }

    /** The texts registered for the file with this display path, in id order. */
    public List<SourceText> textsOf(String path) {
        return texts.values().stream()
                .filter(t -> t.file().path().equals(path))
                .sorted(java.util.Comparator.comparingInt(SourceText::id))
                .toList();
    }

    /**
     * The text position at file offset {@code fileOffset} of the file {@code path}: the fragment that covers the offset
     * (for XML exports: the CDATA, {@code <Text>} or {@code <ST>} body), or null if no analysed text is there. When a
     * whole-file text and a fragment both cover it (an XML file registered whole for its XML diagnostics), the fragment
     * wins: names live in fragments.
     */
    public Position positionAt(String path, int fileOffset) {
        Position best = null;
        int bestSize = Integer.MAX_VALUE;
        for (SourceText t : textsOf(path)) {
            SourceMap m = t.map();
            if (m.fileFrom() < 0 || fileOffset < m.fileFrom() || fileOffset > m.fileTo()) {
                continue;
            }
            int o = m.toFragment(fileOffset);
            int size = m.fileTo() - m.fileFrom();
            if (o >= 0 && size < bestSize) {
                best = new Position(t, o);
                bestSize = size;
            }
        }
        return best;
    }

    /** An offset in a registered text. */
    public record Position(SourceText text, int offset) {}

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

    /** The distinct physical files of all registered texts, in registration order. */
    public List<SourceFile> files() {
        Set<SourceFile> files = Collections.newSetFromMap(new IdentityHashMap<>());
        List<SourceFile> r = new ArrayList<>();
        texts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (files.add(e.getValue().file())) {
                r.add(e.getValue().file());
            }
        });
        return r;
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
