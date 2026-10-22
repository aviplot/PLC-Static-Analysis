package dev.plca.frontend.st;

import dev.plca.ast.Comment;
import dev.plca.ast.Declaration;
import dev.plca.ast.Declaration.Pou;
import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.ast.Pragma;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import dev.plca.core.Dialect;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.kit.ArchiveReader;
import dev.plca.frontend.st.kit.Frontend;
import dev.plca.frontend.st.kit.GraphContext;
import dev.plca.frontend.st.kit.TwoStageParser.Parsed;
import dev.plca.grammar.CodesysStParser.CompilationUnitContext;
import dev.plca.grammar.CodesysStParser.DeclarationPartContext;
import dev.plca.grammar.CodesysStParser.ExpressionOnlyContext;
import dev.plca.grammar.CodesysStParser.StatementsOnlyContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Entry point of the CODESYS/TwinCAT frontend: one file in, one {@link SourceUnit} out, problems reported to the sink.
 * Never throws for bad input (design rule 2).
 *
 * <p>Handles plain {@code .st} text (full library elements, or a bare statement list such as an exported action) and
 * TwinCAT XML ({@code .TcPOU/.TcDUT/.TcGVL/.TcIO}), where every {@code <Declaration>} and {@code <ST>} is parsed as its
 * own fragment so spans resolve to lines in the XML file.
 *
 * <p>Not thread-safe: use one instance per thread (it reuses lexer and parser objects).
 */
public final class StFrontend implements Frontend {

    /** TwinCAT implementation languages read from their {@code XmlArchive} ({@code docs/grammar/codesys.md} §9). */
    private static final Set<String> GRAPHICAL = Set.of("NWL", "CFC", "SFC");

    private static final Set<String> TWINCAT_EXTENSIONS = Set.of(".tcpou", ".tcdut", ".tcgvl", ".tcio", ".tctto");

    /** Every extension this frontend reads (lower case, with dot). */
    public static final Set<String> EXTENSIONS = Set.of(".st", ".tcpou", ".tcdut", ".tcgvl", ".tcio", ".tctto");

    private final StParserDriver driver;
    private int texts;
    private int fallbacks;
    private int cleanFallbacks;
    private int syntaxErrors;

    public StFrontend() {
        this(true);
    }

    /**
     * {@code twoStage = false} parses every text with full LL prediction (no SLL first pass). Results are identical; it
     * exists so benchmarks can measure the two-stage gain (design rule 1).
     */
    public StFrontend(boolean twoStage) {
        this.driver = new StParserDriver(twoStage);
    }

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    public static boolean isTwinCat(String path) {
        return TWINCAT_EXTENSIONS.contains(extension(path));
    }

    @Override
    public Result parse(SourceFile file, SourceRegistry registry, DiagnosticSink sink) {
        texts = 0;
        fallbacks = 0;
        cleanFallbacks = 0;
        syntaxErrors = 0;
        boolean twinCat = isTwinCat(file.path());
        Dialect dialect = twinCat ? Dialect.TWINCAT : Dialect.CODESYS;
        List<Declaration> declarations = new ArrayList<>();
        List<Comment> comments = new ArrayList<>();
        List<Pragma> pragmas = new ArrayList<>();
        try {
            if (extension(file.path()).equals(".tctto")) {
                declarations.addAll(TwinCatTaskReader.read(registry.register(file), sink));
            } else if (twinCat) {
                new TwinCatAssembler(file, registry, sink, comments, pragmas).run(declarations);
            } else {
                parseStText(file, registry, sink, declarations, comments, pragmas);
            }
        } catch (StackOverflowError e) {
            internal(file, registry, sink, "input is nested too deeply to analyse");
        } catch (RuntimeException e) {
            internal(file, registry, sink, "internal error while parsing: " + e);
        }
        SourceUnit unit = new SourceUnit(file.path(), dialect, declarations, comments, pragmas);
        return new Result(unit, new Stats(texts, fallbacks, cleanFallbacks, syntaxErrors));
    }

    private static void internal(SourceFile file, SourceRegistry registry, DiagnosticSink sink, String message) {
        SourceText whole = registry.register(file);
        sink.report(Diagnostic.of(SyntaxCodes.INTERNAL, Severity.ERROR, message, Span.at(whole.id(), 0)));
    }

    // ---- plain text ----------------------------------------------------------------------------

    private void parseStText(
            SourceFile file,
            SourceRegistry registry,
            DiagnosticSink sink,
            List<Declaration> out,
            List<Comment> comments,
            List<Pragma> pragmas) {
        SourceText text = registry.register(file);
        Parsed p = parse(text, StParserDriver.Entry.AUTO, sink);
        AstBuilder b = new AstBuilder(text, p.tokens(), sink);
        if (p.tree() instanceof CompilationUnitContext cu) {
            out.addAll(b.compilationUnit(cu));
        } else {
            // a bare statement list (exported action body, snippet): a nameless body named after the file
            List<Statement> body = b.statementsOnly((StatementsOnlyContext) p.tree());
            Span all = text.span(0, text.text().length());
            out.add(new Pou(
                    PouKind.ACTION,
                    new Identifier(baseName(file.path()), Span.at(text.id(), 0)),
                    Set.of(),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    body,
                    "ST",
                    List.of(),
                    all));
        }
        b.collectHidden(comments, pragmas);
    }

    private Parsed parse(SourceText text, StParserDriver.Entry entry, DiagnosticSink sink) {
        Parsed p = driver.parse(text, entry, sink);
        texts++;
        if (p.fallback()) {
            fallbacks++;
            if (p.syntaxErrors() == 0) {
                cleanFallbacks++;
            }
        }
        syntaxErrors += p.syntaxErrors();
        return p;
    }

    // ---- TwinCAT XML ---------------------------------------------------------------------------

    /** Turns the element tree of one TwinCAT file into declarations. */
    private final class TwinCatAssembler {
        private final SourceFile file;
        private final SourceRegistry registry;
        private final DiagnosticSink sink;
        private final List<Comment> comments;
        private final List<Pragma> pragmas;
        private final SourceText whole;
        private GraphContext graphs;
        private ArchiveReader archives;
        /** The POU being assembled and its declaration (SFC step actions and conditions refer to them). */
        private TwinCatReader.Element owner;

        private AstBuilder.DeclarationPart ownerDecl;

        TwinCatAssembler(
                SourceFile file,
                SourceRegistry registry,
                DiagnosticSink sink,
                List<Comment> comments,
                List<Pragma> pragmas) {
            this.file = file;
            this.registry = registry;
            this.sink = sink;
            this.comments = comments;
            this.pragmas = pragmas;
            this.whole = registry.register(file);
        }

        void run(List<Declaration> out) {
            TwinCatReader.Result r = TwinCatReader.read(file.text());
            if (r.error() != null) {
                sink.report(Diagnostic.error(
                        SyntaxCodes.MALFORMED_XML,
                        "malformed XML: " + r.error(),
                        Span.at(whole.id(), r.errorOffset())));
            }
            for (TwinCatReader.Element e : r.objects()) {
                switch (e.tag) {
                    case "POU" -> out.add(pou(e, null));
                    case "Itf" -> out.add(pou(e, PouKind.INTERFACE));
                    case "DUT", "GVL" -> declarationsOnly(e, out);
                    default -> {}
                }
            }
        }

        private Pou pou(TwinCatReader.Element e, PouKind forcedKind) {
            AstBuilder.DeclarationPart decl = declaration(e);
            owner = e;
            ownerDecl = decl;
            AstBuilder.Header h = decl == null ? null : decl.header();
            PouKind kind = forcedKind != null ? forcedKind : h != null ? h.kind() : defaultKind(e.tag);
            boolean prototype = forcedKind == PouKind.INTERFACE || kind == PouKind.INTERFACE;
            List<Pou> members = new ArrayList<>();
            for (TwinCatReader.Element c : e.children) {
                if (c.tag.equals("Get") || c.tag.equals("Set")) {
                    continue; // handled by their property
                }
                members.add(member(c, prototype));
            }
            List<Statement> body = prototype ? null : body(e);
            Span span = decl != null ? decl.span() : elementSpan(e);
            return new Pou(
                    kind,
                    h != null ? h.name() : attributeName(e),
                    h != null ? h.modifiers() : Set.of(),
                    h != null ? h.type() : null,
                    h != null ? h.extendsTypes() : List.of(),
                    h != null ? h.implementsTypes() : List.of(),
                    blocks(decl, e),
                    members,
                    body,
                    prototype ? null : language(e),
                    h != null ? h.pragmas() : List.of(),
                    span);
        }

        private Pou member(TwinCatReader.Element e, boolean prototype) {
            if (e.tag.equals("Property")) {
                AstBuilder.DeclarationPart decl = declaration(e);
                AstBuilder.Header h = decl == null ? null : decl.header();
                List<Pou> accessors = new ArrayList<>();
                for (TwinCatReader.Element c : e.children) {
                    if (c.tag.equals("Get") || c.tag.equals("Set")) {
                        accessors.add(simpleMember(
                                c, c.tag.equals("Get") ? PouKind.PROPERTY_GET : PouKind.PROPERTY_SET, prototype));
                    }
                }
                return new Pou(
                        PouKind.PROPERTY,
                        h != null ? h.name() : attributeName(e),
                        h != null ? h.modifiers() : Set.of(),
                        h != null ? h.type() : null,
                        List.of(),
                        List.of(),
                        decl != null ? decl.varBlocks() : List.of(),
                        accessors,
                        null,
                        null,
                        h != null ? h.pragmas() : List.of(),
                        decl != null ? decl.span() : elementSpan(e));
            }
            PouKind kind = switch (e.tag) {
                case "Action" -> PouKind.ACTION;
                case "Transition" -> PouKind.TRANSITION;
                default -> PouKind.METHOD;
            };
            return simpleMember(e, kind, prototype);
        }

        private Pou simpleMember(TwinCatReader.Element e, PouKind kind, boolean prototype) {
            AstBuilder.DeclarationPart decl = declaration(e);
            AstBuilder.Header h = decl == null ? null : decl.header();
            List<Statement> body = prototype ? null : body(e);
            List<VarBlock> blocks = blocks(decl, e);
            return new Pou(
                    kind,
                    h != null ? h.name() : attributeName(e),
                    decl != null ? decl.effectiveModifiers() : Set.of(),
                    h != null ? h.type() : null,
                    List.of(),
                    List.of(),
                    blocks,
                    List.of(),
                    body,
                    prototype ? null : language(e),
                    h != null ? h.pragmas() : List.of(),
                    decl != null ? decl.span() : elementSpan(e));
        }

        private void declarationsOnly(TwinCatReader.Element e, List<Declaration> out) {
            AstBuilder.DeclarationPart decl = declaration(e);
            if (decl == null) {
                return;
            }
            out.addAll(decl.types());
            for (VarBlock b : decl.varBlocks()) {
                Identifier listName = e.tag.equals("GVL") ? attributeName(e) : null;
                out.add(new Declaration.GlobalVars(listName, b, b.span()));
            }
        }

        private AstBuilder.DeclarationPart declaration(TwinCatReader.Element e) {
            if (!e.hasDeclaration()) {
                return null;
            }
            SourceText t = registry.registerXmlContent(file, e.declStart, e.declEnd);
            Parsed p = parse(t, StParserDriver.Entry.DECLARATION_PART, sink);
            AstBuilder b = new AstBuilder(t, p.tokens(), sink);
            AstBuilder.DeclarationPart d = b.declarationPart((DeclarationPartContext) p.tree());
            b.collectHidden(comments, pragmas);
            return d;
        }

        private List<Statement> body(TwinCatReader.Element e) {
            if (!e.hasStImplementation() && e.graphStart >= 0 && GRAPHICAL.contains(e.implLanguage)) {
                List<Statement> graphical = graphical(e);
                if (graphical != null) {
                    return graphical;
                }
            }
            if (!e.hasStImplementation()) {
                if (e.implLanguage != null) {
                    sink.report(Diagnostic.of(
                            SyntaxCodes.UNPARSED_LANGUAGE,
                            Severity.INFO,
                            e.tag + " '" + e.name + "' is implemented in " + e.implLanguage
                                    + ", which is not analysed yet",
                            elementSpan(e)));
                }
                return null;
            }
            SourceText t = registry.registerXmlContent(file, e.implStart, e.implEnd);
            if (e.tag.equals("Transition") && isBareCondition(t.text())) {
                // a transition written as its condition: `NOT fb.BUSY` means `Trans := NOT fb.BUSY;`
                Expression condition = expression(t);
                Span s = condition.span();
                Expression target = new Expression.NameRef(
                        new Identifier(e.name == null ? "<unnamed>" : e.name, Identifier.Form.SYNTHETIC, s), false, s);
                return List.of(new Statement.Assignment(target, Statement.AssignOp.ASSIGN, condition, s));
            }
            Parsed p = parse(t, StParserDriver.Entry.STATEMENTS, sink);
            AstBuilder b = new AstBuilder(t, p.tokens(), sink);
            List<Statement> body = b.statementsOnly((StatementsOnlyContext) p.tree());
            b.collectHidden(comments, pragmas);
            return body;
        }

        /** No statement in the text: no {@code ;} and no {@code :=} outside comments and strings. */
        private static boolean isBareCondition(String text) {
            String code = text.replaceAll("(?s)\\(\\*.*?\\*\\)|//[^\\n]*|'[^']*'|\"[^\"]*\"", " ");
            return !code.isBlank() && !code.contains(";") && !code.contains(":=");
        }

        /** A graphical body read from its {@code XmlArchive}; null when the archive is unreadable (reported). */
        private List<Statement> graphical(TwinCatReader.Element e) {
            if (graphs == null) {
                archives = new ArchiveReader(file.text());
                graphs = new GraphContext(file, registry, whole, sink, this::expression);
            }
            ArchiveReader.Result r = archives.read(e.graphStart, e.graphEnd);
            if (r.root() == null) {
                if (r.error() == null) {
                    return null;
                }
                sink.report(Diagnostic.error(
                        SyntaxCodes.MALFORMED_XML,
                        e.tag + " '" + e.name + "': unreadable " + e.implLanguage + " body: " + r.error(),
                        Span.at(whole.id(), r.errorOffset())));
                return null;
            }
            switch (e.implLanguage) {
                case "NWL" -> {
                    e.language = archives.string(r.root(), "DefaultViewMode").equalsIgnoreCase("Ld") ? "LD" : "FBD";
                    return new NwlTranslator(graphs, archives).translate(r.root());
                }
                case "CFC" -> {
                    e.language = "CFC";
                    return new CfcTranslator(graphs, archives).translate(r.root());
                }
                case "SFC" -> {
                    e.language = "SFC";
                    SfcArchiveTranslator.Result sfc = new SfcArchiveTranslator(
                                    graphs, archives, memberNames("Action"), memberNames("Transition"), variables())
                            .translate(r.root());
                    e.extraBlock = sfc.stepVariables();
                    return sfc.body();
                }
                default -> {
                    return null;
                }
            }
        }

        /** The declaration's VAR blocks plus the implicit ones of a graphical body (SFC step variables). */
        private List<VarBlock> blocks(AstBuilder.DeclarationPart decl, TwinCatReader.Element e) {
            List<VarBlock> own = decl != null ? decl.varBlocks() : List.of();
            if (e.extraBlock == null) {
                return own;
            }
            List<VarBlock> all = new ArrayList<>(own);
            all.add(e.extraBlock);
            return all;
        }

        private Set<String> memberNames(String tag) {
            Set<String> out = new HashSet<>();
            if (owner != null) {
                for (TwinCatReader.Element c : owner.children) {
                    if (c.tag.equals(tag) && c.name != null) {
                        out.add(c.name.toLowerCase(Locale.ROOT));
                    }
                }
            }
            return out;
        }

        private Set<String> variables() {
            Set<String> out = new HashSet<>();
            if (ownerDecl != null) {
                for (VarBlock b : ownerDecl.varBlocks()) {
                    for (VarDecl v : b.variables()) {
                        out.add(v.name().name().toLowerCase(Locale.ROOT));
                    }
                }
            }
            return out;
        }

        private Expression expression(SourceText t) {
            Parsed p = parse(t, StParserDriver.Entry.EXPRESSION, sink);
            AstBuilder b = new AstBuilder(t, p.tokens(), sink);
            Expression ex = b.expressionOnly((ExpressionOnlyContext) p.tree());
            b.collectHidden(comments, pragmas);
            return ex;
        }

        private String language(TwinCatReader.Element e) {
            return e.hasStImplementation() ? "ST" : e.language != null ? e.language : e.implLanguage;
        }

        private Identifier attributeName(TwinCatReader.Element e) {
            return new Identifier(e.name == null ? "<unnamed>" : e.name, elementSpan(e));
        }

        private Span elementSpan(TwinCatReader.Element e) {
            return whole.span(e.offset, TwinCatReader.endOfStartTag(file.text(), e.offset));
        }
    }

    private static PouKind defaultKind(String tag) {
        return tag.equals("Itf") ? PouKind.INTERFACE : PouKind.FUNCTION_BLOCK;
    }

    static String extension(String path) {
        return Frontend.extension(path);
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return path.substring(slash + 1, dot > slash ? dot : path.length());
    }
}
