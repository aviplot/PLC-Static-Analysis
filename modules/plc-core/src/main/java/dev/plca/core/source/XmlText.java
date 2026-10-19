package dev.plca.core.source;

/**
 * Decodes the character content of an XML element directly from the raw file text, producing the fragment text a parser
 * sees together with an exact {@link SourceMap} back to the file.
 *
 * <p>Why not take the text from StAX: the parser normalises line endings and resolves entities, and its reported
 * offsets are implementation-specific, so the original positions are lost. The XML frontends use StAX only to find the
 * element's content boundaries, then call {@link #decode} on that region.
 *
 * <p>Handles CDATA sections (verbatim, possibly several and mixed with character data), the predefined entities,
 * decimal and hex character references, {@code \r\n}/{@code \r} normalisation (XML 1.0 §2.11), and skips comments and
 * processing instructions. Unknown entities and stray markup are kept or skipped without throwing: real exports are
 * messy and the analyzer must not fail on them.
 */
public final class XmlText {

    /** The decoded text and its map; {@code map.fragmentLength() == text.length()}. */
    public record Fragment(String text, SourceMap map) {}

    private XmlText() {}

    /** Decodes {@code fileText[start, end)}, the content between an element's start and end tags. */
    public static Fragment decode(String fileText, int start, int end) {
        StringBuilder out = new StringBuilder(end - start);
        SourceMap.Builder map = SourceMap.builder();
        decodeInto(fileText, start, end, out, map);
        return new Fragment(out.toString(), map.build());
    }

    /**
     * Appends the decoded content of {@code fileText[start, end)} to {@code out} and its segments to {@code map}, so
     * several elements can form one fragment (an L5X routine's rungs or ST lines). Regions must be appended in file
     * order.
     */
    public static void decodeInto(String fileText, int start, int end, StringBuilder out, SourceMap.Builder map) {
        int i = start;
        while (i < end) {
            char c = fileText.charAt(i);
            if (c == '<') {
                if (fileText.startsWith("<![CDATA[", i)) {
                    int body = i + 9;
                    int close = indexOf(fileText, "]]>", body, end);
                    copyNormalisingNewlines(fileText, body, close < 0 ? end : close, out, map);
                    i = close < 0 ? end : close + 3;
                } else if (fileText.startsWith("<!--", i)) {
                    int close = indexOf(fileText, "-->", i + 4, end);
                    i = close < 0 ? end : close + 3;
                } else {
                    // processing instruction or unexpected nested element: no character content
                    int close = fileText.indexOf('>', i + 1);
                    i = close < 0 || close >= end ? end : close + 1;
                }
            } else if (c == '&') {
                i = reference(fileText, i, end, out, map);
            } else {
                int runEnd = i;
                while (runEnd < end && fileText.charAt(runEnd) != '<' && fileText.charAt(runEnd) != '&') {
                    runEnd++;
                }
                i = copyNormalisingNewlines(fileText, i, runEnd, out, map);
            }
        }
    }

    /** Copies {@code [from, to)}, turning {@code \r\n} and lone {@code \r} into {@code \n}; returns {@code to}. */
    private static int copyNormalisingNewlines(String s, int from, int to, StringBuilder out, SourceMap.Builder map) {
        int runStart = from;
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == '\r') {
                out.append(s, runStart, i);
                map.verbatim(runStart, i - runStart);
                int len = i + 1 < to && s.charAt(i + 1) == '\n' ? 2 : 1;
                out.append('\n');
                map.replacement(i, len, 1);
                i += len - 1;
                runStart = i + 1;
            }
        }
        out.append(s, runStart, to);
        map.verbatim(runStart, to - runStart);
        return to;
    }

    private static int reference(String s, int amp, int end, StringBuilder out, SourceMap.Builder map) {
        int semi = s.indexOf(';', amp + 1);
        if (semi < 0 || semi >= end || semi - amp > 12) {
            out.append('&');
            map.verbatim(amp, 1);
            return amp + 1;
        }
        String name = s.substring(amp + 1, semi);
        String value = resolve(name);
        if (value == null) {
            // unknown (DTD-defined) entity: keep it literally
            out.append(s, amp, semi + 1);
            map.verbatim(amp, semi + 1 - amp);
        } else {
            out.append(value);
            map.replacement(amp, semi + 1 - amp, value.length());
        }
        return semi + 1;
    }

    private static String resolve(String name) {
        String predefined = switch (name) {
            case "lt" -> "<";
            case "gt" -> ">";
            case "amp" -> "&";
            case "apos" -> "'";
            case "quot" -> "\"";
            default -> null;
        };
        if (predefined != null) {
            return predefined;
        }
        if (name.length() > 1 && name.charAt(0) == '#') {
            try {
                int cp = name.charAt(1) == 'x' || name.charAt(1) == 'X'
                        ? Integer.parseInt(name.substring(2), 16)
                        : Integer.parseInt(name.substring(1));
                return Character.isValidCodePoint(cp) ? Character.toString(cp) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static int indexOf(String s, String needle, int from, int end) {
        int i = s.indexOf(needle, from);
        return i < 0 || i + needle.length() > end ? -1 : i;
    }
}
