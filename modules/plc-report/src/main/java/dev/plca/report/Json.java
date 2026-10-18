package dev.plca.report;

import dev.plca.core.diag.Severity;
import dev.plca.core.source.Location;
import java.io.Writer;
import java.util.Locale;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared Jackson setup for the JSON-based reports. */
final class Json {

    /** Does not close the target: reports are often written to stdout. */
    static final JsonMapper MAPPER =
            JsonMapper.builder().disable(StreamWriteFeature.AUTO_CLOSE_TARGET).build();

    private Json() {}

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static void write(ObjectNode root, Writer out) {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, root);
    }

    static String severity(Severity s) {
        return s.name().toLowerCase(Locale.ROOT);
    }

    static ObjectNode location(Location l) {
        ObjectNode n = object();
        n.put("path", l.path());
        n.put("startLine", l.startLine());
        n.put("startColumn", l.startColumn());
        n.put("endLine", l.endLine());
        n.put("endColumn", l.endColumn());
        return n;
    }
}
