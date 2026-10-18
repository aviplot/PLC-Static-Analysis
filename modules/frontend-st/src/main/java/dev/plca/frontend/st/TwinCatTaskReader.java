package dev.plca.frontend.st;

import dev.plca.ast.Declaration;
import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.ast.QualifiedName;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.codehaus.stax2.XMLStreamReader2;

/**
 * Reads a TwinCAT task object ({@code .TcTTO}) into the same {@link Declaration.Configuration} shape as an IEC
 * {@code CONFIGURATION}, so the semantic model has one task representation.
 *
 * <p>Format (verified against public projects): {@code <TcPlcObject><Task Name="PlcTask"><CycleTime>10000</CycleTime>
 * <Priority>20</Priority><PouCall><Name>MAIN</Name></PouCall>…</Task>}; the cycle time is in microseconds (the file
 * says so in a comment).
 */
final class TwinCatTaskReader {

    private TwinCatTaskReader() {}

    static List<Declaration> read(SourceText whole, DiagnosticSink sink) {
        String xml = whole.text();
        List<Declaration> out = new ArrayList<>();
        XMLStreamReader2 r = null;
        String taskName = null;
        int taskAt = 0;
        int textAt = 0;
        StringBuilder text = new StringBuilder();
        String cycle = null;
        String priority = null;
        List<Declaration.ConfigElement> elements = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        List<Integer> callAt = new ArrayList<>();
        boolean inPouCall = false;
        try {
            r = (XMLStreamReader2) TwinCatReader.FACTORY.createXMLStreamReader(new StringReader(xml));
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String tag = r.getLocalName();
                    int at = (int) r.getLocationInfo().getStartingCharOffset();
                    text.setLength(0);
                    textAt = TwinCatReader.endOfStartTag(xml, at);
                    if (tag.equals("Task")) {
                        taskName = r.getAttributeValue(null, "Name");
                        taskAt = at;
                    } else if (tag.equals("PouCall")) {
                        inPouCall = true;
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String tag = r.getLocalName();
                    String value = text.toString().strip();
                    switch (tag) {
                        case "CycleTime" -> cycle = value;
                        case "Priority" -> priority = value;
                        case "Name" -> {
                            if (inPouCall && !value.isEmpty()) {
                                calls.add(value);
                                callAt.add(textAt);
                            }
                        }
                        case "PouCall" -> inPouCall = false;
                        case "Task" -> {
                            if (taskName != null) {
                                addTask(whole, taskName, taskAt, cycle, priority, calls, callAt, elements);
                            }
                            taskName = null;
                            cycle = null;
                            priority = null;
                            calls.clear();
                            callAt.clear();
                        }
                        default -> {}
                    }
                    text.setLength(0);
                }
            }
        } catch (XMLStreamException e) {
            int offset =
                    e.getLocation() == null ? 0 : Math.max(0, e.getLocation().getCharacterOffset());
            sink.report(Diagnostic.error(
                    SyntaxCodes.MALFORMED_XML,
                    "malformed XML: "
                            + (e.getMessage() == null
                                    ? ""
                                    : e.getMessage().lines().findFirst().orElse("")),
                    Span.at(whole.id(), Math.min(offset, xml.length()))));
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (XMLStreamException ignored) {
                    // nothing to release for a StringReader
                }
            }
        }
        if (!elements.isEmpty()) {
            Span all = whole.span(0, xml.length());
            out.add(new Declaration.Configuration(
                    new Identifier("<TwinCAT tasks>", Span.at(whole.id(), 0)), List.of(), elements, all));
        }
        return out;
    }

    private static void addTask(
            SourceText whole,
            String name,
            int at,
            String cycleMicros,
            String priority,
            List<String> calls,
            List<Integer> callAt,
            List<Declaration.ConfigElement> out) {
        String xml = whole.text();
        Span tag = whole.span(at, TwinCatReader.endOfStartTag(xml, at));
        Identifier taskId = new Identifier(name, tag);
        List<Expression.Argument> settings = new ArrayList<>();
        if (cycleMicros != null && cycleMicros.matches("\\d+")) {
            settings.add(setting(
                    "INTERVAL",
                    new Expression.Literal(Expression.LiteralKind.TIME, cycleMicros + "us", "T", tag),
                    tag));
        }
        if (priority != null && priority.matches("\\d+")) {
            settings.add(setting(
                    "PRIORITY", new Expression.Literal(Expression.LiteralKind.INTEGER, priority, null, tag), tag));
        }
        out.add(new Declaration.Task(taskId, settings, tag));
        for (int i = 0; i < calls.size(); i++) {
            int start = callAt.get(i);
            Span s = whole.span(
                    start, Math.min(xml.length(), start + calls.get(i).length()));
            Identifier program = new Identifier(calls.get(i), s);
            out.add(new Declaration.ProgramInstance(
                    program, taskId, new QualifiedName(List.of(program), s), List.of(), s));
        }
    }

    private static Expression.Argument setting(String name, Expression value, Span span) {
        return new Expression.Argument(Expression.ArgumentKind.INPUT, new Identifier(name, span), false, value, span);
    }
}
