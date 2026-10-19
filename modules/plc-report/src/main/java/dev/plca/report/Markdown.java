package dev.plca.report;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small Markdown subset rule descriptors use, rendered to HTML for the HTML report: {@code ##} headings,
 * paragraphs, fenced code blocks, {@code -} lists, {@code `code`}, {@code *emphasis*} and {@code **strong**}.
 * Everything is HTML-escaped first; no raw HTML passes through.
 */
final class Markdown {

    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern STRONG = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern EM = Pattern.compile("(?<![\\w*])\\*([^*\\s][^*]*)\\*(?![\\w*])");

    private Markdown() {}

    static String toHtml(String markdown) {
        StringBuilder out = new StringBuilder();
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        StringBuilder para = new StringBuilder();
        boolean list = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("```")) {
                flush(out, para);
                list = closeList(out, list);
                StringBuilder code = new StringBuilder();
                for (i++; i < lines.length && !lines[i].startsWith("```"); i++) {
                    code.append(lines[i]).append('\n');
                }
                out.append("<pre><code>").append(Html.escape(code.toString())).append("</code></pre>\n");
                continue;
            }
            if (line.startsWith("## ") || line.startsWith("# ")) {
                flush(out, para);
                list = closeList(out, list);
                out.append("<h4>")
                        .append(inline(line.substring(line.indexOf(' ') + 1)))
                        .append("</h4>\n");
                continue;
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                flush(out, para);
                if (!list) {
                    out.append("<ul>\n");
                    list = true;
                }
                out.append("<li>").append(inline(line.substring(2))).append("</li>\n");
                continue;
            }
            if (line.isBlank()) {
                flush(out, para);
                list = closeList(out, list);
                continue;
            }
            if (list && line.startsWith("  ")) {
                // continuation of a list item: append to the previous <li>
                int end = out.lastIndexOf("</li>");
                out.insert(end, " " + inline(line.strip()));
                continue;
            }
            list = closeList(out, list);
            para.append(para.isEmpty() ? "" : " ").append(line.strip());
        }
        flush(out, para);
        closeList(out, list);
        return out.toString();
    }

    private static void flush(StringBuilder out, StringBuilder para) {
        if (!para.isEmpty()) {
            out.append("<p>").append(inline(para.toString())).append("</p>\n");
            para.setLength(0);
        }
    }

    private static boolean closeList(StringBuilder out, boolean list) {
        if (list) {
            out.append("</ul>\n");
        }
        return false;
    }

    /** Inline markup; code spans are protected from emphasis. */
    static String inline(String text) {
        String escaped = Html.escape(text);
        StringBuilder sb = new StringBuilder();
        Matcher m = CODE.matcher(escaped);
        int at = 0;
        while (m.find()) {
            sb.append(emphasis(escaped.substring(at, m.start())));
            sb.append("<code>").append(m.group(1)).append("</code>");
            at = m.end();
        }
        sb.append(emphasis(escaped.substring(at)));
        return sb.toString();
    }

    private static String emphasis(String s) {
        String r = STRONG.matcher(s).replaceAll("<strong>$1</strong>");
        return EM.matcher(r).replaceAll("<em>$1</em>");
    }
}
