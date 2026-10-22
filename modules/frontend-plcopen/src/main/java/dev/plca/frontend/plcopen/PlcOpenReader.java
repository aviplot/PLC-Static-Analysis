package dev.plca.frontend.plcopen;

import dev.plca.frontend.st.kit.XmlInput;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.function.Consumer;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.codehaus.stax2.XMLStreamReader2;

/**
 * Streams a PLCopen TC6 file with StAX and hands each top-level unit — {@code fileHeader}, {@code dataType},
 * {@code pou}, {@code configuration}, and CODESYS global variable lists in project-level addData — to a consumer as a
 * small {@link XNode} tree, then forgets it. Memory is bounded by the largest unit, not the file (exports reach 36 MB).
 *
 * <p>Not stored: documentation, the layout of connections, and addData the translator does not use. Element names are
 * local names: {@code xhtml:p} is {@code p}.
 */
final class PlcOpenReader {

    /** Units handed to the consumer, by tag. */
    private static final Set<String> UNITS = Set.of("fileHeader", "dataType", "pou", "configuration");

    /** addData the translator reads (by the end of its {@code name}); everything else is skipped. */
    private static final Set<String> KEPT_DATA = Set.of(
            "interfaceasplaintext",
            "method",
            "property",
            "pouinheritance",
            "attributes",
            "tasksettings",
            "globalvars",
            "accessmodifiers",
            "cfc",
            "cfcelementtype");

    private static final Set<String> SKIPPED = Set.of("documentation", "comment", "vendorElement");

    /** Result: the first well-formedness error, if any (units read before it were delivered). */
    record Result(String error, int errorOffset) {}

    private final String xml;
    private final Consumer<XNode> units;
    private final Deque<XNode> open = new ArrayDeque<>();
    private int skipDepth;
    private int depth;
    /** Depth of the project-level {@code addData/data} holding global variable lists, or -1. */
    private XMLStreamReader2 r;

    private PlcOpenReader(String xml, Consumer<XNode> units) {
        this.xml = xml;
        this.units = units;
    }

    static Result read(String xml, Consumer<XNode> units) {
        return new PlcOpenReader(xml, units).run();
    }

    private Result run() {
        try {
            r = (XMLStreamReader2) XmlInput.factory().createXMLStreamReader(new StringReader(xml));
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    start();
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    end();
                }
            }
            return new Result(null, -1);
        } catch (XMLStreamException e) {
            int offset =
                    e.getLocation() == null ? 0 : Math.max(0, e.getLocation().getCharacterOffset());
            String msg = e.getMessage() == null
                    ? "malformed XML"
                    : e.getMessage().lines().findFirst().orElse("malformed XML");
            return new Result(msg, Math.min(offset, xml.length()));
        } catch (RuntimeException e) {
            return new Result(String.valueOf(e.getMessage()), 0);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (XMLStreamException ignored) {
                    // nothing to release for a StringReader
                }
            }
        }
    }

    private void start() throws XMLStreamException {
        depth++;
        if (skipDepth > 0) {
            skipDepth++;
            return;
        }
        String tag = r.getLocalName();
        int at = (int) r.getLocationInfo().getStartingCharOffset();
        XNode parent = open.peek();
        if (parent == null) {
            boolean unit = UNITS.contains(tag) || tag.equals("globalVars") && depth > 2;
            if (!unit) {
                return; // structure around the units: project, types, pous, instances, …
            }
        } else {
            // CODESYS CFC connection marks are vendor elements (plcopen.md §5a); elsewhere they are network titles
            boolean cfcMark = tag.equals("vendorElement") && parent.tag.equals("CFC");
            if (SKIPPED.contains(tag) && !cfcMark || tag.equals("data") && !kept(r.getAttributeValue(null, "name"))) {
                skipDepth = 1;
                return;
            }
            if (tag.equals("position") || tag.equals("relPosition")) {
                // an element's position, a connection point's relative position, or a point of a wire
                if (parent.tag.equals("connection")) {
                    parent.addRoutePoint(intAttr("x"), intAttr("y"));
                } else {
                    parent.x = intAttr("x");
                    parent.y = intAttr("y");
                }
                skipDepth = 1;
                return;
            }
        }
        XNode n = new XNode(tag, at);
        for (int i = 0; i < r.getAttributeCount(); i++) {
            String name = r.getAttributeLocalName(i);
            int[] range = XmlInput.attributeRange(xml, at, name);
            n.names.add(name);
            n.values.add(new XNode.Attr(
                    r.getAttributeValue(i), range == null ? -1 : range[0], range == null ? -1 : range[1]));
        }
        if (!r.isEmptyElement()) {
            n.contentStart = XmlInput.endOfStartTag(xml, at);
        }
        if (parent != null) {
            parent.children.add(n);
        }
        open.push(n);
    }

    private void end() {
        depth--;
        if (skipDepth > 0) {
            skipDepth--;
            return;
        }
        XNode n = open.poll();
        if (n == null) {
            return;
        }
        if (n.contentStart >= 0) {
            n.contentEnd = Math.max(n.contentStart, (int) r.getLocationInfo().getStartingCharOffset());
        }
        if (open.isEmpty()) {
            units.accept(n);
        }
    }

    private static boolean kept(String name) {
        if (name == null) {
            return true;
        }
        int slash = name.lastIndexOf('/');
        return KEPT_DATA.contains(name.substring(slash + 1).toLowerCase(java.util.Locale.ROOT));
    }

    private int intAttr(String name) {
        String v = r.getAttributeValue(null, name);
        if (v == null) {
            return Integer.MIN_VALUE;
        }
        try {
            return (int) Math.round(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }
}
