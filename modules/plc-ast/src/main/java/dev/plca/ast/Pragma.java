package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code {…}} pragma. {@code name}/{@code value} are filled for attributes ({@code {attribute 'hide'}},
 * {@code {attribute 'TcRpcEnable' := '1'}}) and messages ({@code {warning 'text'}}); otherwise {@code name} is the
 * leading word and {@code value} the rest.
 */
public record Pragma(Kind kind, String name, String value, String text, Span span) implements Node {

    public enum Kind {
        ATTRIBUTE,
        /** {@code warning}, {@code info}, {@code error}, {@code text} */
        MESSAGE,
        /** {@code IF}, {@code ELSIF}, {@code ELSE}, {@code END_IF}, {@code define}, {@code undefine} */
        CONDITIONAL,
        /** {@code region}, {@code endregion} */
        REGION,
        OTHER
    }

    private static final Pattern ATTRIBUTE =
            Pattern.compile("(?is)^\\{\\s*attribute\\s+'([^']*)'\\s*(?::=\\s*'(.*)'\\s*)?\\}$");
    private static final Pattern WORD = Pattern.compile("(?s)^\\{\\s*([A-Za-z_]\\w*)\\s*(.*?)\\s*\\}?$");
    private static final Pattern SIEMENS_PAIR = Pattern.compile("\\s*([A-Za-z_][\\w.]*)\\s*:=\\s*'([^']*)'\\s*(;|$)");

    /** Classifies the raw pragma text (including braces). */
    public static Pragma of(String text, Span span) {
        Matcher a = ATTRIBUTE.matcher(text);
        if (a.matches()) {
            return new Pragma(Kind.ATTRIBUTE, a.group(1), a.group(2), text, span);
        }
        Matcher w = WORD.matcher(text);
        if (!w.matches()) {
            return new Pragma(Kind.OTHER, "", text, text, span);
        }
        String word = w.group(1);
        String rest = w.group(2);
        Kind kind = switch (word.toLowerCase(Locale.ROOT)) {
            case "warning", "info", "error", "text" -> Kind.MESSAGE;
            case "if", "elsif", "else", "end_if", "define", "undefine" -> Kind.CONDITIONAL;
            case "region", "endregion" -> Kind.REGION;
            default -> Kind.OTHER;
        };
        if (kind == Kind.MESSAGE && rest.length() >= 2 && rest.startsWith("'") && rest.endsWith("'")) {
            rest = rest.substring(1, rest.length() - 1);
        }
        return new Pragma(kind, word, rest, text, span);
    }

    /**
     * Siemens attributes {@code { S7_Optimized_Access := 'TRUE' }} or {@code {InstructionName := 'TON_TIME'; LibVersion
     * := '1.0'}}: one {@link Kind#ATTRIBUTE} per pair, all with the text and span of the whole block. Text of another
     * shape falls back to {@link #of}.
     */
    public static List<Pragma> siemensAttributes(String text, Span span) {
        String inner = text.length() >= 2 && text.startsWith("{") && text.endsWith("}")
                ? text.substring(1, text.length() - 1).strip()
                : null;
        if (inner == null || inner.isEmpty()) {
            return List.of(of(text, span));
        }
        List<Pragma> out = new ArrayList<>();
        Matcher m = SIEMENS_PAIR.matcher(inner);
        int at = 0;
        while (at < inner.length()) {
            if (!m.find(at) || m.start() != at) {
                return List.of(of(text, span));
            }
            out.add(new Pragma(Kind.ATTRIBUTE, m.group(1), m.group(2), text, span));
            at = m.end();
        }
        return out;
    }

    public boolean isAttribute(String attributeName) {
        return kind == Kind.ATTRIBUTE && name.equalsIgnoreCase(attributeName);
    }
}
