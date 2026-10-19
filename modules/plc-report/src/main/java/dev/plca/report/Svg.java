package dev.plca.report;

import dev.plca.core.source.Location;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.Span;

/**
 * A small SVG writer shared by {@link LadderSvg} and {@link NetworkSvg}. Elements that stand for source text carry
 * {@code data-range="startLine,startChar,endLine,endChar"} (0-based, UTF-16, as LSP ranges), so a viewer can match
 * diagnostics and send hover requests without knowing about spans.
 */
final class Svg {

    /** Width of one character of the 12 px monospace font, for layout. */
    static final int CHAR_W = 7;

    /** Classes only; a viewer themes them. The defaults make a standalone SVG readable. */
    static final String STYLE = "<style>"
            + ".plca-svg{font:12px ui-monospace,Menlo,Consolas,monospace;fill:none}"
            + ".plca-svg .w{stroke:var(--plca-wire,#555);stroke-width:1.5}"
            + ".plca-svg .rail{stroke:var(--plca-rail,#333);stroke-width:3}"
            + ".plca-svg .sym{stroke:var(--plca-sym,#222);stroke-width:1.5}"
            + ".plca-svg .box{stroke:var(--plca-sym,#222);stroke-width:1.2;fill:var(--plca-box,transparent)}"
            + ".plca-svg text{fill:var(--plca-text,#222);stroke:none}"
            + ".plca-svg text.mn{font-weight:bold}"
            + ".plca-svg text.lbl,.plca-svg text.num{fill:var(--plca-muted,#777)}"
            + ".plca-svg text.cmt{fill:var(--plca-comment,#2a7a2a);font-style:italic}"
            + ".plca-svg text.err{fill:var(--plca-error,#c22)}"
            + ".plca-svg .hit{fill:transparent;stroke:none}"
            + "</style>";

    private final StringBuilder sb = new StringBuilder(4096);
    private final SourceRegistry registry;

    Svg(SourceRegistry registry) {
        this.registry = registry;
    }

    static int textWidth(String s) {
        return s.length() * CHAR_W;
    }

    /** {@code s} cut to {@code max} characters with an ellipsis. */
    static String elide(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }

    String range(Span span) {
        if (span == null || registry == null) {
            return null;
        }
        try {
            Location l = registry.resolve(span);
            return (l.startLine() - 1) + "," + (l.startColumn() - 1) + "," + (l.endLine() - 1) + ","
                    + (l.endColumn() - 1);
        } catch (RuntimeException e) {
            return null; // a span of a text the registry no longer has: draw without a range
        }
    }

    Svg raw(String s) {
        sb.append(s);
        return this;
    }

    Svg open(String cls, Span span) {
        sb.append("<g class=\"").append(cls).append('"');
        String r = range(span);
        if (r != null) {
            sb.append(" data-range=\"").append(r).append('"');
        }
        sb.append('>');
        return this;
    }

    Svg close() {
        sb.append("</g>");
        return this;
    }

    Svg line(String cls, double x1, double y1, double x2, double y2) {
        sb.append("<line class=\"")
                .append(cls)
                .append("\" x1=\"")
                .append(n(x1))
                .append("\" y1=\"")
                .append(n(y1))
                .append("\" x2=\"")
                .append(n(x2))
                .append("\" y2=\"")
                .append(n(y2))
                .append("\"/>");
        return this;
    }

    Svg rect(String cls, double x, double y, double w, double h, double r) {
        sb.append("<rect class=\"")
                .append(cls)
                .append("\" x=\"")
                .append(n(x))
                .append("\" y=\"")
                .append(n(y))
                .append("\" width=\"")
                .append(n(Math.max(0, w)))
                .append("\" height=\"")
                .append(n(Math.max(0, h)))
                .append('"');
        if (r > 0) {
            sb.append(" rx=\"").append(n(r)).append('"');
        }
        sb.append("/>");
        return this;
    }

    Svg circle(String cls, double cx, double cy, double r) {
        sb.append("<circle class=\"")
                .append(cls)
                .append("\" cx=\"")
                .append(n(cx))
                .append("\" cy=\"")
                .append(n(cy))
                .append("\" r=\"")
                .append(n(r))
                .append("\"/>");
        return this;
    }

    Svg path(String cls, String d) {
        sb.append("<path class=\"").append(cls).append("\" d=\"").append(d).append("\"/>");
        return this;
    }

    Svg polyline(String cls, double[] xy) {
        sb.append("<polyline class=\"").append(cls).append("\" points=\"");
        for (int i = 0; i + 1 < xy.length; i += 2) {
            sb.append(i > 0 ? " " : "").append(n(xy[i])).append(',').append(n(xy[i + 1]));
        }
        sb.append("\"/>");
        return this;
    }

    /** Text; when {@code full} differs from the shown text (elided), it becomes a {@code <title>}. */
    Svg text(String cls, double x, double y, String anchor, String shown, String full, Span span) {
        sb.append("<text class=\"")
                .append(cls)
                .append("\" x=\"")
                .append(n(x))
                .append("\" y=\"")
                .append(n(y))
                .append('"');
        if (anchor != null) {
            sb.append(" text-anchor=\"").append(anchor).append('"');
        }
        String r = range(span);
        if (r != null) {
            sb.append(" data-range=\"").append(r).append('"');
        }
        sb.append('>');
        if (full != null && !full.equals(shown)) {
            sb.append("<title>").append(Html.escape(full)).append("</title>");
        }
        sb.append(Html.escape(shown)).append("</text>");
        return this;
    }

    String document(int width, int height, String cls) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"plca-svg " + cls + "\" width=\"" + width
                + "\" height=\"" + height + "\" viewBox=\"0 0 " + width + " " + height + "\">" + STYLE + sb + "</svg>";
    }

    String document(double minX, double minY, double width, double height, String cls) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"plca-svg " + cls + "\" width=\"" + n(width)
                + "\" height=\"" + n(height) + "\" viewBox=\"" + n(minX) + " " + n(minY) + " " + n(width) + " "
                + n(height) + "\">" + STYLE + sb + "</svg>";
    }

    static String n(double v) {
        long r = Math.round(v);
        return Math.abs(v - r) < 1e-6 ? Long.toString(r) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
