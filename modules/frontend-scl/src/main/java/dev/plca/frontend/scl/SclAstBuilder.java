package dev.plca.frontend.scl;

import dev.plca.ast.Declaration;
import dev.plca.ast.Declaration.BlockInfo;
import dev.plca.ast.Declaration.Pou;
import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Expression;
import dev.plca.ast.Expression.Argument;
import dev.plca.ast.Expression.ArgumentKind;
import dev.plca.ast.Identifier;
import dev.plca.ast.Initializer;
import dev.plca.ast.Pragma;
import dev.plca.ast.QualifiedName;
import dev.plca.ast.Statement;
import dev.plca.ast.Statement.AssignOp;
import dev.plca.ast.TypeRef;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.SyntaxCodes;
import dev.plca.frontend.st.kit.BuilderSupport;
import dev.plca.frontend.st.kit.CoreTokens;
import dev.plca.grammar.SiemensSclLexer;
import dev.plca.grammar.SiemensSclParser.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Builds the {@code plc-ast} tree from a Siemens SCL parse tree (design rule 3). The grammar-independent parts live in
 * {@link BuilderSupport}; this class is the glue for the SCL contexts. Statement and expression glue mirrors the
 * CODESYS builder rule for rule — {@code SclConformanceTest} checks that both give the same AST for core ST.
 *
 * <p>Error recovery leaves partial trees: missing children are null. Every method tolerates that.
 */
final class SclAstBuilder extends BuilderSupport {

    static final CoreTokens TOKENS = new CoreTokens(SiemensSclLexer.VOCABULARY, SiemensSclLexer.channelNames);

    /** Classic absolute DB access {@code DB10.DBW2}, {@code DB10.DX0.1}: block part and element part. */
    private static final Pattern CLASSIC_DB = Pattern.compile("(?i)DB\\d+");

    private static final Pattern CLASSIC_DB_ELEMENT = Pattern.compile("(?i)DB?[XBWD]\\d+");

    private static final String LANGUAGE = "SCL";

    SclAstBuilder(SourceText text, CommonTokenStream tokens, DiagnosticSink sink) {
        super(text, tokens, sink, TOKENS);
    }

    // ============================================================================================
    // Entry points
    // ============================================================================================

    List<Declaration> compilationUnit(CompilationUnitContext ctx) {
        List<Declaration> out = new ArrayList<>();
        Token strayStart = null;
        Token strayEnd = null;
        for (ParseTree c : ctx.children == null ? List.<ParseTree>of() : ctx.children) {
            if (c instanceof StrayTokenContext s) {
                strayStart = strayStart == null ? s.getStart() : strayStart;
                strayEnd = s.getStop();
                continue;
            }
            reportStray(strayStart, strayEnd);
            strayStart = null;
            if (c instanceof LibraryElementContext e) {
                libraryElement(e, out);
            }
        }
        reportStray(strayStart, strayEnd);
        return out;
    }

    /** One diagnostic per run of tokens outside any block. */
    private void reportStray(Token first, Token last) {
        if (first == null) {
            return;
        }
        sink.report(Diagnostic.error(
                SclCodes.OUTSIDE_BLOCK,
                "text outside any block is ignored",
                text.span(tokenSpan(first).start(), tokenSpan(last).end())));
    }

    List<Statement> statementsOnly(StatementsOnlyContext ctx) {
        return statementList(ctx.statementList());
    }

    private void libraryElement(LibraryElementContext ctx, List<Declaration> out) {
        if (ctx.organizationBlock() != null) {
            OrganizationBlockContext b = ctx.organizationBlock();
            out.add(codeBlock(
                    PouKind.ORGANIZATION_BLOCK,
                    b,
                    b.identifier(),
                    null,
                    b.blockAttribute(),
                    b.declarationSection(),
                    b.BEGIN(),
                    b.pouBody()));
        } else if (ctx.sclFunctionBlock() != null) {
            SclFunctionBlockContext b = ctx.sclFunctionBlock();
            out.add(codeBlock(
                    PouKind.FUNCTION_BLOCK,
                    b,
                    b.identifier(),
                    null,
                    b.blockAttribute(),
                    b.declarationSection(),
                    b.BEGIN(),
                    b.pouBody()));
        } else if (ctx.sclFunction() != null) {
            SclFunctionContext b = ctx.sclFunction();
            out.add(codeBlock(
                    PouKind.FUNCTION,
                    b,
                    b.identifier(),
                    b.typeSpec(),
                    b.blockAttribute(),
                    b.declarationSection(),
                    b.BEGIN(),
                    b.pouBody()));
        } else if (ctx.dataBlock() != null) {
            out.add(dataBlock(ctx.dataBlock()));
        } else if (ctx.dataTypeDeclaration() != null) {
            out.addAll(dataTypeDeclaration(ctx.dataTypeDeclaration()));
        } else if (ctx.namespaceDeclaration() != null) {
            NamespaceDeclarationContext n = ctx.namespaceDeclaration();
            List<Declaration> members = new ArrayList<>();
            for (LibraryElementContext e : n.libraryElement()) {
                libraryElement(e, members);
            }
            out.add(new Declaration.Namespace(qualifiedName(n.qualifiedName()), members, span(n)));
        }
    }

    // ============================================================================================
    // Blocks
    // ============================================================================================

    private Pou codeBlock(
            PouKind kind,
            ParserRuleContext ctx,
            IdentifierContext name,
            TypeSpecContext returnType,
            List<BlockAttributeContext> attributes,
            List<DeclarationSectionContext> sections,
            TerminalNode begin,
            PouBodyContext body) {
        List<Statement> statements = new ArrayList<>();
        if (body != null && body.children != null) {
            for (ParseTree c : body.children) {
                if (c instanceof StatementContext s) {
                    statement(s, statements);
                } else if (c instanceof LabelDefinitionContext l) {
                    statements.add(new Statement.Label(identifier(l.identifier()), span(l)));
                }
            }
        }
        if (begin == null && !statements.isEmpty()) {
            sink.report(Diagnostic.warning(
                    SclCodes.MISSING_BEGIN,
                    "missing BEGIN between the declarations and the statements of '"
                            + identifier(name).name() + "'",
                    statements.getFirst().span()));
        }
        return new Pou(
                kind,
                identifier(name),
                Set.of(),
                returnType == null ? null : typeSpec(returnType, span(returnType)),
                List.of(),
                List.of(),
                declarationSections(sections),
                List.of(),
                statements,
                LANGUAGE,
                headerPragmas(ctx, headerEnd(name, returnType, attributes)),
                blockInfo(attributes, false),
                span(ctx));
    }

    private Declaration.DataBlock dataBlock(DataBlockContext ctx) {
        DataBlockLayoutContext layout = ctx.dataBlockLayout();
        TypeRef struct = null;
        List<VarBlock> varBlocks = List.of();
        QualifiedName basedOn = null;
        if (layout != null) {
            if (layout.structType() != null) {
                struct = new TypeRef.StructType(members(layout.structType().varDeclaration()), span(layout));
            } else if (layout.qualifiedName() != null) {
                basedOn = qualifiedName(layout.qualifiedName());
            } else {
                varBlocks = declarationSections(layout.declarationSection());
            }
        }
        List<Statement> init = new ArrayList<>();
        for (Statement s : statementList(ctx.statementList())) {
            if (s instanceof Statement.Assignment) {
                init.add(s);
            } else if (!(s instanceof Statement.Empty) && !(s instanceof Statement.Invalid)) {
                sink.report(Diagnostic.warning(
                        SclCodes.DB_INIT_NOT_ASSIGNMENT,
                        "only assignments of initial values are allowed after BEGIN in a data block",
                        s.span()));
            }
        }
        return new Declaration.DataBlock(
                identifier(ctx.identifier()),
                struct,
                varBlocks,
                basedOn,
                init,
                headerPragmas(ctx, headerEnd(ctx.identifier(), null, ctx.blockAttribute())),
                blockInfo(ctx.blockAttribute(), true),
                span(ctx));
    }

    private List<Declaration.TypeDecl> dataTypeDeclaration(DataTypeDeclarationContext ctx) {
        List<Declaration.TypeDecl> out = new ArrayList<>();
        boolean first = true;
        for (TypeDefinitionContext d : ctx.typeDefinition()) {
            List<Pragma> pragmas = new ArrayList<>(first ? pragmasBefore(ctx.getStart()) : List.of());
            pragmas.addAll(headerPragmas(d, headerEnd(d.identifier(), null, d.blockAttribute())));
            first = false;
            Span span = span(d);
            out.add(new Declaration.TypeDecl(
                    identifier(d.identifier()),
                    null,
                    typeSpec(d.typeSpec(), span),
                    d.initializer() == null ? null : initializer(d.initializer()),
                    pragmas,
                    blockInfo(d.blockAttribute(), false),
                    span));
        }
        return out;
    }

    // ---- block header ----------------------------------------------------------------------------

    /** The last token of a block header: its last attribute, else the result type, else the name. */
    private static Token headerEnd(IdentifierContext name, TypeSpecContext type, List<BlockAttributeContext> attrs) {
        if (!attrs.isEmpty()) {
            return attrs.getLast().getStop();
        }
        if (type != null) {
            return type.getStop();
        }
        return name == null ? null : name.getStop();
    }

    /**
     * Pragmas before the block keyword, plus the attribute pragmas inside the header ({@code { S7_Optimized_Access :=
     * 'TRUE' }} after the name or between header attributes) and directly after it.
     */
    private List<Pragma> headerPragmas(ParserRuleContext block, Token headerEnd) {
        List<Pragma> out = new ArrayList<>(pragmasBefore(block.getStart()));
        if (headerEnd == null || headerEnd.getTokenIndex() < 0) {
            return out;
        }
        int from = block.getStart().getTokenIndex() + 1;
        for (int i = from; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (i > headerEnd.getTokenIndex() && t.getChannel() == Token.DEFAULT_CHANNEL) {
                break;
            }
            if (t.getChannel() == tok.pragmasChannel) {
                out.addAll(pragmasOf(t));
            }
        }
        return out;
    }

    private BlockInfo blockInfo(List<BlockAttributeContext> attributes, boolean dataBlock) {
        if (attributes.isEmpty()) {
            return null;
        }
        String title = null;
        String author = null;
        String family = null;
        String name = null;
        String version = null;
        boolean knowHow = false;
        boolean unlinked = false;
        BlockInfo.Retention retention = BlockInfo.Retention.UNSPECIFIED;
        Set<String> seen = new java.util.HashSet<>();
        for (BlockAttributeContext a : attributes) {
            String key = a.getStart().getType() == SiemensSclLexer.TITLE_LINE
                    ? "TITLE"
                    : a.getStart().getText().toUpperCase(Locale.ROOT);
            if (key.equals("NON_RETAIN")) {
                key = "RETAIN";
            }
            if (!seen.add(key)) {
                sink.report(Diagnostic.warning(
                        SclCodes.DUPLICATE_ATTRIBUTE,
                        "block attribute " + key + " is given more than once; the first one is used",
                        span(a)));
                continue;
            }
            switch (a) {
                case TitleAttributeContext t -> title = titleText(t.TITLE_LINE().getText());
                case KeyValueAttributeContext kv -> {
                    String value = attributeValue(kv.value);
                    switch (kv.key.getType()) {
                        case SiemensSclLexer.AUTHOR -> author = value;
                        case SiemensSclLexer.FAMILY -> family = value;
                        case SiemensSclLexer.NAME -> name = value;
                        default -> version = value;
                    }
                }
                case KnowHowAttributeContext k -> knowHow = true;
                case UnlinkedAttributeContext u -> unlinked = true;
                case RetainAttributeContext r -> {
                    if (!dataBlock) {
                        sink.report(Diagnostic.warning(
                                SclCodes.MISPLACED_ATTRIBUTE,
                                r.getText().toUpperCase(Locale.ROOT) + " belongs to data blocks only",
                                span(r)));
                    }
                    retention = r.RETAIN() != null ? BlockInfo.Retention.RETAIN : BlockInfo.Retention.NON_RETAIN;
                }
                default -> {}
            }
        }
        Span span = text.span(
                span(attributes.getFirst()).start(), span(attributes.getLast()).end());
        return new BlockInfo(title, author, family, name, version, knowHow, unlinked, retention, span);
    }

    /** {@code TITLE = Main Program Sweep (Cycle)} or with the text in quotes: the text. */
    private static String titleText(String line) {
        String s = line.substring(line.indexOf('=') + 1).strip();
        return unquote(s);
    }

    private String attributeValue(AttributeValueContext v) {
        if (v == null) {
            return null;
        }
        return unquote(v.getText());
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("'") && s.endsWith("'") || s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ============================================================================================
    // Declarations
    // ============================================================================================

    private List<VarBlock> declarationSections(List<DeclarationSectionContext> sections) {
        List<VarBlock> out = new ArrayList<>();
        for (DeclarationSectionContext d : sections) {
            if (d.varSection() != null) {
                out.add(varSection(d.varSection()));
            } else if (d.constSection() != null) {
                out.add(constSection(d.constSection()));
            }
            // LABEL sections only pre-declare jump labels (classic); labels are defined by their use in the body
        }
        return out;
    }

    private VarBlock varSection(VarSectionContext ctx) {
        Set<VarBlock.Qualifier> qualifiers = EnumSet.noneOf(VarBlock.Qualifier.class);
        for (VarQualifierContext q : ctx.varQualifier()) {
            qualifiers.add(VarBlock.Qualifier.valueOf(q.getStart().getText().toUpperCase(Locale.ROOT)));
        }
        return new VarBlock(
                section(ctx.varSectionKeyword()),
                qualifiers,
                members(ctx.varDeclaration()),
                pragmasBefore(ctx.getStart()),
                span(ctx));
    }

    /** Classic {@code CONST n := 10; END_CONST}: a constant section whose types follow from the values. */
    private VarBlock constSection(ConstSectionContext ctx) {
        List<VarDecl> vars = new ArrayList<>();
        for (ConstDeclarationContext c : ctx.constDeclaration()) {
            Span span = span(c);
            vars.add(new VarDecl(
                    identifier(c.identifier()),
                    null,
                    null,
                    null,
                    expression(c.expression(), span),
                    false,
                    pragmasBefore(c.getStart()),
                    span));
        }
        return new VarBlock(
                VarBlock.Section.VAR,
                EnumSet.of(VarBlock.Qualifier.CONSTANT),
                vars,
                pragmasBefore(ctx.getStart()),
                span(ctx));
    }

    private static VarBlock.Section section(VarSectionKeywordContext ctx) {
        return switch (ctx.getStart().getType()) {
            case SiemensSclLexer.VAR_INPUT -> VarBlock.Section.INPUT;
            case SiemensSclLexer.VAR_OUTPUT -> VarBlock.Section.OUTPUT;
            case SiemensSclLexer.VAR_IN_OUT -> VarBlock.Section.IN_OUT;
            case SiemensSclLexer.VAR_TEMP -> VarBlock.Section.TEMP;
            case SiemensSclLexer.VAR_STAT -> VarBlock.Section.STAT;
            case SiemensSclLexer.VAR_GLOBAL -> VarBlock.Section.GLOBAL;
            case SiemensSclLexer.VAR_EXTERNAL -> VarBlock.Section.EXTERNAL;
            default -> VarBlock.Section.VAR;
        };
    }

    private List<VarDecl> members(List<VarDeclarationContext> decls) {
        List<VarDecl> out = new ArrayList<>();
        for (VarDeclarationContext d : decls) {
            varDeclaration(d, out);
        }
        return out;
    }

    /** {@code a, b {Attr := 'x'} [AT %… | AT other] : T [:= init];} */
    private void varDeclaration(VarDeclarationContext ctx, List<VarDecl> out) {
        Span span = span(ctx);
        List<Pragma> pragmas = new ArrayList<>(pragmasBefore(ctx.getStart()));
        if (ctx.COLON() != null) {
            // TIA writes declaration attributes between the name and the colon
            pragmas.addAll(pragmasBefore(ctx.COLON().getSymbol()));
        }
        TypeRef type = typeSpec(ctx.typeSpec(), span);
        String address = null;
        Identifier overlay = null;
        LocationContext loc = ctx.location();
        if (loc != null) {
            if (loc.DIRECT_ADDRESS() != null) {
                address = loc.DIRECT_ADDRESS().getText();
            } else if (loc.identifier() != null) {
                overlay = identifier(loc.identifier());
            }
        }
        Initializer init = null;
        VariableInitContext vi = ctx.variableInit();
        if (vi != null) {
            if (vi.bareArrayInit() != null) {
                init = bareArrayInit(vi.bareArrayInit());
            } else {
                init = vi.initializer() == null ? new Expression.Invalid(span(vi)) : initializer(vi.initializer());
            }
        }
        for (VariablePathContext p : ctx.variablePath()) {
            QualifiedName path = variablePath(p);
            if (path == null) {
                continue;
            }
            boolean dotted = path.parts().size() > 1;
            out.add(new VarDecl(path.last(), dotted ? path : null, address, type, init, false, pragmas, overlay, span));
        }
    }

    private QualifiedName variablePath(VariablePathContext ctx) {
        if (ctx == null || ctx.declaredName().isEmpty()) {
            return null;
        }
        List<Identifier> parts = new ArrayList<>();
        for (DeclaredNameContext n : ctx.declaredName()) {
            parts.add(n.identifier() != null ? identifier(n.identifier()) : new Identifier(n.getText(), span(n)));
        }
        return new QualifiedName(parts, span(ctx));
    }

    /** {@code := 1, 2, 3} / {@code := 32(0)} without brackets: an array initialiser. */
    private Initializer bareArrayInit(BareArrayInitContext ctx) {
        List<Initializer.Element> elements = new ArrayList<>();
        if (ctx.arrayInitElement().isEmpty()) {
            int repeat = parseIntOr(ctx.INTEGER().getText(), 1);
            elements.add(new Initializer.Element(
                    repeat, ctx.initializer() == null ? null : initializer(ctx.initializer()), span(ctx)));
        }
        for (ArrayInitElementContext e : ctx.arrayInitElement()) {
            elements.add(arrayElement(e));
        }
        return new Initializer.ArrayInit(elements, span(ctx));
    }

    private Initializer.Element arrayElement(ArrayInitElementContext e) {
        if (e.INTEGER() != null) {
            int repeat = parseIntOr(e.INTEGER().getText(), 1);
            return new Initializer.Element(
                    repeat, e.initializer() == null ? null : initializer(e.initializer()), span(e));
        }
        if (e.initializer() == null) {
            // `()`: the element keeps its default
            return new Initializer.Element(1, new Initializer.StructInit(List.of(), span(e)), span(e));
        }
        return new Initializer.Element(1, initializer(e.initializer()), span(e));
    }

    private Initializer initializer(InitializerContext ctx) {
        if (ctx.arrayInitializer() != null) {
            return arrayInit(ctx.arrayInitializer());
        }
        if (ctx.structInitializer() != null) {
            return structInit(ctx.structInitializer());
        }
        return expression(ctx.expression(), span(ctx));
    }

    private Initializer arrayInit(ArrayInitializerContext a) {
        List<Initializer.Element> elements = new ArrayList<>();
        for (ArrayInitElementContext e : a.arrayInitElement()) {
            elements.add(arrayElement(e));
        }
        return new Initializer.ArrayInit(elements, span(a));
    }

    /** Named {@code (a := 1)}, or positional {@code (1, (), [2])} where {@code ()} keeps the member's default. */
    private Initializer structInit(StructInitializerContext s) {
        List<Initializer.FieldInit> fields = new ArrayList<>();
        for (StructInitElementContext f : s.structInitElement()) {
            fields.add(new Initializer.FieldInit(
                    identifier(f.identifier()), initializerOrInvalid(f.initializer(), span(f)), span(f)));
        }
        SinglePositionalContext single = s.singlePositional();
        if (single != null) {
            Initializer value = single.arrayInitializer() != null
                    ? arrayInit(single.arrayInitializer())
                    : single.structInitializer() != null
                            ? structInit(single.structInitializer())
                            : new Initializer.StructInit(List.of(), span(single));
            fields.add(new Initializer.FieldInit(null, value, span(single)));
        }
        for (PositionalInitContext p : s.positionalInit()) {
            Initializer value = p.initializer() == null
                    ? new Initializer.StructInit(List.of(), span(p))
                    : initializer(p.initializer());
            fields.add(new Initializer.FieldInit(null, value, span(p)));
        }
        return new Initializer.StructInit(fields, span(s));
    }

    private Initializer initializerOrInvalid(InitializerContext ctx, Span fallback) {
        return ctx == null ? new Expression.Invalid(fallback) : initializer(ctx);
    }

    private TypeRef typeSpec(TypeSpecContext ctx, Span fallback) {
        if (ctx == null || ctx.getChildCount() == 0) {
            return new TypeRef.Invalid(ctx == null ? fallback : span(ctx));
        }
        ParseTree c = ctx.getChild(0);
        Span span = span(ctx);
        return switch (c) {
            case NamedTypeContext n -> namedType(n);
            case ArrayTypeContext a -> {
                List<TypeRef.Dimension> dims = new ArrayList<>();
                for (ArrayDimensionContext d : a.arrayDimension()) {
                    dims.add(
                            d.STAR() != null
                                    ? new TypeRef.Dimension(null, null, span(d))
                                    : new TypeRef.Dimension(
                                            expression(exprAt(d.expression(), 0), span(d)),
                                            expression(exprAt(d.expression(), 1), span(d)),
                                            span(d)));
                }
                yield new TypeRef.ArrayType(dims, typeSpec(a.typeSpec(), span), List.of(), span);
            }
            case StringTypeContext s ->
                new TypeRef.StringType(
                        s.WSTRING() != null, s.expression() == null ? null : expression(s.expression(), span), span);
            case RefToTypeContext r ->
                new TypeRef.PointerType(TypeRef.PointerKind.REF_TO, typeSpec(r.typeSpec(), span), span);
            case StructTypeContext s -> new TypeRef.StructType(members(s.varDeclaration()), span);
            case EnumTypeContext e -> {
                List<TypeRef.EnumValue> values = new ArrayList<>();
                for (EnumValueContext v : e.enumValue()) {
                    values.add(new TypeRef.EnumValue(
                            identifier(v.identifier()),
                            v.expression() == null ? null : expression(v.expression(), span(v)),
                            span(v)));
                }
                yield new TypeRef.EnumType(values, e.namedType() == null ? null : namedType(e.namedType()), span);
            }
            default -> new TypeRef.Invalid(span);
        };
    }

    private TypeRef.Named namedType(NamedTypeContext ctx) {
        Span span = span(ctx);
        QualifiedName name = ctx.qualifiedName() == null ? null : qualifiedName(ctx.qualifiedName());
        if (name == null) {
            name = new QualifiedName(List.of(new Identifier("?", span)), span);
        }
        Expression lo = null;
        Expression hi = null;
        if (!ctx.expression().isEmpty()) {
            lo = expression(exprAt(ctx.expression(), 0), span);
            hi = expression(exprAt(ctx.expression(), 1), span);
        }
        return new TypeRef.Named(name, List.of(), lo, hi, null, span);
    }

    // ============================================================================================
    // Statements
    // ============================================================================================

    private List<Statement> statementList(StatementListContext ctx) {
        List<Statement> out = new ArrayList<>();
        if (ctx == null || ctx.children == null) {
            return out;
        }
        for (ParseTree c : ctx.children) {
            if (c instanceof StatementContext s) {
                statement(s, out);
            } else if (c instanceof LabelDefinitionContext l) {
                out.add(new Statement.Label(identifier(l.identifier()), span(l)));
            }
        }
        return out;
    }

    private List<Statement> caseBody(CaseBodyContext ctx) {
        List<Statement> out = new ArrayList<>();
        if (ctx != null) {
            for (StatementContext s : ctx.statement()) {
                statement(s, out);
            }
        }
        return out;
    }

    private void statement(StatementContext ctx, List<Statement> out) {
        if (ctx.getChildCount() > 0 && ctx.getChild(0) instanceof DesignatorStatementContext d) {
            designatorStatement(d, out);
        } else {
            out.add(statement(ctx));
        }
    }

    private Statement statement(StatementContext ctx) {
        Span span = span(ctx);
        if (ctx.getChildCount() == 0) {
            return new Statement.Invalid(span);
        }
        ParseTree c = ctx.getChild(0);
        return switch (c) {
            case IfStatementContext i -> ifStatement(i);
            case CaseStatementContext k -> caseStatement(k);
            case ForStatementContext f ->
                new Statement.For(
                        designator(f.designator(), span),
                        expression(exprAt(f.expression(), 0), span),
                        expression(exprAt(f.expression(), 1), span),
                        f.BY() == null ? null : expression(exprAt(f.expression(), 2), span),
                        statementList(f.statementList()),
                        span);
            case WhileStatementContext w ->
                new Statement.While(condition(w.condition(), span), statementList(w.statementList()), span);
            case RepeatStatementContext r ->
                new Statement.Repeat(statementList(r.statementList()), condition(r.condition(), span), span);
            case ExitStatementContext e -> new Statement.Exit(span);
            case ContinueStatementContext e -> new Statement.Continue(span);
            case ReturnStatementContext r ->
                new Statement.Return(r.expression() == null ? null : expression(r.expression(), span), span);
            case GotoStatementContext g ->
                new Statement.Jump(g.identifier() == null ? missingIdentifier(span) : identifier(g.identifier()), span);
            case RegionStatementContext r ->
                new Statement.Region(regionName(r.REGION_LINE().getText()), statementList(r.statementList()), span);
            case EmptyStatementContext e -> new Statement.Empty(span);
            default -> new Statement.Invalid(span);
        };
    }

    /** {@code REGION Motor control // comment}: the text after the keyword, without a trailing line comment. */
    private static String regionName(String line) {
        String s = line.substring("REGION".length());
        int comment = s.indexOf("//");
        if (comment >= 0) {
            s = s.substring(0, comment);
        }
        return s.strip();
    }

    /** Adds one statement, or several for a chained assignment {@code a := b := 0;}. */
    private void designatorStatement(DesignatorStatementContext ctx, List<Statement> out) {
        Span span = span(ctx);
        Expression target = designator(ctx.designator(), span);
        AssignmentTailContext tail = ctx.assignmentTail();
        if (tail == null) {
            if (!(target instanceof Expression.Call) && !(target instanceof Expression.Invalid)) {
                sink.report(Diagnostic.warning(
                        SyntaxCodes.NOT_A_STATEMENT,
                        "statement has no effect: '" + ctx.designator().getText() + "' is neither assigned nor called",
                        target.span()));
            }
            out.add(new Statement.CallStatement(target, span));
            return;
        }
        Expression value = expression(tail.expression(), span(tail));
        if (tail.op != null) {
            AssignOp compound = switch (tail.op.getType()) {
                case SiemensSclLexer.ADD_ASSIGN -> AssignOp.ADD;
                case SiemensSclLexer.SUB_ASSIGN -> AssignOp.SUB;
                case SiemensSclLexer.MUL_ASSIGN -> AssignOp.MUL;
                default -> AssignOp.DIV;
            };
            out.add(new Statement.Assignment(target, compound, value, span));
            return;
        }
        AssignOp op = tail.ATTEMPT_ASSIGN() != null ? AssignOp.ATTEMPT : AssignOp.ASSIGN;
        List<DesignatorContext> chained = tail.designator();
        // evaluation order of `a := b := v`: b := v, then a := v
        for (int i = chained.size() - 1; i >= 0; i--) {
            out.add(new Statement.Assignment(designator(chained.get(i), span), op, value, span));
        }
        out.add(new Statement.Assignment(target, op, value, span));
    }

    /** IF / ELSIF / WHILE / UNTIL condition (the core grammar's `condition` extension point). */
    private Expression condition(ConditionContext ctx, Span fallback) {
        return expression(ctx == null ? null : ctx.expression(), fallback);
    }

    private Statement ifStatement(IfStatementContext ctx) {
        Span span = span(ctx);
        List<Statement.Branch> branches = new ArrayList<>();
        List<StatementListContext> lists = ctx.statementList();
        branches.add(new Statement.Branch(
                condition(ctx.condition(), span), statementList(listAt(lists, 0)), span(ctx.getStart(), ctx.THEN())));
        for (ElsifClauseContext e : ctx.elsifClause()) {
            branches.add(
                    new Statement.Branch(condition(e.condition(), span(e)), statementList(e.statementList()), span(e)));
        }
        List<Statement> elseBody = ctx.ELSE() == null ? null : statementList(listAt(lists, 1));
        return new Statement.If(branches, elseBody, span);
    }

    private Statement caseStatement(CaseStatementContext ctx) {
        Span span = span(ctx);
        List<Statement.CaseBranch> branches = new ArrayList<>();
        for (CaseElementContext e : ctx.caseElement()) {
            List<Statement.CaseLabel> labels = new ArrayList<>();
            for (CaseLabelContext l : e.caseLabel()) {
                labels.add(new Statement.CaseLabel(
                        expression(exprAt(l.expression(), 0), span(l)),
                        l.DOTDOT() == null ? null : expression(exprAt(l.expression(), 1), span(l)),
                        span(l)));
            }
            branches.add(new Statement.CaseBranch(labels, caseBody(e.caseBody()), span(e)));
        }
        List<Statement> elseBody = ctx.ELSE() == null ? null : statementList(ctx.statementList());
        return new Statement.Case(expression(ctx.expression(), span), branches, elseBody, span);
    }

    // ============================================================================================
    // Expressions
    // ============================================================================================

    private Expression expression(ExpressionContext ctx, Span fallback) {
        if (ctx == null) {
            return new Expression.Invalid(fallback);
        }
        Span span = span(ctx);
        return switch (ctx) {
            case PrimaryExprContext p -> primary(p.primaryExpression(), span);
            case PowerExprContext p -> binary(p.POWER().getSymbol(), p.expression(), span);
            case UnaryExprContext u -> unary(u.op, expression(u.expression(), span), span);
            case MulExprContext m -> binary(m.op, m.expression(), span);
            case AddExprContext a -> binary(a.op, a.expression(), span);
            case RelExprContext r -> binary(r.op, r.expression(), span);
            case EqExprContext e -> binary(e.op, e.expression(), span);
            case AndExprContext a -> binary(a.op, a.expression(), span);
            case XorExprContext x -> binary(x.op, x.expression(), span);
            case OrExprContext o -> binary(o.op, o.expression(), span);
            default -> new Expression.Invalid(span);
        };
    }

    private Expression binary(Token op, List<ExpressionContext> operands, Span span) {
        return binary(op, expression(exprAt(operands, 0), span), expression(exprAt(operands, 1), span), span);
    }

    private Expression primary(PrimaryExpressionContext ctx, Span span) {
        if (ctx == null) {
            return new Expression.Invalid(span);
        }
        if (ctx.literal() != null) {
            return literal(ctx.literal());
        }
        if (ctx.designator() != null) {
            return designator(ctx.designator(), span);
        }
        if (ctx.expression() == null && (ctx.STRING() != null || ctx.WSTRING() != null)) {
            Span s = span(ctx);
            return new Expression.TypeOperand(new TypeRef.StringType(ctx.WSTRING() != null, null, s), s);
        }
        return expression(ctx.expression(), span);
    }

    private Expression designator(DesignatorContext ctx, Span fallback) {
        if (ctx == null || ctx.designatorRoot() == null) {
            return new Expression.Invalid(ctx == null ? fallback : span(ctx));
        }
        DesignatorRootContext root = ctx.designatorRoot();
        Span rootSpan = span(root);
        List<PostfixOpContext> ops = ctx.postfixOp();
        int firstOp = 0;
        Expression e;
        if (root.DIRECT_ADDRESS() != null || root.ANY_POINTER() != null) {
            e = new Expression.DirectAddress(root.getText(), rootSpan);
        } else if (root.HASH() != null) {
            e = new Expression.NameRef(local(root), false, rootSpan);
        } else if (root.identifier() != null) {
            Identifier name = identifier(root.identifier());
            int classic = classicDbAccess(name, ops);
            if (classic > 0) {
                firstOp = classic;
                Span s = text.span(rootSpan.start(), span(ops.get(classic - 1)).end());
                e = new Expression.DirectAddress(text.text().substring(s.start(), s.end()), s);
            } else {
                e = new Expression.NameRef(name, false, rootSpan);
            }
        } else {
            return new Expression.Invalid(rootSpan);
        }
        int start = rootSpan.start();
        for (int k = firstOp; k < ops.size(); k++) {
            PostfixOpContext op = ops.get(k);
            Span s = text.span(start, Math.max(start, span(op).end()));
            e = switch (op) {
                case MemberOpContext m ->
                    new Expression.MemberAccess(
                            e, m.localName() == null ? missingIdentifier(s) : localName(m.localName()), s);
                case BitOpContext b ->
                    new Expression.BitAccess(e, parseIntOr(b.INTEGER().getText(), -1), s);
                case SliceOpContext sl -> slice(e, sl.SLICE().getText(), s);
                case DerefOpContext d -> new Expression.Deref(e, s);
                case IndexOpContext i -> {
                    List<Expression> indices = new ArrayList<>();
                    for (ExpressionContext x : i.expression()) {
                        indices.add(expression(x, s));
                    }
                    yield new Expression.Index(e, indices, s);
                }
                case CallOpContext c -> new Expression.Call(e, arguments(c.argumentList()), s);
                default -> new Expression.Invalid(s);
            };
        }
        return e;
    }

    private Identifier argumentName(ArgumentNameContext ctx) {
        if (ctx == null) {
            return new Identifier("<missing>", text.span(0, 0));
        }
        return ctx.localName() != null ? localName(ctx.localName()) : new Identifier(ctx.getText(), span(ctx));
    }

    /** A name after {@code #} or {@code .}, where keywords are names too ({@code #array}). */
    private Identifier localName(LocalNameContext ctx) {
        return ctx.identifier() != null ? identifier(ctx.identifier()) : new Identifier(ctx.getText(), span(ctx));
    }

    /** {@code #x} / {@code #"x y"}; warns when the prefix is separated from the name. */
    private Identifier local(DesignatorRootContext root) {
        Token hash = root.HASH().getSymbol();
        LocalNameContext n = root.localName();
        Identifier name = n == null ? missingIdentifier(tokenSpan(hash)) : localName(n);
        if (n != null && n.getStart().getStartIndex() != hash.getStopIndex() + 1) {
            sink.report(Diagnostic.warning(
                    SclCodes.DETACHED_LOCAL_PREFIX,
                    "'#' must be written directly before the local name '" + name.name() + "'",
                    tokenSpan(hash)));
        }
        return new Identifier(
                name.name(),
                Identifier.Form.LOCAL,
                text.span(hash.getStartIndex(), name.span().end()));
    }

    /**
     * Classic {@code DB10.DBW2} / {@code DB10.DBX0.1}: the number of postfix operators that belong to the address (0 if
     * this is not one). Only an unquoted {@code DB<n>} root qualifies — in TIA sources that is never a symbol.
     */
    private static int classicDbAccess(Identifier root, List<PostfixOpContext> ops) {
        if (root.form() != Identifier.Form.PLAIN
                || !CLASSIC_DB.matcher(root.name()).matches()
                || ops.isEmpty()
                || !(ops.getFirst() instanceof MemberOpContext m)
                || m.localName() == null
                || m.HASH() != null
                || !CLASSIC_DB_ELEMENT.matcher(m.localName().getText()).matches()) {
            return 0;
        }
        return ops.size() > 1 && ops.get(1) instanceof BitOpContext ? 2 : 1;
    }

    private static Expression slice(Expression target, String token, Span span) {
        Expression.SliceWidth width = switch (Character.toUpperCase(token.charAt(2))) {
            case 'X' -> Expression.SliceWidth.BIT;
            case 'B' -> Expression.SliceWidth.BYTE;
            case 'W' -> Expression.SliceWidth.WORD;
            default -> Expression.SliceWidth.DWORD;
        };
        return new Expression.Slice(target, width, parseIntOr(token.substring(3), -1), span);
    }

    private List<Argument> arguments(ArgumentListContext ctx) {
        List<Argument> out = new ArrayList<>();
        if (ctx == null) {
            return out;
        }
        for (ArgumentContext a : ctx.argument()) {
            Span span = span(a);
            out.add(
                    switch (a) {
                        case NamedArgumentContext n ->
                            new Argument(
                                    ArgumentKind.INPUT,
                                    argumentName(n.argumentName()),
                                    false,
                                    n.expression() == null ? null : expression(n.expression(), span),
                                    span);
                        case OutputArgumentContext o ->
                            new Argument(
                                    ArgumentKind.OUTPUT,
                                    argumentName(o.argumentName()),
                                    o.NOT() != null,
                                    o.designator() == null ? null : designator(o.designator(), span),
                                    span);
                        case PositionalArgumentContext p ->
                            new Argument(ArgumentKind.POSITIONAL, null, false, expression(p.expression(), span), span);
                        default ->
                            new Argument(ArgumentKind.POSITIONAL, null, false, new Expression.Invalid(span), span);
                    });
        }
        return out;
    }

    private Expression literal(LiteralContext ctx) {
        if (ctx.typedLiteral() != null) {
            return typedLiteral(ctx.typedLiteral(), ctx.typedLiteral().HASH());
        }
        Token t = ctx.getStart();
        if (t.getType() == SiemensSclLexer.S5TIME_LITERAL) {
            return prefixed(Expression.LiteralKind.TIME, t.getText(), span(ctx));
        }
        return coreLiteral(t, span(ctx));
    }

    // ============================================================================================
    // Names and pragmas
    // ============================================================================================

    /** {@code (/* … *}{@code /)} comment sections close with {@code *}{@code /)}. */
    @Override
    protected boolean commentTerminated(Token t) {
        if (t.getType() == SiemensSclLexer.SECTION_COMMENT) {
            return t.getText().length() >= 6 && t.getText().endsWith("*/)");
        }
        return super.commentTerminated(t);
    }

    /** SCL attributes are {@code key := 'value'} pairs: one ATTRIBUTE pragma each. */
    @Override
    protected List<Pragma> pragmasOf(Token t) {
        return Pragma.siemensAttributes(t.getText(), tokenSpan(t));
    }

    /** {@code x}, or {@code "x y"} (form QUOTED; the name is the text between the quotes). */
    private Identifier identifier(IdentifierContext ctx) {
        if (ctx != null && ctx.QUOTED_IDENT() != null) {
            String quoted = ctx.QUOTED_IDENT().getText();
            return new Identifier(quoted.substring(1, quoted.length() - 1), Identifier.Form.QUOTED, span(ctx));
        }
        return identifierOf(ctx);
    }

    private QualifiedName qualifiedName(QualifiedNameContext ctx) {
        List<Identifier> parts = new ArrayList<>();
        for (IdentifierContext i : ctx.identifier()) {
            parts.add(identifier(i));
        }
        if (parts.isEmpty()) {
            parts.add(new Identifier("<missing>", span(ctx)));
        }
        return new QualifiedName(parts, span(ctx));
    }

    private static ExpressionContext exprAt(List<ExpressionContext> list, int i) {
        return i < list.size() ? list.get(i) : null;
    }

    private static StatementListContext listAt(List<StatementListContext> list, int i) {
        return i < list.size() ? list.get(i) : null;
    }
}
