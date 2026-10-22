package dev.plca.frontend.st.kit;

import dev.plca.core.source.XmlText;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.codehaus.stax2.XMLStreamReader2;

/**
 * Reads one CODESYS {@code <XmlArchive>} region of a file into {@link ArchiveNode}s with file offsets. Only that region
 * is held in memory (one body at a time), never the whole file. Values are decoded on demand by {@link #text}.
 */
public final class ArchiveReader {

    /**
     * The archive's root object ({@code Data/o}); null when there is none, with {@code error} set when the region is
     * malformed.
     */
    public record Result(ArchiveNode root, String error, int errorOffset) {}

    private final String file;

    /** {@code fileText}: the whole decoded file the offsets refer to. */
    public ArchiveReader(String fileText) {
        this.file = fileText;
    }

    /** Reads {@code file[start, end)}, the content of an implementation element ({@code <NWL>}, {@code <CFC>}, …). */
    public Result read(int start, int end) {
        Deque<ArchiveNode> open = new ArrayDeque<>();
        ArchiveNode top = new ArchiveNode("#region", null, null, start);
        open.push(top);
        XMLStreamReader2 r = null;
        try {
            r = (XMLStreamReader2)
                    XmlInput.factory().createXMLStreamReader(new StringReader(file.substring(start, end)));
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    int at = start + (int) r.getLocationInfo().getStartingCharOffset();
                    ArchiveNode parent = open.peek();
                    String type = r.getAttributeValue(null, "t");
                    if (type == null && parent.tag.equals("l2")) {
                        type = parent.listType;
                    }
                    ArchiveNode n = new ArchiveNode(r.getLocalName(), r.getAttributeValue(null, "n"), type, at);
                    n.listType = r.getAttributeValue(null, "cet");
                    parent.children.add(n);
                    open.push(n);
                    n.contentStart = XmlInput.endOfStartTag(file, at);
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    ArchiveNode n = open.pop();
                    int at = start + (int) r.getLocationInfo().getStartingCharOffset();
                    if (at <= n.contentStart) {
                        n.contentStart = -1; // <x/>
                    } else {
                        n.contentEnd = at;
                    }
                }
            }
        } catch (XMLStreamException e) {
            int offset =
                    e.getLocation() == null ? 0 : Math.max(0, e.getLocation().getCharacterOffset());
            String msg = e.getMessage() == null
                    ? "malformed XML"
                    : e.getMessage().lines().findFirst().orElse("");
            return new Result(null, msg, Math.min(start + offset, end));
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (XMLStreamException ignored) {
                    // nothing to release for a StringReader
                }
            }
        }
        ArchiveNode data = find(top, "Data");
        ArchiveNode root = null;
        if (data != null) {
            for (ArchiveNode c : data.children) {
                if (c.tag.equals("o")) {
                    root = c;
                    break;
                }
            }
        }
        // no Data object (an empty archive): nothing to read, and nothing wrong with the file
        return new Result(root, null, -1);
    }

    private static ArchiveNode find(ArchiveNode n, String tag) {
        for (ArchiveNode c : n.children) {
            if (c.tag.equals(tag)) {
                return c;
            }
            ArchiveNode d = find(c, tag);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /** The decoded content of a value element ({@code <v>}); "" for an empty or missing one. */
    public String text(ArchiveNode v) {
        if (v == null || v.contentStart < 0) {
            return "";
        }
        for (int i = v.contentStart; i < v.contentEnd; i++) {
            char c = file.charAt(i);
            if (c == '&' || c == '<') {
                return XmlText.decode(file, v.contentStart, v.contentEnd).text();
            }
        }
        return file.substring(v.contentStart, v.contentEnd);
    }

    /** A string value without its quotes ({@code "abc"} → {@code abc}); "" for a null or missing one. */
    public String string(ArchiveNode v) {
        String t = text(v);
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    /** {@code string(owner.member(name))}. */
    public String string(ArchiveNode owner, String name) {
        return owner == null ? "" : string(owner.member(name));
    }

    /** A boolean value ({@code true}/{@code "TRUE"}, any case); false when missing. */
    public boolean bool(ArchiveNode owner, String name) {
        return owner != null && string(owner.member(name)).equalsIgnoreCase("true");
    }

    /** An integer value ({@code 12}, {@code 12L}); {@code fallback} when missing or not a number. */
    public long number(ArchiveNode owner, String name, long fallback) {
        String t = owner == null ? "" : string(owner.member(name)).strip();
        if (t.endsWith("L") || t.endsWith("l")) {
            t = t.substring(0, t.length() - 1);
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * The file range of a string value's characters without its quotes, or null when the value is empty, missing or not
     * quoted. Expressions are parsed from this range so spans point into the file.
     */
    public int[] stringRange(ArchiveNode v) {
        if (v == null || v.contentStart < 0) {
            return null;
        }
        int s = v.contentStart;
        int e = v.contentEnd;
        if (e - s >= 2 && file.charAt(s) == '"' && file.charAt(e - 1) == '"') {
            return e - s == 2 ? null : new int[] {s + 1, e - 1};
        }
        return e > s ? new int[] {s, e} : null;
    }
}
