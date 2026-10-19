package dev.plca.frontend.plcopen;

import java.util.ArrayList;
import java.util.List;

/**
 * A light element of one PLCopen unit (a POU, data type, configuration or global variable list): tag, attributes with
 * the offsets of their raw values, children, the offsets of its content, and the coordinates of a {@code position}
 * child. Text is never copied; it is decoded from the file by offset when needed.
 */
final class XNode {

    /** An attribute's value (entities resolved) and the range of its raw text, {@code start < 0} when unknown. */
    record Attr(String value, int start, int end) {}

    final String tag;
    /** Offset of the {@code <} of the start tag. */
    final int offset;

    final List<String> names = new ArrayList<>(4);
    final List<Attr> values = new ArrayList<>(4);
    final List<XNode> children = new ArrayList<>(2);
    /** Content between the start and the end tag; both -1 for an empty element. */
    int contentStart = -1;

    int contentEnd = -1;
    /** Coordinates from a {@code <position x y/>} child; {@code Integer.MIN_VALUE} when absent. */
    int x = Integer.MIN_VALUE;

    int y = Integer.MIN_VALUE;
    /** Points of a {@code connection}'s {@code position} children (x, y pairs), or null. */
    int[] route;

    int routeLength;

    XNode(String tag, int offset) {
        this.tag = tag;
        this.offset = offset;
    }

    void addRoutePoint(int px, int py) {
        if (route == null) {
            route = new int[8];
        } else if (routeLength + 2 > route.length) {
            route = java.util.Arrays.copyOf(route, route.length * 2);
        }
        route[routeLength++] = px;
        route[routeLength++] = py;
    }

    String attr(String name) {
        Attr a = attribute(name);
        return a == null ? null : a.value();
    }

    Attr attribute(String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(name)) {
                return values.get(i);
            }
        }
        return null;
    }

    boolean flag(String name) {
        return "true".equalsIgnoreCase(attr(name));
    }

    XNode child(String t) {
        for (XNode c : children) {
            if (c.tag.equals(t)) {
                return c;
            }
        }
        return null;
    }

    List<XNode> children(String t) {
        List<XNode> out = new ArrayList<>();
        for (XNode c : children) {
            if (c.tag.equals(t)) {
                out.add(c);
            }
        }
        return out;
    }

    /** {@code child(a).child(b)…}, null as soon as one is missing. */
    XNode path(String... tags) {
        XNode n = this;
        for (String t : tags) {
            n = n == null ? null : n.child(t);
        }
        return n;
    }

    /** The first element child, or null. */
    XNode first() {
        return children.isEmpty() ? null : children.getFirst();
    }

    @Override
    public String toString() {
        return "<" + tag + "@" + offset + ">";
    }
}
