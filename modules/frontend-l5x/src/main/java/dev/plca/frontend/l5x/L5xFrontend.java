package dev.plca.frontend.l5x;

import dev.plca.ast.Comment;
import dev.plca.ast.Declaration;
import dev.plca.ast.Declaration.Pou;
import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.ast.Initializer;
import dev.plca.ast.Ladder;
import dev.plca.ast.Pragma;
import dev.plca.ast.QualifiedName;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import dev.plca.ast.TypeRef;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import dev.plca.core.Dialect;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceMap;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.core.source.XmlText;
import dev.plca.frontend.l5x.L5xReader.AoiDef;
import dev.plca.frontend.l5x.L5xReader.Attr;
import dev.plca.frontend.l5x.L5xReader.DataTypeDef;
import dev.plca.frontend.l5x.L5xReader.LineDef;
import dev.plca.frontend.l5x.L5xReader.MemberDef;
import dev.plca.frontend.l5x.L5xReader.ProgramDef;
import dev.plca.frontend.l5x.L5xReader.Range;
import dev.plca.frontend.l5x.L5xReader.RoutineDef;
import dev.plca.frontend.l5x.L5xReader.RungDef;
import dev.plca.frontend.l5x.L5xReader.TagDef;
import dev.plca.frontend.l5x.L5xReader.TaskDef;
import dev.plca.frontend.l5x.RungLowering.AoiSignature;
import dev.plca.frontend.st.SyntaxCodes;
import dev.plca.frontend.st.kit.Frontend;
import dev.plca.frontend.st.kit.TwoStageParser.Parsed;
import dev.plca.frontend.st.kit.XmlInput;
import dev.plca.grammar.RockwellStParser.StatementsOnlyContext;
import dev.plca.grammar.RungParser.RungContext;
import dev.plca.grammar.RungParser.RungsContext;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Entry point of the Rockwell frontend: a Studio 5000 {@code .L5X} export to one {@link SourceUnit} of dialect
 * {@link Dialect#ROCKWELL}. Ladder routines are parsed with the rung grammar and lowered to statements; ST routines are
 * parsed with the Rockwell ST dialect. The mapping is documented in {@code docs/grammar/rockwell.md} §2. Never throws
 * for bad input (design rule 2).
 *
 * <p>Not thread-safe: use one instance per thread (it reuses lexer and parser objects).
 */
public final class L5xFrontend implements Frontend {

    /** Every extension this frontend reads (lower case, with dot). */
    public static final Set<String> EXTENSIONS = Set.of(".l5x");

    private final RungParserDriver rungParser;
    private final RockwellStParserDriver stParser;

    public L5xFrontend() {
        this(true);
    }

    /** {@code twoStage = false} parses with full LL only; results are identical (for benchmarks). */
    public L5xFrontend(boolean twoStage) {
        this.rungParser = new RungParserDriver(twoStage);
        this.stParser = new RockwellStParserDriver(twoStage);
    }

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public Result parse(SourceFile file, SourceRegistry registry, DiagnosticSink sink) {
        Run run = new Run(file, registry, sink);
        try {
            run.unit();
        } catch (StackOverflowError e) {
            run.internal("input is nested too deeply to analyse");
        } catch (RuntimeException e) {
            run.internal("internal error while reading the L5X export: " + e);
        }
        return new Result(
                new SourceUnit(file.path(), Dialect.ROCKWELL, run.declarations, run.comments, run.pragmas), run.stats);
    }

    /** The state of one file's translation. */
    private final class Run {
        final SourceFile file;
        final SourceRegistry registry;
        final DiagnosticSink sink;
        final SourceText whole;
        final List<Declaration> declarations = new ArrayList<>();
        final List<Comment> comments = new ArrayList<>();
        final List<Pragma> pragmas = new ArrayList<>();
        Stats stats = Stats.NONE;
        Map<String, AoiSignature> aois = Map.of();

        Run(SourceFile file, SourceRegistry registry, DiagnosticSink sink) {
            this.file = file;
            this.registry = registry;
            this.sink = sink;
            this.whole = registry.register(file);
        }

        void unit() {
            L5xReader.Project p = L5xReader.read(file.text());
            if (p.error != null) {
                sink.report(Diagnostic.error(
                        SyntaxCodes.MALFORMED_XML,
                        "malformed XML: " + p.error + "; the rest of the file is not analysed",
                        Span.at(whole.id(), p.errorOffset)));
            }
            if (!p.targetType.present() && p.error == null) {
                sink.report(Diagnostic.of(
                        L5xCodes.NOT_L5X,
                        Severity.INFO,
                        "'" + file.path() + "' is not a Logix 5000 export (no RSLogix5000Content root); skipped",
                        Span.at(whole.id(), 0)));
                return;
            }
            // a partial export (one routine, rung, AOI…) refers to tags it does not contain: the model needs to know
            pragmas.add(attribute("TargetType", p.targetType.value(), nameSpan(p.targetType, 0)));
            aois = signatures(p.aois);
            for (DataTypeDef d : p.dataTypes) {
                declarations.add(dataType(d));
            }
            for (AoiDef a : p.aois) {
                declarations.add(aoi(a));
            }
            if (!p.tags.isEmpty()) {
                Identifier list = p.controller.present()
                        ? name(p.controller, 0)
                        : new Identifier("Controller", Span.at(whole.id(), 0));
                for (VarBlock b : varBlocks(p.tags, true)) {
                    declarations.add(new Declaration.GlobalVars(list, b, b.span()));
                }
            }
            for (ProgramDef prog : p.programs) {
                declarations.add(program(prog));
            }
            for (RoutineDef r : p.looseRoutines) {
                declarations.add(routine(r));
            }
            if (!p.tasks.isEmpty()) {
                declarations.add(tasks(p));
            }
        }

        // ---- data types -------------------------------------------------------------------------

        Declaration dataType(DataTypeDef d) {
            List<VarDecl> members = new ArrayList<>();
            for (MemberDef m : d.members) {
                if (m.hidden) {
                    continue; // the SINT host of BIT members: never addressed by logic
                }
                members.add(variable(m, null, List.of()));
            }
            Span span = elementSpan(d.offset);
            return new Declaration.TypeDecl(
                    name(d.name, d.offset), null, new TypeRef.StructType(members, span), null, List.of(), span);
        }

        // ---- AOIs -------------------------------------------------------------------------------

        Map<String, AoiSignature> signatures(List<AoiDef> defs) {
            Map<String, AoiSignature> map = new HashMap<>();
            for (AoiDef a : defs) {
                if (!a.name.present()) {
                    continue;
                }
                List<AoiSignature.Param> operands = new ArrayList<>();
                for (TagDef p : a.parameters) {
                    String n = p.name.value();
                    if (p.required
                            && n != null
                            && !n.equalsIgnoreCase("EnableIn")
                            && !n.equalsIgnoreCase("EnableOut")) {
                        operands.add(new AoiSignature.Param(n, p.usage));
                    }
                }
                map.putIfAbsent(a.name.value().toUpperCase(Locale.ROOT), new AoiSignature(a.name.value(), operands));
            }
            return map;
        }

        Pou aoi(AoiDef a) {
            Map<VarBlock.Section, List<VarDecl>> sections = new LinkedHashMap<>();
            for (TagDef p : a.parameters) {
                VarBlock.Section s = switch (p.usage == null ? "" : p.usage) {
                    case "Output" -> VarBlock.Section.OUTPUT;
                    case "InOut" -> VarBlock.Section.IN_OUT;
                    default -> VarBlock.Section.INPUT;
                };
                sections.computeIfAbsent(s, k -> new ArrayList<>()).add(tagVariable(p));
            }
            for (TagDef t : a.localTags) {
                sections.computeIfAbsent(VarBlock.Section.VAR, k -> new ArrayList<>())
                        .add(tagVariable(t));
            }
            List<VarBlock> blocks = new ArrayList<>();
            sections.forEach((section, vars) -> blocks.add(block(section, Set.of(), vars)));

            List<Statement> body = null;
            String language = a.encoded ? "Encrypted" : null;
            List<Pou> members = new ArrayList<>();
            for (RoutineDef r : a.routines) {
                Pou routine = routine(r);
                if (r.name.present() && r.name.value().equalsIgnoreCase("Logic")) {
                    body = routine.body();
                    language = routine.bodyLanguage();
                } else {
                    members.add(routine);
                }
            }
            if (a.encoded) {
                sink.report(Diagnostic.of(
                        L5xCodes.PROTECTED,
                        Severity.INFO,
                        "Add-On Instruction '" + a.name.value() + "' is source-protected; only its parameters are"
                                + " analysed",
                        nameSpan(a.name, a.offset)));
            }
            List<Pragma> attributes = new ArrayList<>();
            if (a.safety) {
                attributes.add(attribute("Class", "Safety", nameSpan(a.name, a.offset)));
            }
            return new Pou(
                    PouKind.FUNCTION_BLOCK,
                    name(a.name, a.offset),
                    Set.of(),
                    null,
                    List.of(),
                    List.of(),
                    blocks,
                    members,
                    body,
                    language,
                    attributes,
                    elementSpan(a.offset));
        }

        // ---- programs and routines ----------------------------------------------------------------

        Pou program(ProgramDef p) {
            List<Pou> routines = new ArrayList<>();
            for (RoutineDef r : p.routines) {
                routines.add(routine(r));
            }
            List<Statement> body = new ArrayList<>();
            if (p.mainRoutine.present() && !p.mainRoutine.value().isEmpty()) {
                body.add(jsr(p.mainRoutine, p.offset));
            }
            List<Pragma> attributes = new ArrayList<>();
            Span at = nameSpan(p.name, p.offset);
            if (p.faultRoutine.present() && !p.faultRoutine.value().isEmpty()) {
                attributes.add(attribute("FaultRoutine", p.faultRoutine.value(), nameSpan(p.faultRoutine, p.offset)));
            }
            if (p.disabled) {
                attributes.add(attribute("Disabled", "true", at));
            }
            if (p.safety) {
                attributes.add(attribute("Class", "Safety", at));
            }
            return new Pou(
                    PouKind.PROGRAM,
                    name(p.name, p.offset),
                    Set.of(),
                    null,
                    List.of(),
                    List.of(),
                    varBlocks(p.tags, false),
                    routines,
                    body,
                    "L5X",
                    attributes,
                    elementSpan(p.offset));
        }

        /** The program's scan: {@code JSR(MainRoutine);}, made up from {@code MainRoutineName}. */
        Statement jsr(Attr main, int offset) {
            Span s = nameSpan(main, offset);
            Identifier jsr = new Identifier("JSR", s);
            Expression routine = new Expression.NameRef(new Identifier(main.value(), s), false, s);
            Expression call = new Expression.Call(
                    new Expression.NameRef(jsr, false, s),
                    List.of(new Expression.Argument(Expression.ArgumentKind.POSITIONAL, null, false, routine, s)),
                    s);
            return new Statement.CallStatement(call, s);
        }

        Pou routine(RoutineDef r) {
            List<Statement> body = null;
            String type = r.type;
            String language = type.isEmpty() ? null : type;
            switch (type.toUpperCase(Locale.ROOT)) {
                case "RLL" -> body = ladder(r);
                case "ST" -> body = structuredText(r);
                case "ENCRYPTED" ->
                    sink.report(Diagnostic.of(
                            L5xCodes.PROTECTED,
                            Severity.INFO,
                            "routine '" + r.name.value() + "' is source-protected and not analysed",
                            nameSpan(r.name, r.offset)));
                // a routine without Type is a reference in a partial export (Use="Reference"): no body to analyse
                case "" -> {}
                default ->
                    sink.report(Diagnostic.of(
                            L5xCodes.UNPARSED_ROUTINE,
                            Severity.INFO,
                            "routine '" + r.name.value() + "' is written in " + type + ", which is not analysed yet",
                            nameSpan(r.name, r.offset)));
            }
            return new Pou(
                    PouKind.ROUTINE,
                    name(r.name, r.offset),
                    Set.of(),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    body,
                    language,
                    List.of(),
                    elementSpan(r.offset));
        }

        // ---- ladder ---------------------------------------------------------------------------------

        /**
         * The routine's rungs as one text (rung texts joined with newlines through the source map), parsed once, then
         * each parsed rung matched back to its {@code <Rung>} by offset and lowered.
         */
        List<Statement> ladder(RoutineDef r) {
            List<RungDef> rungs = r.rungs;
            StringBuilder text = new StringBuilder();
            SourceMap.Builder map = SourceMap.builder();
            int[] starts = new int[rungs.size()];
            String fileText = file.text();
            for (int k = 0; k < rungs.size(); k++) {
                RungDef d = rungs.get(k);
                starts[k] = text.length();
                if (d.text.present()) {
                    int from = text.length();
                    XmlText.decodeInto(fileText, d.text.start(), d.text.end(), text, map);
                    if (!endsWithSemicolon(text, from)) {
                        // every rung's text ends with ';': close it here so it cannot swallow the next rung
                        sink.report(Diagnostic.warning(
                                L5xCodes.UNTERMINATED_RUNG,
                                "rung " + d.number + " does not end with ';'",
                                whole.span(d.text.start(), d.text.end())));
                        text.append(';');
                        map.replacement(d.text.end(), 0, 1);
                    }
                    text.append('\n');
                    map.replacement(d.text.end(), 0, 1);
                }
                if (d.comment.present()) {
                    SourceText c = registry.registerXmlContent(file, d.comment.start(), d.comment.end());
                    comments.add(
                            new Comment(Comment.Kind.BLOCK, c.span(0, c.text().length())));
                }
            }
            Ladder[] ladders = new Ladder[rungs.size()];
            boolean[] parsed = new boolean[rungs.size()];
            if (!text.isEmpty()) {
                SourceText routineText = registry.registerFragment(file, text.toString(), map.build());
                // where the syntax errors are: a rung with one keeps its number but not its (partial) drawing
                List<Integer> errors = new ArrayList<>();
                DiagnosticSink capture = diag -> {
                    if (diag.severity() == Severity.ERROR && diag.span().textId() == routineText.id()) {
                        errors.add(diag.span().start());
                    }
                    sink.report(diag);
                };
                Parsed p = rungParser.parse(routineText, capture);
                count(p);
                RungAstBuilder b = new RungAstBuilder(routineText, p.tokens(), capture);
                if (p.tree() instanceof RungsContext rs) {
                    for (RungContext rc : rs.rung()) {
                        int k = rungAt(starts, RungAstBuilder.start(rc));
                        if (k >= 0 && !parsed[k]) {
                            ladders[k] = b.rung(rc);
                            parsed[k] = true;
                        }
                    }
                }
                b.collectHidden(comments, pragmas);
                for (int offset : errors) {
                    int k = rungAt(starts, Math.min(offset, Math.max(0, text.length() - 1)));
                    if (k >= 0) {
                        ladders[k] = null;
                    }
                }
            }
            RungLowering lowering = new RungLowering(aois);
            List<Statement> body = new ArrayList<>(rungs.size());
            for (int k = 0; k < rungs.size(); k++) {
                RungDef d = rungs.get(k);
                Ladder l = ladders[k];
                if (l != null) {
                    body.add(new Statement.Rung(d.number, d.type, l, lowering.lower(l), l.span()));
                } else {
                    Span at = elementSpan(d.offset);
                    // no text: an empty rung; text that did not parse: a diagnostic was reported
                    List<Statement> lowered =
                            d.text.present() && !blank(d.text) ? List.of(new Statement.Invalid(at)) : List.of();
                    body.add(new Statement.Rung(d.number, d.type, null, lowered, at));
                }
            }
            return body;
        }

        private static boolean endsWithSemicolon(StringBuilder text, int from) {
            for (int i = text.length() - 1; i >= from; i--) {
                char c = text.charAt(i);
                if (!Character.isWhitespace(c)) {
                    return c == ';';
                }
            }
            return true; // empty text: an empty rung
        }

        private boolean blank(Range r) {
            String s = XmlText.decode(file.text(), r.start(), r.end()).text().strip();
            return s.isEmpty() || s.equals(";");
        }

        /** Index of the rung whose text contains {@code offset}. */
        private static int rungAt(int[] starts, int offset) {
            if (offset < 0) {
                return -1;
            }
            int k = java.util.Arrays.binarySearch(starts, offset);
            if (k < 0) {
                k = -k - 2;
            } else {
                // several empty rungs share a start: the last of them owns the text
                while (k + 1 < starts.length && starts[k + 1] == offset) {
                    k++;
                }
            }
            return k;
        }

        // ---- structured text ------------------------------------------------------------------------

        /** The routine's lines as one text, so the engineer's line numbers are kept by the source map. */
        List<Statement> structuredText(RoutineDef r) {
            StringBuilder text = new StringBuilder();
            SourceMap.Builder map = SourceMap.builder();
            String fileText = file.text();
            for (LineDef line : r.lines) {
                XmlText.decodeInto(fileText, line.text().start(), line.text().end(), text, map);
                text.append('\n');
                map.replacement(line.text().end(), 0, 1);
            }
            SourceText routineText = registry.registerFragment(file, text.toString(), map.build());
            Parsed p = stParser.parse(routineText, sink);
            count(p);
            RockwellStAstBuilder b = new RockwellStAstBuilder(routineText, p.tokens(), sink);
            List<Statement> body = b.statementsOnly((StatementsOnlyContext) p.tree());
            b.collectHidden(comments, pragmas);
            return AoiCalls.rewrite(body, aois);
        }

        private void count(Parsed p) {
            stats = stats.plus(new Stats(
                    1, p.fallback() ? 1 : 0, p.fallback() && p.syntaxErrors() == 0 ? 1 : 0, p.syntaxErrors()));
        }

        // ---- tags -----------------------------------------------------------------------------------

        /** Tags grouped into VAR blocks by section (program parameters) and constness, in order of appearance. */
        List<VarBlock> varBlocks(List<TagDef> tags, boolean global) {
            record Key(VarBlock.Section section, boolean constant, boolean isPublic) {}
            Map<Key, List<VarDecl>> groups = new LinkedHashMap<>();
            for (TagDef t : tags) {
                VarBlock.Section section;
                boolean isPublic = false;
                if (global) {
                    section = VarBlock.Section.GLOBAL;
                } else {
                    section = switch (t.usage == null ? "" : t.usage) {
                        case "Input" -> VarBlock.Section.INPUT;
                        case "Output" -> VarBlock.Section.OUTPUT;
                        case "InOut" -> VarBlock.Section.IN_OUT;
                        default -> VarBlock.Section.VAR;
                    };
                    isPublic = "Public".equals(t.usage);
                }
                groups.computeIfAbsent(new Key(section, t.constant, isPublic), k -> new ArrayList<>())
                        .add(tagVariable(t));
            }
            List<VarBlock> out = new ArrayList<>();
            groups.forEach((k, vars) -> {
                Set<VarBlock.Qualifier> q = EnumSet.noneOf(VarBlock.Qualifier.class);
                if (k.constant()) {
                    q.add(VarBlock.Qualifier.CONSTANT);
                }
                if (k.isPublic()) {
                    q.add(VarBlock.Qualifier.PUBLIC);
                }
                out.add(block(k.section(), q, vars));
            });
            return out;
        }

        VarBlock block(VarBlock.Section section, Set<VarBlock.Qualifier> qualifiers, List<VarDecl> vars) {
            Span span = vars.isEmpty()
                    ? Span.at(whole.id(), 0)
                    : vars.getFirst().span().union(vars.getLast().span());
            return new VarBlock(section, qualifiers, vars, List.of(), span);
        }

        VarDecl tagVariable(TagDef t) {
            List<Pragma> attributes = new ArrayList<>();
            Span at = nameSpan(t.name, t.offset);
            if (t.externalAccess.present()) {
                attributes.add(
                        attribute("ExternalAccess", t.externalAccess.value(), nameSpan(t.externalAccess, t.offset)));
            }
            if (!t.tagType.equalsIgnoreCase("Base") && !t.tagType.equalsIgnoreCase("Alias")) {
                attributes.add(attribute("TagType", t.tagType, at)); // Produced, Consumed
            }
            if (t.safety) {
                attributes.add(attribute("Class", "Safety", at));
            }
            Identifier overlay = t.aliasFor.present() ? name(t.aliasFor, t.offset) : null;
            return variable(t, overlay, attributes);
        }

        VarDecl variable(L5xReader.Typed t, Identifier overlay, List<Pragma> attributes) {
            Identifier name = name(t.name, t.offset);
            TypeRef type = typeRef(t);
            return new VarDecl(
                    name, null, null, type, initializer(t), false, attributes, overlay, elementSpan(t.offset));
        }

        /** {@code DataType} with {@code Dimensions}: {@code DINT}, {@code DINT[10]} (0..9), {@code REAL[2,3]}. */
        TypeRef typeRef(L5xReader.Typed t) {
            if (!t.dataType.present()) {
                return null; // alias tags take the type of their base
            }
            Span s = nameSpan(t.dataType, t.offset);
            String typeName = t.dataType.value().equalsIgnoreCase("BIT") ? "BOOL" : t.dataType.value();
            TypeRef named = new TypeRef.Named(
                    new QualifiedName(List.of(new Identifier(typeName, s)), s), List.of(), null, null, null, s);
            List<TypeRef.Dimension> dims = new ArrayList<>();
            if (t.dimensions.present()) {
                Span ds = nameSpan(t.dimensions, t.offset);
                for (String d : DIMENSIONS.split(t.dimensions.value().strip())) {
                    long n = parseLong(d);
                    if (n > 0) {
                        dims.add(new TypeRef.Dimension(integer("0", ds), integer(Long.toString(n - 1), ds), ds));
                    }
                }
            }
            return dims.isEmpty() ? named : new TypeRef.ArrayType(dims, named, List.of(), s);
        }

        /** Elementary initial values ({@code DataValue Value}, string data); structures and arrays have none. */
        Initializer initializer(L5xReader.Typed t) {
            if (t.initialValue == null || !t.initialValueRange.present()) {
                return null;
            }
            Span s = whole.span(t.initialValueRange.start(), t.initialValueRange.end());
            String v = t.initialValue.strip();
            if (t.initialIsString) {
                // String data is CDATA content: decode it like other embedded text
                String decoded = XmlText.decode(file.text(), t.initialValueRange.start(), t.initialValueRange.end())
                        .text()
                        .strip();
                return new Expression.Literal(Expression.LiteralKind.STRING, decoded, null, s);
            }
            String type = t.dataType.present() ? t.dataType.value().toUpperCase(Locale.ROOT) : "";
            if (type.equals("BOOL") || type.equals("BIT")) {
                return new Expression.Literal(
                        Expression.LiteralKind.BOOL, v.equals("1") ? "TRUE" : v.equals("0") ? "FALSE" : v, null, s);
            }
            if (INTEGER.matcher(v).matches()) {
                return new Expression.Literal(Expression.LiteralKind.INTEGER, v, null, s);
            }
            if (REAL.matcher(v).matches()) {
                return new Expression.Literal(Expression.LiteralKind.REAL, v, null, s);
            }
            return null;
        }

        // ---- tasks ----------------------------------------------------------------------------------

        Declaration tasks(L5xReader.Project p) {
            List<Declaration.ConfigElement> elements = new ArrayList<>();
            for (TaskDef t : p.tasks) {
                Span at = nameSpan(t.name, t.offset);
                List<Expression.Argument> settings = new ArrayList<>();
                settings.add(setting(
                        "TYPE",
                        new Expression.Literal(Expression.LiteralKind.STRING, "'" + t.type + "'", null, at),
                        at));
                if (t.priority.present() && INTEGER.matcher(t.priority.value()).matches()) {
                    Span s = nameSpan(t.priority, t.offset);
                    settings.add(setting("PRIORITY", integer(t.priority.value(), s), s));
                }
                if (t.type.equalsIgnoreCase("PERIODIC")
                        && t.rate.present()
                        && REAL.matcher(t.rate.value()).matches()) {
                    Span s = nameSpan(t.rate, t.offset);
                    settings.add(setting(
                            "INTERVAL",
                            new Expression.Literal(Expression.LiteralKind.TIME, t.rate.value() + "ms", "T", s),
                            s));
                }
                Identifier task = name(t.name, t.offset);
                elements.add(new Declaration.Task(task, settings, elementSpan(t.offset)));
                for (Attr program : t.programs) {
                    if (!program.present()) {
                        continue;
                    }
                    Identifier pn = name(program, t.offset);
                    elements.add(new Declaration.ProgramInstance(
                            pn, task, new QualifiedName(List.of(pn), pn.span()), List.of(), pn.span()));
                }
            }
            Identifier controller = p.controller.present()
                    ? name(p.controller, 0)
                    : new Identifier("Controller", Span.at(whole.id(), 0));
            return new Declaration.Configuration(controller, List.of(), elements, controller.span());
        }

        private static Expression.Argument setting(String name, Expression value, Span span) {
            return new Expression.Argument(
                    Expression.ArgumentKind.INPUT, new Identifier(name, span), false, value, span);
        }

        // ---- names, spans, diagnostics ------------------------------------------------------------------

        Identifier name(Attr a, int elementOffset) {
            return new Identifier(a.present() ? a.value() : "<missing>", nameSpan(a, elementOffset));
        }

        Span nameSpan(Attr a, int elementOffset) {
            if (a.range().present()) {
                return whole.span(a.range().start(), a.range().end());
            }
            return Span.at(
                    whole.id(), Math.max(0, Math.min(elementOffset, file.text().length())));
        }

        /** The start tag of the element at {@code offset}. */
        Span elementSpan(int offset) {
            int s = Math.max(0, Math.min(offset, file.text().length()));
            return whole.span(s, XmlInput.endOfStartTag(file.text(), s));
        }

        Pragma attribute(String name, String value, Span span) {
            return new Pragma(Pragma.Kind.ATTRIBUTE, name, value, name + "=\"" + value + "\"", span);
        }

        Expression integer(String text, Span span) {
            return new Expression.Literal(Expression.LiteralKind.INTEGER, text, null, span);
        }

        void internal(String message) {
            sink.report(Diagnostic.of(SyntaxCodes.INTERNAL, Severity.ERROR, message, Span.at(whole.id(), 0)));
        }
    }

    private static final Pattern DIMENSIONS = Pattern.compile("[\\s,]+");
    private static final Pattern INTEGER = Pattern.compile("[-+]?\\d+");
    private static final Pattern REAL = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
