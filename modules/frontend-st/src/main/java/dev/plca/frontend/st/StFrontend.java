package dev.plca.frontend.st;

import dev.plca.ast.Comment;
import dev.plca.ast.Declaration;
import dev.plca.ast.Declaration.Pou;
import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Identifier;
import dev.plca.ast.Pragma;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import dev.plca.ast.VarBlock;
import dev.plca.core.Dialect;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.grammar.CodesysStParser.CompilationUnitContext;
import dev.plca.grammar.CodesysStParser.DeclarationPartContext;
import dev.plca.grammar.CodesysStParser.StatementsOnlyContext;
import java.util.ArrayList;
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
public final class StFrontend {

    /** Parse statistics of one file, for the corpus check and benchmarks. */
    public record Stats(int texts, int sllFallbacks, int cleanFallbacks, int syntaxErrors) {
        public Stats plus(Stats o) {
            return new Stats(
                    texts + o.texts,
                    sllFallbacks + o.sllFallbacks,
                    cleanFallbacks + o.cleanFallbacks,
                    syntaxErrors + o.syntaxErrors);
        }
    }

    public record Result(SourceUnit unit, Stats stats) {}

    private static final Set<String> TWINCAT_EXTENSIONS = Set.of(".tcpou", ".tcdut", ".tcgvl", ".tcio");

    private final StParserDriver driver = new StParserDriver();
    private int texts;
    private int fallbacks;
    private int cleanFallbacks;
    private int syntaxErrors;

    public static boolean isTwinCat(String path) {
        return TWINCAT_EXTENSIONS.contains(extension(path));
    }

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
            if (twinCat) {
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
        StParserDriver.Parsed p = parse(text, StParserDriver.Entry.AUTO, sink);
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

    private StParserDriver.Parsed parse(SourceText text, StParserDriver.Entry entry, DiagnosticSink sink) {
        StParserDriver.Parsed p = driver.parse(text, entry, sink);
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
                    decl != null ? decl.varBlocks() : List.of(),
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
            return new Pou(
                    kind,
                    h != null ? h.name() : attributeName(e),
                    decl != null ? decl.effectiveModifiers() : Set.of(),
                    h != null ? h.type() : null,
                    List.of(),
                    List.of(),
                    decl != null ? decl.varBlocks() : List.of(),
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
            StParserDriver.Parsed p = parse(t, StParserDriver.Entry.DECLARATION_PART, sink);
            AstBuilder b = new AstBuilder(t, p.tokens(), sink);
            AstBuilder.DeclarationPart d = b.declarationPart((DeclarationPartContext) p.tree());
            b.collectHidden(comments, pragmas);
            return d;
        }

        private List<Statement> body(TwinCatReader.Element e) {
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
            StParserDriver.Parsed p = parse(t, StParserDriver.Entry.STATEMENTS, sink);
            AstBuilder b = new AstBuilder(t, p.tokens(), sink);
            List<Statement> body = b.statementsOnly((StatementsOnlyContext) p.tree());
            b.collectHidden(comments, pragmas);
            return body;
        }

        private String language(TwinCatReader.Element e) {
            return e.hasStImplementation() ? "ST" : e.implLanguage;
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

    private static String extension(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return path.substring(slash + 1, dot > slash ? dot : path.length());
    }
}
