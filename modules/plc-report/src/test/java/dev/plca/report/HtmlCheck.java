package dev.plca.report;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small HTML well-formedness check for the report: every non-void element is closed in order, attribute values are
 * quoted, and text holds no raw {@code <} or unescaped {@code &}. Script and style bodies are skipped.
 */
final class HtmlCheck {

    private static final Set<String> VOID =
            Set.of("meta", "br", "hr", "img", "input", "link", "source", "col", "area", "base", "wbr");
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[^<>]*?)?)\\s*(/?)>");
    private static final Pattern ATTR = Pattern.compile("\\s+([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\\s*=\\s*(\"[^\"]*\"))?");
    private static final Pattern ENTITY = Pattern.compile("&(?:[a-zA-Z]+|#\\d+|#x[0-9a-fA-F]+);");

    private HtmlCheck() {}

    static List<String> problems(String html) {
        List<String> out = new ArrayList<>();
        String body = html.replaceFirst("(?is)^<!DOCTYPE html>", "");
        body = body.replaceAll("(?s)(<script>).*?(</script>)", "$1$2").replaceAll("(?s)(<style>).*?(</style>)", "$1$2");
        Deque<String> open = new ArrayDeque<>();
        Matcher m = TAG.matcher(body);
        int at = 0;
        while (m.find()) {
            text(body.substring(at, m.start()), out);
            at = m.end();
            String name = m.group(2).toLowerCase(Locale.ROOT);
            String attrs = m.group(3);
            Matcher a = ATTR.matcher(attrs);
            int consumed = 0;
            while (a.find()) {
                if (a.start() != consumed) {
                    out.add("bad attributes in <" + name + attrs + ">");
                    break;
                }
                consumed = a.end();
            }
            if (consumed != attrs.length()) {
                out.add("bad attributes in <" + name + attrs + ">");
            }
            if (m.group(1).isEmpty()) {
                if (!VOID.contains(name) && m.group(4).isEmpty()) {
                    open.push(name);
                }
            } else if (open.isEmpty() || !open.pop().equals(name)) {
                out.add("unexpected </" + name + "> at " + m.start());
                return out;
            }
        }
        text(body.substring(at), out);
        if (!open.isEmpty()) {
            out.add("unclosed elements: " + open);
        }
        return out;
    }

    private static void text(String t, List<String> out) {
        if (t.contains("<") || t.contains(">")) {
            out.add("raw < or > in text: " + t.strip());
        }
        Matcher e = Pattern.compile("&").matcher(t);
        while (e.find()) {
            if (!ENTITY.matcher(t).region(e.start(), t.length()).lookingAt()) {
                out.add("unescaped & in text: " + t.strip());
                return;
            }
        }
    }
}
