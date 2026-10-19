package dev.plca.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;

/**
 * The client's {@code initializationOptions}, all optional:
 *
 * <pre>
 * { "config": "path/to/plca.yaml",   // relative to the workspace root; default: plca.yaml at the root
 *   "threads": 4,                    // rule threads of the project tier
 *   "fastDelayMs": 100, "fullDelayMs": 750,
 *   "maxFileSizeMb": 20 }            // larger files are analysed from disk only, never from editor buffers
 * </pre>
 */
final class Options {

    private Options() {}

    static Session.Settings read(Object options, Session.Settings defaults) {
        if (!(options instanceof JsonObject o)) {
            return defaults;
        }
        String config = string(o, "config");
        return new Session.Settings(
                config == null || config.isBlank() ? defaults.config() : Path.of(config),
                (int) number(o, "threads", defaults.threads(), 1, 256),
                number(o, "fastDelayMs", defaults.fastDelayMs(), 0, 10_000),
                number(o, "fullDelayMs", defaults.fullDelayMs(), 0, 60_000),
                number(o, "maxFileSizeMb", defaults.maxFileBytes() >> 20, 1, 4096) << 20);
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static long number(JsonObject o, String key, long fallback, long min, long max) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return Math.max(min, Math.min(max, e.getAsLong()));
    }
}
