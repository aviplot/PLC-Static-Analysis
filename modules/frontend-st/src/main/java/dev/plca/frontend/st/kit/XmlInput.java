package dev.plca.frontend.st.kit;

import javax.xml.stream.XMLInputFactory;

/**
 * The StAX setup every XML frontend uses: Woodstox, with DTDs and external entities turned off (the corpus contains XXE
 * probes disguised as PLC exports) and a nesting limit. Readers parse the already decoded file text, so Woodstox's
 * character offsets are the {@code String} indices spans use.
 */
public final class XmlInput {

    private static final XMLInputFactory FACTORY = createFactory();

    private XmlInput() {}

    /** The shared factory (thread-safe once configured). */
    public static XMLInputFactory factory() {
        return FACTORY;
    }

    /**
     * Woodstox, instantiated reflectively: referencing its classes directly makes javac warn about OSGi annotations
     * that are not on the classpath (and {@code XMLInputFactory.newFactory} takes a property name, not a class name).
     */
    private static XMLInputFactory createFactory() {
        XMLInputFactory f;
        try {
            f = (XMLInputFactory) Class.forName("com.ctc.wstx.stax.WstxInputFactory")
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Woodstox StAX implementation missing from the classpath", e);
        }
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, false);
        f.setProperty("com.ctc.wstx.maxElementDepth", 500);
        return f;
    }

    /** Offset just after the {@code >} of the start tag beginning at {@code lt}, skipping quoted attribute values. */
    public static int endOfStartTag(String xml, int lt) {
        char quote = 0;
        for (int i = lt; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i + 1;
            }
        }
        return xml.length();
    }
}
