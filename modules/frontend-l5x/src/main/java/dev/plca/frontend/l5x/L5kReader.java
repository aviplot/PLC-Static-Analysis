package dev.plca.frontend.l5x;

import dev.plca.frontend.l5x.L5xReader.AoiDef;
import dev.plca.frontend.l5x.L5xReader.Attr;
import dev.plca.frontend.l5x.L5xReader.DataTypeDef;
import dev.plca.frontend.l5x.L5xReader.FbdDef;
import dev.plca.frontend.l5x.L5xReader.FbdNode;
import dev.plca.frontend.l5x.L5xReader.LineDef;
import dev.plca.frontend.l5x.L5xReader.MemberDef;
import dev.plca.frontend.l5x.L5xReader.ProgramDef;
import dev.plca.frontend.l5x.L5xReader.Project;
import dev.plca.frontend.l5x.L5xReader.Range;
import dev.plca.frontend.l5x.L5xReader.RoutineDef;
import dev.plca.frontend.l5x.L5xReader.RungDef;
import dev.plca.frontend.l5x.L5xReader.TagDef;
import dev.plca.frontend.l5x.L5xReader.TaskDef;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads an L5K export (Logix 5000's text export, {@code docs/grammar/rockwell.md} §11) into the same {@link Project}
 * the L5X reader produces, so everything after reading is shared. A hand-written scanner rather than a grammar: L5K is
 * a container of blocks ({@code KEYWORD name (attr := value, …) … END_KEYWORD}) whose bodies are rung text and ST,
 * which the existing grammars parse. Every name, value and body keeps its range in the file. Never throws: what cannot
 * be read is skipped, and a structural problem ends reading with {@code Project.error}.
 */
final class L5kReader {

    private final String t;
    private final int n;
    private final Project p = new Project();
    private int pos;

    private L5kReader(String text) {
        this.t = text;
        this.n = text.length();
    }

    static Project read(String text) {
        L5kReader r = new L5kReader(text);
        try {
            r.run();
        } catch (RuntimeException e) {
            r.p.error = "unreadable L5K structure: " + e.getMessage();
            r.p.errorOffset = Math.min(r.pos, r.n);
        }
        return r.p;
    }

    /** The end of the line containing {@code s} (before its {@code \r} or {@code \n}). */
    static int lineEnd(String text, int s) {
        int i = s;
        while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
            i++;
        }
        return i;
    }

    private void run() {
        if (pos < n && t.charAt(pos) == '﻿') {
            pos++;
        }
        while (true) {
            skipBlank();
            if (pos >= n) {
                return;
            }
            int at = pos;
            String kw = word();
            switch (kw) {
                case "" -> skipLine();
                case "CONTROLLER" -> {
                    p.targetType = new Attr("Controller", new Range(at, at + kw.length()));
                    p.controller = name();
                    attributes();
                }
                case "END_CONTROLLER", "IE_VER" -> skipStatementOrLine();
                case "DATATYPE" -> dataType(at);
                case "ADD_ON_INSTRUCTION_DEFINITION" -> aoi(at);
                case "MODULE" -> {
                    Attr name = name();
                    if (name.present()) {
                        p.modules.add(name.value());
                    }
                    skipPast("END_MODULE");
                }
                case "TAG" -> tags(p.tags, "END_TAG");
                case "PROGRAM" -> program(at);
                case "TASK" -> task(at);
                case "ROUTINE", "ST_ROUTINE", "FBD_ROUTINE", "SFC_ROUTINE" -> p.looseRoutines.add(routine(kw, at));
                default -> skipBlock(kw);
            }
        }
    }

    // ---- blocks ---------------------------------------------------------------------------------

    private void dataType(int at) {
        Attr name = name();
        Map<String, Attr> attrs = attributes();
        DataTypeDef d = new DataTypeDef(name, attrs.getOrDefault("FamilyType", Attr.NONE), at);
        p.dataTypes.add(d);
        while (true) {
            skipBlank();
            if (pos >= n || atWord("END_DATATYPE")) {
                word();
                return;
            }
            int s = pos;
            int e = statementEnd();
            MemberDef m = member(s, e);
            if (m != null) {
                d.members.add(m);
            }
            pos = Math.min(n, e + 1);
        }
    }

    /** {@code TYPE Name[dim] (attrs)} or {@code BIT Name Target : bit (attrs)}. */
    private MemberDef member(int s, int e) {
        pos = s;
        String type = word();
        Attr typeAttr = new Attr(type, new Range(s, s + type.length()));
        Attr name = name();
        if (type.isEmpty() || !name.present()) {
            return null;
        }
        Attr dims = Attr.NONE;
        Attr target = Attr.NONE;
        skipSpaces(e);
        if (pos < e && t.charAt(pos) == '[') {
            dims = dimensions(e);
        }
        if (type.equalsIgnoreCase("BIT")) {
            target = name();
        }
        Map<String, Attr> attrs = attributesBefore(e);
        boolean hidden = "1".equals(value(attrs, "Hidden"));
        return new MemberDef(name, typeAttr, dims, attrs.getOrDefault("ExternalAccess", Attr.NONE), hidden, target, s);
    }

    private void aoi(int at) {
        Attr name = name();
        Map<String, Attr> attrs = attributes();
        AoiDef a = new AoiDef(name, at, false, "Safety".equalsIgnoreCase(value(attrs, "Class")));
        p.aois.add(a);
        while (true) {
            skipBlank();
            if (pos >= n) {
                return;
            }
            int kwAt = pos;
            String kw = word();
            switch (kw) {
                case "END_ADD_ON_INSTRUCTION_DEFINITION" -> {
                    return;
                }
                case "PARAMETERS" -> tags(a.parameters, "END_PARAMETERS");
                case "LOCAL_TAGS" -> tags(a.localTags, "END_LOCAL_TAGS");
                case "ROUTINE", "ST_ROUTINE", "FBD_ROUTINE", "SFC_ROUTINE" -> a.routines.add(routine(kw, kwAt));
                case "" -> skipLine();
                default -> skipBlock(kw);
            }
        }
    }

    private void program(int at) {
        Attr name = name();
        Map<String, Attr> attrs = attributes();
        ProgramDef prog = new ProgramDef(
                name,
                attrs.getOrDefault("MAIN", Attr.NONE),
                attrs.getOrDefault("FAULT", Attr.NONE),
                "1".equals(value(attrs, "DisableFlag")),
                "Safety".equalsIgnoreCase(value(attrs, "Class")),
                at);
        p.programs.add(prog);
        while (true) {
            skipBlank();
            if (pos >= n) {
                return;
            }
            int kwAt = pos;
            String kw = word();
            switch (kw) {
                case "END_PROGRAM" -> {
                    return;
                }
                case "TAG" -> tags(prog.tags, "END_TAG");
                case "ROUTINE", "ST_ROUTINE", "FBD_ROUTINE", "SFC_ROUTINE" -> prog.routines.add(routine(kw, kwAt));
                case "" -> skipLine();
                default -> skipBlock(kw);
            }
        }
    }

    private void task(int at) {
        Attr name = name();
        Map<String, Attr> attrs = attributes();
        TaskDef task = new TaskDef(
                name,
                value(attrs, "Type") == null ? "" : value(attrs, "Type").toUpperCase(Locale.ROOT),
                attrs.getOrDefault("Rate", Attr.NONE),
                attrs.getOrDefault("Priority", Attr.NONE),
                "Yes".equalsIgnoreCase(value(attrs, "InhibitTask")),
                at);
        p.tasks.add(task);
        while (true) {
            skipBlank();
            if (pos >= n || atWord("END_TASK")) {
                word();
                return;
            }
            int s = pos;
            int e = statementEnd();
            Attr program = trimmed(s, e);
            if (program.present()) {
                task.programs.add(program);
            }
            pos = Math.min(n, e + 1);
        }
    }

    /** Tag declarations up to {@code end}: {@code Name : TYPE[dims] (attrs) := value;} or {@code Name OF alias …;}. */
    private void tags(List<TagDef> out, String end) {
        while (true) {
            skipBlank();
            if (pos >= n || atWord(end)) {
                word();
                return;
            }
            int s = pos;
            int e = statementEnd();
            TagDef tag = tag(s, e);
            if (tag != null) {
                out.add(tag);
            }
            pos = Math.min(n, e + 1);
        }
    }

    private TagDef tag(int s, int e) {
        pos = s;
        Attr name = name();
        if (!name.present()) {
            return null;
        }
        skipSpaces(e);
        Attr type = Attr.NONE;
        Attr dims = Attr.NONE;
        Attr alias = Attr.NONE;
        String tagType = "Base";
        if (atWord("OF")) {
            word();
            skipSpaces(e);
            int a = pos;
            while (pos < e && t.charAt(pos) != '(' && !Character.isWhitespace(t.charAt(pos))) {
                pos++;
            }
            alias = new Attr(t.substring(a, pos), new Range(a, pos));
            tagType = "Alias";
        } else if (pos < e && t.charAt(pos) == ':' && !t.startsWith(":=", pos)) {
            pos++;
            skipSpaces(e);
            int a = pos;
            String w = word();
            type = new Attr(w, new Range(a, a + w.length()));
            skipSpaces(e);
            if (pos < e && t.charAt(pos) == '[') {
                dims = dimensions(e);
            }
        }
        Map<String, Attr> attrs = attributesBefore(e);
        String usage = value(attrs, "Usage");
        TagDef tag = new TagDef(
                name,
                type,
                dims,
                attrs.getOrDefault("ExternalAccess", Attr.NONE),
                tagType,
                usage,
                alias,
                "Yes".equalsIgnoreCase(value(attrs, "Constant")),
                "Yes".equalsIgnoreCase(value(attrs, "Required")),
                "Safety".equalsIgnoreCase(value(attrs, "Class")),
                s);
        // the initial value: `:= v` after the attributes, or an AOI parameter's DefaultData
        skipSpaces(e);
        Attr init = Attr.NONE;
        if (t.startsWith(":=", pos)) {
            pos += 2;
            init = trimmed(pos, e);
        } else if (attrs.containsKey("DefaultData")) {
            init = attrs.get("DefaultData");
        }
        if (init.present() && !init.value().startsWith("[") && !init.value().isEmpty()) {
            tag.initialValue = init.value();
            tag.initialValueRange = init.range();
            tag.initialIsString = init.value().startsWith("'") || init.value().startsWith("\"");
        }
        return tag;
    }

    /** A routine block; {@code kw} gives its language. */
    private RoutineDef routine(String kw, int at) {
        Attr name = name();
        String type = switch (kw) {
            case "ST_ROUTINE" -> "ST";
            case "FBD_ROUTINE" -> "FBD";
            case "SFC_ROUTINE" -> "SFC";
            default -> "RLL";
        };
        skipSpaces(n);
        if (pos < n && t.charAt(pos) == '(') {
            attributes();
        }
        RoutineDef r = new RoutineDef(name, type, at);
        String end = "END_" + kw;
        switch (type) {
            case "RLL" -> rungs(r, end);
            case "ST" -> stLines(r, end);
            case "FBD" -> fbd(r, end);
            default -> skipPast(end); // SFC: TODO(verify-format) no L5K SFC routine in the corpus
        }
        return r;
    }

    /** {@code RC: "comment" "more";} then {@code N: XIC(a)OTE(b);} (the type letter before the colon). */
    private void rungs(RoutineDef r, String end) {
        Range comment = Range.NONE;
        int number = 0;
        while (true) {
            skipBlank();
            if (pos >= n || atWord(end)) {
                word();
                return;
            }
            int at = pos;
            String type = word();
            skipSpaces(n);
            if (type.isEmpty() || pos >= n || t.charAt(pos) != ':') {
                skipLine();
                continue;
            }
            pos++;
            skipSpaces(n);
            int s = pos;
            int e = statementEnd();
            if (type.equals("RC")) {
                int first = t.indexOf('"', s);
                int last = t.lastIndexOf('"', e);
                comment = first >= 0 && last > first ? new Range(first + 1, last) : Range.NONE;
            } else {
                RungDef rung = new RungDef(number++, type, at);
                rung.text = new Range(s, Math.min(n, e + 1));
                rung.comment = comment;
                r.rungs.add(rung);
                comment = Range.NONE;
            }
            pos = Math.min(n, e + 1);
        }
    }

    /** ST lines, each written {@code 'text} on its own line. */
    private void stLines(RoutineDef r, String end) {
        int number = 0;
        while (true) {
            skipSpaces(n);
            skipNewlines();
            skipSpaces(n);
            if (pos >= n || atWord(end)) {
                word();
                return;
            }
            if (t.charAt(pos) == '\'') {
                int s = pos + 1;
                int e = lineEnd(t, s);
                r.lines.add(new LineDef(number++, new Range(s, e)));
                pos = e;
            } else {
                skipLine();
            }
        }
    }

    /**
     * {@code SHEET (…) … END_SHEET} blocks of elements {@code KIND (attrs) END_KIND}: {@code IREF}, {@code OREF},
     * {@code ICON}, {@code OCON}, {@code WIRE}, {@code FEEDBACK_WIRE}, {@code X_BLOCK} (instruction or AOI {@code X}),
     * {@code X_FUNCTION}, {@code JSR}/{@code SBR}/{@code RET}, read into the L5X element names and attributes.
     */
    private void fbd(RoutineDef r, String end) {
        r.fbd = new FbdDef();
        int sheet = 0;
        while (true) {
            skipBlank();
            if (pos >= n || atWord(end)) {
                word();
                return;
            }
            int at = pos;
            String kw = word();
            if (kw.isEmpty()) {
                skipLine();
                continue;
            }
            if (kw.equals("ADD_ON_INSTRUCTION")) {
                r.fbd.nodes.add(aoiCall(Math.max(sheet, 1), at));
                continue;
            }
            Map<String, Attr> attrs = attributes();
            switch (kw) {
                case "SHEET" -> sheet++;
                case "END_SHEET" -> {}
                default -> {
                    FbdNode node = fbdNode(kw, Math.max(sheet, 1), at, attrs);
                    if (node != null) {
                        r.fbd.nodes.add(node);
                    }
                    if (!kw.startsWith("END_")) {
                        skipPast("END_" + kw);
                    }
                }
            }
        }
    }

    /**
     * {@code ADD_ON_INSTRUCTION Aoi (ID := …, Operand := "tag", …) FBD_PARAMETERS (Param := Arg, …) END_FBD_PARAMETERS
     * END_ADD_ON_INSTRUCTION}: an AOI on its backing tag, in-out arguments by name.
     */
    private FbdNode aoiCall(int sheet, int at) {
        Attr name = name();
        FbdNode node = new FbdNode("AddOnInstruction", sheet, at);
        node.attributes.put("Name", name);
        attributes().forEach(node.attributes::putIfAbsent);
        skipBlank();
        if (atWord("FBD_PARAMETERS")) {
            word();
            attributes().forEach((k, v) -> node.params.add(new Attr[] {new Attr(k, v.range()), v}));
        }
        skipPast("END_ADD_ON_INSTRUCTION");
        return node;
    }

    private FbdNode fbdNode(String kw, int sheet, int at, Map<String, Attr> attrs) {
        String tag;
        FbdNode node;
        switch (kw) {
            case "IREF", "OREF", "ICON", "OCON" -> {
                tag = switch (kw) {
                    case "IREF" -> "IRef";
                    case "OREF" -> "ORef";
                    case "ICON" -> "ICon";
                    default -> "OCon";
                };
                node = new FbdNode(tag, sheet, at);
            }
            case "WIRE", "FEEDBACK_WIRE" -> {
                node = new FbdNode(kw.equals("WIRE") ? "Wire" : "FeedbackWire", sheet, at);
                rename(attrs, node, "FromElementID", "FromID");
                rename(attrs, node, "FromParameter", "FromParam");
                rename(attrs, node, "ToElementID", "ToID");
                rename(attrs, node, "ToParameter", "ToParam");
                return node;
            }
            case "JSR", "SBR", "RET" -> node = new FbdNode(kw, sheet, at);
            default -> {
                if (kw.endsWith("_BLOCK")) {
                    String type = kw.substring(0, kw.length() - "_BLOCK".length());
                    boolean aoi = p.aois.stream().anyMatch(a -> type.equalsIgnoreCase(a.name.value()));
                    node = new FbdNode(aoi ? "AddOnInstruction" : "Block", sheet, at);
                    node.attributes.put(aoi ? "Name" : "Type", new Attr(type, new Range(at, at + type.length())));
                } else if (kw.endsWith("_FUNCTION")) {
                    String type = kw.substring(0, kw.length() - "_FUNCTION".length()) + "__F";
                    node = new FbdNode("Function", sheet, at);
                    node.attributes.put("Type", new Attr(type, new Range(at, at + kw.length())));
                } else {
                    return null; // TEXT_BOX, ATTACHMENT, …: not logic
                }
            }
        }
        attrs.forEach(node.attributes::putIfAbsent);
        return node;
    }

    private static void rename(Map<String, Attr> attrs, FbdNode node, String from, String to) {
        Attr a = attrs.get(from);
        if (a != null && a.present() && !a.value().isEmpty()) {
            node.attributes.put(to, a);
        }
    }

    // ---- attributes -----------------------------------------------------------------------------

    /** {@code (Name := value, …)} at the current position (skipping blanks first); empty when there is none. */
    private Map<String, Attr> attributes() {
        skipBlank();
        return attributesBefore(n);
    }

    /** The attribute list starting at the current position (after spaces), if it starts before {@code limit}. */
    private Map<String, Attr> attributesBefore(int limit) {
        Map<String, Attr> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        skipSpaces(limit);
        if (pos >= limit || t.charAt(pos) != '(') {
            return out;
        }
        pos++;
        while (pos < n) {
            skipBlank();
            if (pos >= n) {
                return out;
            }
            if (t.charAt(pos) == ')') {
                pos++;
                return out;
            }
            if (t.charAt(pos) == ',') {
                pos++;
                continue;
            }
            String key = word();
            skipBlank();
            if (key.isEmpty() || !t.startsWith(":=", pos)) {
                // not an attribute: skip to the next separator
                while (pos < n && t.charAt(pos) != ',' && t.charAt(pos) != ')') {
                    pos++;
                }
                continue;
            }
            pos += 2;
            skipBlank();
            out.put(key, attributeValue());
        }
        return out;
    }

    /** A quoted string (range inside the quotes) or bare text up to {@code ,} or {@code )} at depth 0. */
    private Attr attributeValue() {
        if (pos < n && t.charAt(pos) == '"') {
            int s = pos + 1;
            int e = closingQuote(s, '"');
            pos = Math.min(n, e + 1);
            return new Attr(t.substring(s, e), new Range(s, e));
        }
        int s = pos;
        int depth = 0;
        while (pos < n) {
            char c = t.charAt(pos);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                break;
            } else if (c == '"' || c == '\'') {
                pos = closingQuote(pos + 1, c);
            }
            pos++;
        }
        return trimmed(s, Math.min(pos, n));
    }

    private static String value(Map<String, Attr> attrs, String key) {
        Attr a = attrs.get(key);
        return a == null ? null : a.value();
    }

    // ---- scanning -------------------------------------------------------------------------------

    /** An identifier ({@code $NoName}, {@code Local:1:I} is not one); empty when none starts here. */
    private String word() {
        int s = pos;
        while (pos < n && (Character.isLetterOrDigit(t.charAt(pos)) || t.charAt(pos) == '_' || t.charAt(pos) == '$')) {
            pos++;
        }
        return t.substring(s, pos);
    }

    private Attr name() {
        skipSpaces(n);
        int s = pos;
        String w = word();
        return w.isEmpty() ? Attr.NONE : new Attr(w, new Range(s, pos));
    }

    private boolean atWord(String w) {
        if (!t.startsWith(w, pos)) {
            return false;
        }
        int e = pos + w.length();
        return e >= n || !(Character.isLetterOrDigit(t.charAt(e)) || t.charAt(e) == '_');
    }

    /** {@code [10]} / {@code [2,3]} as the L5X form {@code "10"} / {@code "2 3"}. */
    private Attr dimensions(int limit) {
        int s = pos + 1;
        int e = t.indexOf(']', s);
        if (e < 0 || e > limit) {
            return Attr.NONE;
        }
        pos = e + 1;
        return new Attr(t.substring(s, e).replace(',', ' ').strip(), new Range(s, e));
    }

    /** The {@code ;} ending the statement at the current position (outside quotes and brackets); n when missing. */
    private int statementEnd() {
        int depth = 0;
        int i = pos;
        while (i < n) {
            char c = t.charAt(i);
            if (c == '"' || c == '\'') {
                i = closingQuote(i + 1, c);
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == ';' && depth == 0) {
                return i;
            }
            i++;
        }
        return n;
    }

    /** The closing {@code quote} from {@code s} ({@code $"} and a doubled quote are escapes); n when missing. */
    private int closingQuote(int s, char quote) {
        int i = s;
        while (i < n) {
            char c = t.charAt(i);
            if (c == '$' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && t.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        return n;
    }

    private Attr trimmed(int s, int e) {
        int a = s;
        int b = Math.min(e, n);
        while (a < b && Character.isWhitespace(t.charAt(a))) {
            a++;
        }
        while (b > a && Character.isWhitespace(t.charAt(b - 1))) {
            b--;
        }
        return a == b ? Attr.NONE : new Attr(t.substring(a, b), new Range(a, b));
    }

    /** Whitespace and {@code (* … *)} comments. */
    private void skipBlank() {
        while (pos < n) {
            char c = t.charAt(pos);
            if (Character.isWhitespace(c) || c == '﻿') {
                pos++;
            } else if (t.startsWith("(*", pos)) {
                int e = t.indexOf("*)", pos + 2);
                pos = e < 0 ? n : e + 2;
            } else {
                return;
            }
        }
    }

    private void skipSpaces(int limit) {
        while (pos < limit && pos < n && (t.charAt(pos) == ' ' || t.charAt(pos) == '\t')) {
            pos++;
        }
    }

    private void skipNewlines() {
        while (pos < n && (t.charAt(pos) == '\n' || t.charAt(pos) == '\r')) {
            pos++;
        }
    }

    private void skipLine() {
        pos = lineEnd(t, pos);
        skipNewlines();
    }

    private void skipStatementOrLine() {
        int e = statementEnd();
        int l = lineEnd(t, pos);
        pos = Math.min(n, (e < l ? e : l) + 1);
    }

    /** Past the next {@code END_X} word (or to the end of the file). */
    private void skipPast(String endWord) {
        int i = pos;
        while (true) {
            int k = t.indexOf(endWord, i);
            if (k < 0) {
                pos = n;
                return;
            }
            int after = k + endWord.length();
            boolean startOk = k == 0 || !(Character.isLetterOrDigit(t.charAt(k - 1)) || t.charAt(k - 1) == '_');
            boolean endOk = after >= n || !(Character.isLetterOrDigit(t.charAt(after)) || t.charAt(after) == '_');
            if (startOk && endOk) {
                pos = after;
                return;
            }
            i = after;
        }
    }

    /** An unknown block: past its {@code END_KW} when the file has one, else the rest of the line. */
    private void skipBlock(String kw) {
        if (kw.startsWith("END_") || t.indexOf("END_" + kw, pos) < 0) {
            skipStatementOrLine();
        } else {
            skipPast("END_" + kw);
        }
    }
}
