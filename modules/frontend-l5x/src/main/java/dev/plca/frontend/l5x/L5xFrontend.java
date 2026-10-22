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
import dev.plca.ast.Sfc;
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
import dev.plca.frontend.st.kit.SfcLowering;
import dev.plca.frontend.st.kit.SimpleExpressions;
import dev.plca.frontend.st.kit.TwoStageParser.Parsed;
import dev.plca.frontend.st.kit.XmlInput;
import dev.plca.grammar.RockwellStParser.ConditionOnlyContext;
import dev.plca.grammar.RockwellStParser.StatementsOnlyContext;
import dev.plca.grammar.RungParser.RungContext;
import dev.plca.grammar.RungParser.RungsContext;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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
    public static final Set<String> EXTENSIONS = Set.of(".l5x", ".l5k");

    private final RungParserDriver rungParser;
    private final RockwellStParserDriver stParser;
    /** Drawing mode: the routines whose rungs are parsed (paths as {@code Diagrams} names them); null: a full parse. */
    private final Predicate<String> drawn;

    public L5xFrontend() {
        this(true);
    }

    /** {@code twoStage = false} parses with full LL only; results are identical (for benchmarks). */
    public L5xFrontend(boolean twoStage) {
        this(twoStage, null);
    }

    private L5xFrontend(boolean twoStage, Predicate<String> drawn) {
        this.rungParser = new RungParserDriver(twoStage);
        this.stParser = new RockwellStParserDriver(twoStage);
        this.drawn = drawn;
    }

    /**
     * A quick read for drawing one routine of a large export while the project still loads: rungs are parsed only for
     * {@code routine} ({@code Program/Routine}, an AOI's name for its Logic, {@code Aoi/Routine}; null: the first
     * ladder routine with rungs, the one a viewer shows first). Every other ladder routine keeps its rungs as
     * placeholders (number and place, no drawing), ST bodies are left out. Not for analysis.
     */
    public static L5xFrontend forDrawing(String routine) {
        return new L5xFrontend(true, routine == null ? FIRST : path -> path.equals(routine));
    }

    /** Drawing mode's "the first ladder routine with rungs", in the order {@code Diagrams} lists them. */
    private static final Predicate<String> FIRST = path -> false;

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
        /** L5X (XML) or L5K (text): where text is decoded and how far an element's span reaches. */
        final boolean xml;

        final List<Declaration> declarations = new ArrayList<>();
        final List<Comment> comments = new ArrayList<>();
        final List<Pragma> pragmas = new ArrayList<>();
        Stats stats = Stats.NONE;
        Map<String, AoiSignature> aois = Map.of();
        /** The ladder routines whose rungs are parsed (drawing mode); null: all. */
        Predicate<String> parsed = drawn;

        Run(SourceFile file, SourceRegistry registry, DiagnosticSink sink) {
            this.file = file;
            this.xml = !Frontend.extension(file.path()).equals(".l5k");
            this.registry = registry;
            this.sink = sink;
            this.whole = registry.register(file);
        }

        void unit() {
            L5xReader.Project p = xml ? L5xReader.read(file.text()) : L5kReader.read(file.text());
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
                        xml
                                ? "'" + file.path()
                                        + "' is not a Logix 5000 export (no RSLogix5000Content root); skipped"
                                : "'" + file.path() + "' is not a Logix 5000 export (no CONTROLLER block); skipped",
                        Span.at(whole.id(), 0)));
                return;
            }
            // a partial export (one routine, rung, AOI…) refers to tags it does not contain: the model needs to know
            pragmas.add(attribute("TargetType", p.targetType.value(), nameSpan(p.targetType, 0)));
            if (drawn == FIRST) {
                String first = firstDrawn(p);
                parsed = path -> path.equals(first);
            }
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
                declarations.add(routine(r, ""));
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
                boolean logic = r.name.present() && r.name.value().equalsIgnoreCase("Logic");
                Pou routine = routine(r, logic ? "\0" + a.name.value() : a.name.value() + "/");
                if (logic) {
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
                routines.add(routine(r, p.name.value() + "/"));
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

        /**
         * {@code prefix}: the owner's path and {@code /} for {@link #drawn}, or {@code \0} and the AOI's name for its
         * Logic routine (drawn under the AOI's name).
         */
        Pou routine(RoutineDef r, String prefix) {
            List<Statement> body = null;
            String type = r.type;
            String language = type.isEmpty() ? null : type;
            String path = prefix.startsWith("\0") ? prefix.substring(1) : prefix + r.name.value();
            switch (type.toUpperCase(Locale.ROOT)) {
                case "RLL" -> body = parsed == null || parsed.test(path) ? ladder(r) : placeholders(r);
                case "ST" -> body = drawn == null ? structuredText(r) : null;
                case "SFC" -> body = r.sfc == null ? null : chart(r);
                case "FBD" ->
                    body = r.fbd == null
                            ? null
                            : new FbdTranslator(sink, this::operandExpression, this::elementSpan, aois.keySet())
                                    .translate(r.fbd);
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

        // ---- SFC (M10) -------------------------------------------------------------------------------

        /**
         * An SFC routine: the chart's semantics (steps with their actions, transitions with the steps they leave and
         * enter through branches) lowered as for PLCopen, and the chart as drawn. Step operands are the routine's
         * SFC_STEP tags. TODO(verify-format): {@code docs/grammar/rockwell.md} §SFC.
         */
        List<Statement> chart(RoutineDef r) {
            L5xReader.SfcDef d = r.sfc;
            Map<Integer, L5xReader.SfcNode> byId = new HashMap<>();
            for (L5xReader.SfcNode n : d.nodes) {
                byId.putIfAbsent(n.id, n);
            }
            Map<Integer, List<Integer>> next = new HashMap<>();
            Map<Integer, List<Integer>> previous = new HashMap<>();
            for (int[] link : d.links) {
                int from = d.legs.getOrDefault(link[0], link[0]);
                int to = d.legs.getOrDefault(link[1], link[1]);
                next.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
                previous.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
            }
            List<Sfc.Step> steps = new ArrayList<>();
            List<Sfc.Transition> transitions = new ArrayList<>();
            List<Sfc.Element> drawn = new ArrayList<>();
            for (L5xReader.SfcNode n : d.nodes) {
                Span span = elementSpan(n.offset);
                switch (n.tag) {
                    case "Step" -> {
                        List<Sfc.Action> actions = new ArrayList<>();
                        for (L5xReader.SfcNode a : d.nodes) {
                            if (a.tag.equals("Action") && a.step == n.id) {
                                actions.add(new Sfc.Action(
                                        qualifier(a.qualifier),
                                        null,
                                        null,
                                        statements(a.lines),
                                        elementSpan(a.offset)));
                            }
                        }
                        steps.add(new Sfc.Step(operand(n), n.initial, actions, span));
                    }
                    case "Transition" -> {
                        List<Identifier> from = new ArrayList<>();
                        walk(n.id, byId, previous, new HashSet<>(), from);
                        List<Identifier> to = new ArrayList<>();
                        walk(n.id, byId, next, new HashSet<>(), to);
                        transitions.add(new Sfc.Transition(from, to, condition(n, span), span));
                    }
                    default -> {}
                }
                Sfc.Element e = drawn(n, d, previous, next);
                if (e != null) {
                    drawn.add(e);
                    Sfc.Element block = actionBlock(n, e, d);
                    if (block != null) {
                        drawn.add(block);
                    }
                }
            }
            Span span = elementSpan(r.offset);
            Sfc chart = new Sfc(steps, transitions, new Sfc.Drawing(drawn), span);
            List<Statement> lowered = SfcLowering.lower(chart, a -> a.inline() == null ? List.of() : a.inline());
            return List.of(new Statement.Chart(chart, lowered, span));
        }

        private Identifier operand(L5xReader.SfcNode n) {
            return n.operand.present()
                    ? name(n.operand, n.offset)
                    : new Identifier("<" + n.tag + " " + n.id + ">", elementSpan(n.offset));
        }

        /**
         * The steps a transition leaves ({@code links}: backwards) or enters (forwards), through branches; a transition
         * or stop ends the walk.
         */
        private void walk(
                int id,
                Map<Integer, L5xReader.SfcNode> byId,
                Map<Integer, List<Integer>> links,
                Set<Integer> seen,
                List<Identifier> out) {
            for (int other : links.getOrDefault(id, List.of())) {
                L5xReader.SfcNode n = byId.get(other);
                if (n == null || !seen.add(other)) {
                    continue;
                }
                if (n.tag.equals("Step")) {
                    out.add(operand(n));
                } else if (n.tag.equals("Branch")) {
                    walk(other, byId, links, seen, out);
                }
            }
        }

        /** The transition's ST condition; no text: always true. */
        private Expression condition(L5xReader.SfcNode n, Span span) {
            boolean empty = true;
            for (LineDef l : n.lines) {
                empty &= blank(l.text());
            }
            if (empty) {
                return new Expression.Literal(Expression.LiteralKind.BOOL, "TRUE", null, span);
            }
            SourceText text = lines(n.lines);
            Parsed p = stParser.parseCondition(text, sink);
            count(p);
            RockwellStAstBuilder b = new RockwellStAstBuilder(text, p.tokens(), sink);
            Expression e = p.tree() instanceof ConditionOnlyContext x ? b.conditionOnly(x) : null;
            b.collectHidden(comments, pragmas);
            return e == null ? new Expression.Invalid(span) : e;
        }

        /** Logix qualifier words as IEC qualifiers. TODO(verify-format): words other than the corpus's. */
        private static String qualifier(String q) {
            return switch (q == null ? "" : q) {
                case "Stored" -> "S";
                case "Reset" -> "R";
                case "TimeLimited" -> "L";
                case "TimeDelayed" -> "D";
                case "StoredTimeDelayed" -> "SD";
                case "StoredTimeLimited" -> "SL";
                case "Pulse" -> "P";
                case "PulseRisingEdge" -> "P1";
                case "PulseFallingEdge" -> "P0";
                default -> "N";
            };
        }

        /**
         * The element as drawn. Rockwell gives each step, transition and stop an X/Y, taken as its centre, and a branch
         * only its Y (a viewer spans it over its links). TODO(verify-format): centre or corner, and element sizes.
         */
        private Sfc.Element drawn(
                L5xReader.SfcNode n,
                L5xReader.SfcDef d,
                Map<Integer, List<Integer>> previous,
                Map<Integer, List<Integer>> next) {
            Sfc.Kind kind;
            List<String> lines = List.of();
            int w;
            int h;
            switch (n.tag) {
                case "Step", "Stop" -> {
                    kind = n.tag.equals("Step") ? Sfc.Kind.STEP : Sfc.Kind.STOP;
                    w = 80;
                    h = 30;
                }
                case "Transition" -> {
                    kind = Sfc.Kind.TRANSITION;
                    w = 20;
                    h = 10;
                    lines = List.of(conditionText(n));
                }
                case "Branch" -> {
                    boolean simultaneous = "Simultaneous".equals(n.branchType);
                    // exports with placeholder words (DUMMY_ID_…): the links tell which way it goes
                    boolean diverge = "Diverge".equals(n.branchFlow)
                            || !"Converge".equals(n.branchFlow)
                                    && next.getOrDefault(n.id, List.of()).size()
                                            > previous.getOrDefault(n.id, List.of())
                                                    .size();
                    kind = simultaneous
                            ? diverge ? Sfc.Kind.SIMULTANEOUS_DIVERGENCE : Sfc.Kind.SIMULTANEOUS_CONVERGENCE
                            : diverge ? Sfc.Kind.SELECTION_DIVERGENCE : Sfc.Kind.SELECTION_CONVERGENCE;
                    w = 0;
                    h = 3;
                }
                default -> {
                    return null;
                }
            }
            List<Sfc.Link> inputs = new ArrayList<>();
            for (int p : previous.getOrDefault(n.id, List.of())) {
                inputs.add(new Sfc.Link(p, List.of()));
            }
            String name = n.operand.present() ? n.operand.value() : null;
            int x = w == 0 ? n.x : n.x - w / 2;
            Sfc.Element e = new Sfc.Element(
                    n.id, kind, name, n.initial, lines, x, n.y - h / 2, w, h, inputs, elementSpan(n.offset));
            return e;
        }

        /** A step's actions drawn beside it, as PLCopen draws an action block (an ID no element of the chart has). */
        private Sfc.Element actionBlock(L5xReader.SfcNode step, Sfc.Element drawn, L5xReader.SfcDef d) {
            if (!step.tag.equals("Step")) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            int offset = -1;
            for (L5xReader.SfcNode a : d.nodes) {
                if (a.tag.equals("Action") && a.step == step.id) {
                    lines.add(qualifier(a.qualifier) + " " + (a.operand.present() ? a.operand.value() : "Action"));
                    offset = offset < 0 ? a.offset : offset;
                }
            }
            if (lines.isEmpty()) {
                return null;
            }
            int id = Integer.MIN_VALUE + step.id;
            return new Sfc.Element(
                    id,
                    Sfc.Kind.ACTION_BLOCK,
                    null,
                    false,
                    lines,
                    drawn.x() + drawn.width() + 30,
                    drawn.y(),
                    0,
                    0,
                    List.of(new Sfc.Link(step.id, List.of())),
                    elementSpan(offset));
        }

        private String conditionText(L5xReader.SfcNode n) {
            StringBuilder b = new StringBuilder();
            for (LineDef l : n.lines) {
                b.append(decode(l.text().start(), l.text().end())).append(' ');
            }
            String t = b.toString().strip().replaceAll("\\s+", " ");
            return t.isEmpty() && n.operand.present() ? n.operand.value() : t;
        }

        // ---- ladder ---------------------------------------------------------------------------------

        /**
         * The path of the first ladder routine with rungs as {@code Diagrams} lists them: AOIs (Logic first), programs.
         */
        String firstDrawn(L5xReader.Project p) {
            for (AoiDef a : p.aois) {
                for (boolean logicPass : new boolean[] {true, false}) {
                    for (RoutineDef r : a.routines) {
                        boolean logic = r.name.present() && r.name.value().equalsIgnoreCase("Logic");
                        if (logic == logicPass && drawable(r)) {
                            return logic ? a.name.value() : a.name.value() + "/" + r.name.value();
                        }
                    }
                }
            }
            for (ProgramDef prog : p.programs) {
                for (RoutineDef r : prog.routines) {
                    if (drawable(r)) {
                        return prog.name.value() + "/" + r.name.value();
                    }
                }
            }
            for (RoutineDef r : p.looseRoutines) {
                if (drawable(r)) {
                    return r.name.value();
                }
            }
            return null;
        }

        private static boolean drawable(RoutineDef r) {
            return r.type.equalsIgnoreCase("RLL") && !r.rungs.isEmpty();
        }

        /** Drawing mode: rungs with their number and place but not parsed. */
        List<Statement> placeholders(RoutineDef r) {
            List<Statement> body = new ArrayList<>(r.rungs.size());
            for (RungDef d : r.rungs) {
                Span at = d.text.present() ? whole.span(d.text.start(), d.text.end()) : elementSpan(d.offset);
                body.add(new Statement.Rung(d.number, d.type, null, null, List.of(), at));
            }
            return body;
        }

        /**
         * The routine's rungs as one text (rung texts joined with newlines through the source map), parsed once, then
         * each parsed rung matched back to its {@code <Rung>} by offset and lowered.
         */
        List<Statement> ladder(RoutineDef r) {
            List<RungDef> rungs = r.rungs;
            StringBuilder text = new StringBuilder();
            SourceMap.Builder map = SourceMap.builder();
            int[] starts = new int[rungs.size()];
            Comment[] rungComments = new Comment[rungs.size()];
            String fileText = file.text();
            for (int k = 0; k < rungs.size(); k++) {
                RungDef d = rungs.get(k);
                starts[k] = text.length();
                if (d.text.present()) {
                    int from = text.length();
                    decodeInto(fileText, d.text.start(), d.text.end(), text, map);
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
                    SourceText c = content(d.comment.start(), d.comment.end());
                    rungComments[k] =
                            new Comment(Comment.Kind.BLOCK, c.span(0, c.text().length()));
                    comments.add(rungComments[k]);
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
                    body.add(new Statement.Rung(d.number, d.type, rungComments[k], l, lowering.lower(l), l.span()));
                } else {
                    Span at = elementSpan(d.offset);
                    // no text: an empty rung; text that did not parse: a diagnostic was reported
                    List<Statement> lowered =
                            d.text.present() && !blank(d.text) ? List.of(new Statement.Invalid(at)) : List.of();
                    body.add(new Statement.Rung(d.number, d.type, rungComments[k], null, lowered, at));
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
            String s = decode(r.start(), r.end()).strip();
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
            return statements(r.lines);
        }

        /**
         * A tag operand held in an attribute ({@code Operand="Local:4:I.Ch1.Data"}), with spans in the file: plain
         * designators directly, anything else through the Rockwell ST condition parser.
         */
        Expression operandExpression(Attr a, int elementOffset) {
            if (!a.present() || !a.range().present()) {
                return new Expression.Invalid(elementSpan(elementOffset));
            }
            int s = a.range().start();
            int e = a.range().end();
            if (a.value().equals(file.text().substring(s, e))) {
                Expression fast = SimpleExpressions.simple(whole, s, e);
                if (fast != null) {
                    return fast;
                }
            }
            SourceText text = content(s, e);
            if (text.text().isBlank()) {
                return new Expression.Invalid(whole.span(s, e));
            }
            Parsed p = stParser.parseCondition(text, sink);
            count(p);
            RockwellStAstBuilder b = new RockwellStAstBuilder(text, p.tokens(), sink);
            Expression ex = p.tree() instanceof ConditionOnlyContext x ? b.conditionOnly(x) : null;
            return ex == null ? new Expression.Invalid(whole.span(s, e)) : ex;
        }

        /** ST lines as one text through the source map. */
        private SourceText lines(List<LineDef> lines) {
            StringBuilder text = new StringBuilder();
            SourceMap.Builder map = SourceMap.builder();
            String fileText = file.text();
            for (LineDef line : lines) {
                decodeInto(fileText, line.text().start(), line.text().end(), text, map);
                text.append('\n');
                map.replacement(line.text().end(), 0, 1);
            }
            return registry.registerFragment(file, text.toString(), map.build());
        }

        List<Statement> statements(List<LineDef> lines) {
            SourceText routineText = lines(lines);
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
                String decoded = decode(t.initialValueRange.start(), t.initialValueRange.end())
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

        /** The start tag of the element at {@code offset} (L5K: the rest of its line). */
        Span elementSpan(int offset) {
            int s = Math.max(0, Math.min(offset, file.text().length()));
            return whole.span(s, xml ? XmlInput.endOfStartTag(file.text(), s) : L5kReader.lineEnd(file.text(), s));
        }

        /** The text of {@code file[s, e)}: XML content decoded, L5K text as it is. */
        String decode(int s, int e) {
            return xml ? XmlText.decode(file.text(), s, e).text() : file.text().substring(s, e);
        }

        void decodeInto(String fileText, int s, int e, StringBuilder out, SourceMap.Builder map) {
            if (xml) {
                XmlText.decodeInto(fileText, s, e, out, map);
            } else {
                out.append(fileText, s, e);
                map.verbatim(s, e - s);
            }
        }

        /** {@code file[s, e)} as a text of its own, with spans in the file. */
        SourceText content(int s, int e) {
            if (xml) {
                return registry.registerXmlContent(file, s, e);
            }
            return registry.registerFragment(file, file.text().substring(s, e), SourceMap.verbatim(s, e - s));
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
