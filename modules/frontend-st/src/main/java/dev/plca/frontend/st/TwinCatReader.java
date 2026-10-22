package dev.plca.frontend.st;

import dev.plca.frontend.st.kit.XmlInput;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.codehaus.stax2.XMLStreamReader2;

/**
 * Reads the structure of a TwinCAT {@code .TcPOU/.TcDUT/.TcGVL/.TcIO} file: objects (POU, DUT, GVL, Itf), their members
 * (Method, Property with Get/Set, Action, Transition) and the <em>file offsets</em> of each {@code <Declaration>} and
 * {@code <Implementation><ST>} content. The content itself is decoded later by {@code XmlText}, which keeps an exact
 * source map; StAX is used only for structure and offsets.
 *
 * <p>Woodstox reports exact character offsets when reading from a {@code Reader}; we read from the already decoded file
 * text, so its offsets are the {@code String} indices our spans use.
 */
final class TwinCatReader {

    /** An object or member element with its interesting content ranges ({@code -1} when absent). */
    static final class Element {
        final String tag;
        final String name;
        int declStart = -1;
        int declEnd = -1;
        int implStart = -1;
        int implEnd = -1;
        /** Child element of {@code <Implementation>}: {@code ST}, {@code NWL}, {@code SFC}, … */
        String implLanguage;
        /** Content of a graphical implementation element ({@code <NWL>}, {@code <CFC>}, {@code <SFC>}), else -1. */
        int graphStart = -1;

        int graphEnd = -1;
        /** The body language once a graphical body is read ({@code LD}/{@code FBD} for NWL), else null. */
        String language;
        /** Implicit variables a graphical body adds to its POU (SFC step flags), else null. */
        dev.plca.ast.VarBlock extraBlock;

        final List<Element> children = new ArrayList<>();
        final int offset;

        Element(String tag, String name, int offset) {
            this.tag = tag;
            this.name = name;
            this.offset = offset;
        }

        boolean hasDeclaration() {
            return declStart >= 0;
        }

        boolean hasStImplementation() {
            return implStart >= 0;
        }
    }

    /** Result: top-level objects, or the failure point if the XML is malformed (objects read so far are kept). */
    record Result(List<Element> objects, String error, int errorOffset) {}

    private static final Set<String> OBJECTS = Set.of("POU", "DUT", "GVL", "Itf");
    private static final Set<String> MEMBERS = Set.of("Method", "Property", "Get", "Set", "Action", "Transition");

    static final XMLInputFactory FACTORY = XmlInput.factory();

    private TwinCatReader() {}

    static Result read(String xml) {
        List<Element> objects = new ArrayList<>();
        Deque<Element> owners = new ArrayDeque<>();
        Deque<String> path = new ArrayDeque<>();
        int contentStart = -1;
        String contentOf = null; // "decl" or "impl"
        int graphDepth = -1; // depth of the open graphical implementation element
        int graphStart = -1;
        XMLStreamReader2 r = null;
        try {
            r = (XMLStreamReader2) FACTORY.createXMLStreamReader(new StringReader(xml));
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String tag = r.getLocalName();
                    int at = (int) r.getLocationInfo().getStartingCharOffset();
                    String parent = path.peek();
                    path.push(tag);
                    if (OBJECTS.contains(tag) && owners.isEmpty() || MEMBERS.contains(tag) && !owners.isEmpty()) {
                        Element e = new Element(tag, r.getAttributeValue(null, "Name"), at);
                        if (owners.isEmpty()) {
                            objects.add(e);
                        } else {
                            owners.peek().children.add(e);
                        }
                        owners.push(e);
                    } else if (!owners.isEmpty() && tag.equals("Declaration") && isOwnerTag(parent)) {
                        contentStart = endOfStartTag(xml, at);
                        contentOf = "decl";
                        if (r.isEmptyElement()) {
                            owners.peek().declStart = contentStart;
                            owners.peek().declEnd = contentStart;
                            contentOf = null;
                        }
                    } else if (!owners.isEmpty() && "Implementation".equals(parent)) {
                        Element owner = owners.peek();
                        if (owner.implLanguage == null) {
                            owner.implLanguage = tag;
                        }
                        if (!tag.equals("ST") && owner.implLanguage.equals(tag) && !r.isEmptyElement()) {
                            graphDepth = path.size();
                            graphStart = endOfStartTag(xml, at);
                        }
                        if (tag.equals("ST")) {
                            contentStart = endOfStartTag(xml, at);
                            contentOf = "impl";
                            if (r.isEmptyElement()) {
                                owner.implStart = contentStart;
                                owner.implEnd = contentStart;
                                contentOf = null;
                            }
                        }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    int depth = path.size();
                    String tag = path.pop();
                    int at = (int) r.getLocationInfo().getStartingCharOffset();
                    if (depth == graphDepth && !owners.isEmpty()) {
                        owners.peek().graphStart = graphStart;
                        owners.peek().graphEnd = Math.max(graphStart, at);
                        graphDepth = -1;
                    }
                    if (contentOf != null && (tag.equals("Declaration") || tag.equals("ST"))) {
                        Element owner = owners.peek();
                        if (contentOf.equals("decl")) {
                            owner.declStart = contentStart;
                            owner.declEnd = Math.max(contentStart, at);
                        } else {
                            owner.implStart = contentStart;
                            owner.implEnd = Math.max(contentStart, at);
                        }
                        contentOf = null;
                    }
                    if (!owners.isEmpty() && (OBJECTS.contains(tag) || MEMBERS.contains(tag))) {
                        owners.pop();
                    }
                }
            }
            return new Result(objects, null, -1);
        } catch (XMLStreamException e) {
            int offset =
                    e.getLocation() == null ? 0 : Math.max(0, e.getLocation().getCharacterOffset());
            String msg = e.getMessage() == null
                    ? "malformed XML"
                    : e.getMessage().lines().findFirst().orElse("");
            return new Result(objects, msg, Math.min(offset, xml.length()));
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

    private static boolean isOwnerTag(String tag) {
        return tag != null && (OBJECTS.contains(tag) || MEMBERS.contains(tag));
    }

    static int endOfStartTag(String xml, int lt) {
        return XmlInput.endOfStartTag(xml, lt);
    }
}
