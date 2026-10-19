package dev.plca.rules;

import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Statement;
import dev.plca.core.Dialect;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.Span;
import dev.plca.semantic.PouSymbol;

/**
 * Where a finding is in the vendor IDE, for dialects whose editor is not a text file. Rockwell Studio 5000 shows
 * <em>program → routine → rung</em> (or the line of an ST routine), so a finding at an L5X CDATA line also gets
 * {@code Program:Main/Routine:Fill/Rung:14} ({@code AOI:Valve/…} inside Add-On Instructions).
 */
final class Places {

    private Places() {}

    static String of(PouSymbol pou, Span span, SourceRegistry sources) {
        if (pou.dialect() != Dialect.ROCKWELL) {
            return null;
        }
        PouSymbol routine = pou.kind() == PouKind.ROUTINE ? pou : null;
        PouSymbol owner = routine != null ? routine.owner() : pou;
        StringBuilder sb = new StringBuilder();
        if (owner != null) {
            sb.append(owner.kind() == PouKind.PROGRAM ? "Program:" : "AOI:").append(owner.name());
        }
        // an AOI's Logic routine is the AOI's body
        if (routine == null && pou.kind() == PouKind.FUNCTION_BLOCK && inBody(pou, span)) {
            sb.append("/Routine:Logic");
            routine = pou;
        } else if (routine != null) {
            sb.append(sb.isEmpty() ? "" : "/").append("Routine:").append(routine.name());
        }
        if (routine != null
                && routine.declaration() != null
                && routine.declaration().body() != null) {
            String language = routine.declaration().bodyLanguage();
            if ("RLL".equalsIgnoreCase(language)) {
                for (Statement s : routine.declaration().body()) {
                    if (s instanceof Statement.Rung r && contains(r.span(), span)) {
                        return sb.append("/Rung:").append(r.number()).toString();
                    }
                }
            } else if ("ST".equalsIgnoreCase(language) && inBody(routine, span)) {
                String text = sources.text(span.textId()).text();
                int line = 1;
                for (int i = 0; i < span.start() && i < text.length(); i++) {
                    if (text.charAt(i) == '\n') {
                        line++;
                    }
                }
                return sb.append("/Line:").append(line).toString();
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static boolean inBody(PouSymbol pou, Span span) {
        var body = pou.declaration() == null ? null : pou.declaration().body();
        return body != null && !body.isEmpty() && body.getFirst().span().textId() == span.textId();
    }

    private static boolean contains(Span outer, Span inner) {
        return outer.textId() == inner.textId() && outer.start() <= inner.start() && inner.end() <= outer.end();
    }
}
