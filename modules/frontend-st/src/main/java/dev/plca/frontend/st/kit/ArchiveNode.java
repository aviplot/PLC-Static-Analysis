package dev.plca.frontend.st.kit;

import java.util.ArrayList;
import java.util.List;

/**
 * One element of a CODESYS {@code <XmlArchive>} (the serialised object form TwinCAT uses for NWL, CFC and SFC bodies,
 * and CODESYS for its PLCopen CFC addData): {@code <o t="Type">} objects, {@code <v n="Name">} values, {@code <l2
 * n="Name" cet="Type">} lists and {@code <n n="Name"/>} nulls ({@code docs/grammar/codesys.md} §9). Text is never
 * copied: a value is decoded from the file by offset ({@link ArchiveReader#text}).
 */
public final class ArchiveNode {

    /** {@code o}, {@code v}, {@code l2}, {@code n} (or any other tag, kept as is). */
    public final String tag;
    /** The {@code n} attribute: the member name in the owning object; null for list items. */
    public final String name;
    /** The object type: {@code t}, else the owning list's {@code cet}; null when neither is given. */
    public final String type;
    /** Offset of the {@code <} of the start tag in the file. */
    public final int offset;
    /** Content between the start and the end tag in the file; both -1 for an empty element. */
    int contentStart = -1;

    int contentEnd = -1;
    /** A list's {@code cet}: the type of its items. */
    String listType;

    final List<ArchiveNode> children = new ArrayList<>(4);

    ArchiveNode(String tag, String name, String type, int offset) {
        this.tag = tag;
        this.name = name;
        this.type = type;
        this.offset = offset;
    }

    public List<ArchiveNode> children() {
        return children;
    }

    public int contentStart() {
        return contentStart;
    }

    public int contentEnd() {
        return contentEnd;
    }

    public boolean isNull() {
        return tag.equals("n");
    }

    /** The member {@code name} (an object, value, list or null), or null when absent. */
    public ArchiveNode member(String name) {
        for (ArchiveNode c : children) {
            if (name.equals(c.name)) {
                return c;
            }
        }
        return null;
    }

    /** The items of the list member {@code name}; empty when absent or null. */
    public List<ArchiveNode> list(String name) {
        ArchiveNode l = member(name);
        return l == null ? List.of() : l.children;
    }

    /** {@code member(a).member(b)…}, null as soon as one is missing. */
    public ArchiveNode path(String... names) {
        ArchiveNode n = this;
        for (String s : names) {
            n = n == null ? null : n.member(s);
        }
        return n;
    }

    /** Every object of type {@code type} below this one (pre-order), this one included. */
    public List<ArchiveNode> descendants(String type) {
        List<ArchiveNode> out = new ArrayList<>();
        collect(type, out);
        return out;
    }

    private void collect(String t, List<ArchiveNode> out) {
        if (t.equals(type)) {
            out.add(this);
        }
        for (ArchiveNode c : children) {
            c.collect(t, out);
        }
    }

    @Override
    public String toString() {
        return "<" + tag + (name == null ? "" : " n=" + name) + (type == null ? "" : " t=" + type) + "@" + offset + ">";
    }
}
