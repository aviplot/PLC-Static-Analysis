package dev.plca.frontend.st;

import dev.plca.ast.Declaration;
import dev.plca.ast.Declaration.Modifier;
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
import dev.plca.frontend.st.kit.BuilderSupport;
import dev.plca.frontend.st.kit.CoreTokens;
import dev.plca.grammar.CodesysStLexer;
import dev.plca.grammar.CodesysStParser.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Builds the {@code plc-ast} tree from a CODESYS ST parse tree (design rule 3: nothing downstream sees ANTLR types).
 * The grammar-independent parts live in {@link BuilderSupport}; this class is the glue for the CODESYS contexts.
 *
 * <p>Error recovery leaves partial trees: missing children are null and rule contexts may carry an exception. Every
 * method here tolerates that and substitutes {@code Invalid} nodes, so a syntax error never loses the rest of the file.
 */
final class AstBuilder extends BuilderSupport {

    static final CoreTokens TOKENS = new CoreTokens(CodesysStLexer.VOCABULARY, CodesysStLexer.channelNames);

    /** A TwinCAT {@code <Declaration>} fragment: optional header plus VAR blocks, or TYPE declarations. */
    record DeclarationPart(
            Header header,
            Set<Modifier> modifiers,
            List<VarBlock> varBlocks,
            List<Declaration.TypeDecl> types,
            Span span) {
        /** Modifiers of the header, or the loose ones of a property accessor declaration ({@code PROTECTED VAR …}). */
        Set<Modifier> effectiveModifiers() {
            return header != null ? header.modifiers() : modifiers;
        }
    }

    /** The {@code FUNCTION_BLOCK X EXTENDS Y …} part of a POU/member. */
    record Header(
            PouKind kind,
            Identifier name,
            Set<Modifier> modifiers,
            TypeRef type,
            List<QualifiedName> extendsTypes,
            List<QualifiedName> implementsTypes,
            List<Pragma> pragmas,
            Span span) {}

    AstBuilder(SourceText text, CommonTokenStream tokens, DiagnosticSink sink) {
        super(text, tokens, sink, TOKENS);
    }

    // ============================================================================================
    // Entry points
    // ============================================================================================

    List<Declaration> compilationUnit(CompilationUnitContext ctx) {
        List<Declaration> out = new ArrayList<>();
        for (LibraryElementContext e : ctx.libraryElement()) {
            libraryElement(e, out);
        }
        return out;
    }

    Expression expressionOnly(ExpressionOnlyContext ctx) {
        return expression(ctx.expression(), span(ctx));
    }

    List<Statement> statementsOnly(StatementsOnlyContext ctx) {
        return statementList(ctx.statementList());
    }

    DeclarationPart declarationPart(DeclarationPartContext ctx) {
        List<Declaration.TypeDecl> types = new ArrayList<>();
        List<VarSectionContext> vars = new ArrayList<>();
        List<ModifierContext> loose = new ArrayList<>();
        List<InheritanceClauseContext> late = new ArrayList<>();
        Header header = null;
        boolean afterGeneric = false;
        for (DeclarationItemContext item : ctx.declarationItem()) {
            if (item.inheritanceClause() != null) {
                lateClause(item.inheritanceClause(), afterGeneric && header != null, late);
                continue;
            }
            afterGeneric = false;
            if (item.dataTypeDeclaration() != null) {
                types.addAll(dataTypeDeclaration(item.dataTypeDeclaration()));
            } else if (item.varSection() != null) {
                afterGeneric = vars.isEmpty()
                        && header != null
                        && header.kind() == PouKind.FUNCTION_BLOCK
                        && isGeneric(item.varSection());
                vars.add(item.varSection());
            } else if (item.modifier() != null) {
                loose.add(item.modifier());
            } else if (item.pouHeader() != null && header == null) {
                header = pouHeader(item.pouHeader());
            }
        }
        if (header != null) {
            header = withClauses(header, late);
        }
        return new DeclarationPart(header, modifiers(loose), varSections(vars), types, span(ctx));
    }

    // ============================================================================================
    // Library elements
    // ============================================================================================

    private void libraryElement(LibraryElementContext ctx, List<Declaration> out) {
        if (ctx.programDeclaration() != null) {
            out.add(program(ctx.programDeclaration()));
        } else if (ctx.functionDeclaration() != null) {
            out.add(function(ctx.functionDeclaration()));
        } else if (ctx.functionBlockDeclaration() != null) {
            out.add(functionBlock(ctx.functionBlockDeclaration()));
        } else if (ctx.classDeclaration() != null) {
            out.add(classDecl(ctx.classDeclaration()));
        } else if (ctx.interfaceDeclaration() != null) {
            out.add(interfaceDecl(ctx.interfaceDeclaration()));
        } else if (ctx.methodDeclaration() != null) {
            attachOrAdd(method(ctx.methodDeclaration(), false), out);
        } else if (ctx.propertyDeclaration() != null) {
            attachOrAdd(property(ctx.propertyDeclaration(), false), out);
        } else if (ctx.actionDeclaration() != null) {
            attachOrAdd(action(ctx.actionDeclaration()), out);
        } else if (ctx.dataTypeDeclaration() != null) {
            out.addAll(dataTypeDeclaration(ctx.dataTypeDeclaration()));
        } else if (ctx.varSection() != null) {
            VarBlock b = varSection(ctx.varSection());
            out.add(new Declaration.GlobalVars(null, b, b.span()));
        } else if (ctx.namespaceDeclaration() != null) {
            NamespaceDeclarationContext n = ctx.namespaceDeclaration();
            List<Declaration> members = new ArrayList<>();
            for (LibraryElementContext e : n.libraryElement()) {
                libraryElement(e, members);
            }
            out.add(new Declaration.Namespace(qualifiedName(n.qualifiedName()), members, span(n)));
        } else if (ctx.usingDirective() != null) {
            UsingDirectiveContext u = ctx.usingDirective();
            out.add(new Declaration.Using(qualifiedNames(u.qualifiedNameList()), span(u)));
        } else if (ctx.configurationDeclaration() != null) {
            out.add(configuration(ctx.configurationDeclaration()));
        }
    }

    /**
     * Some exporters write methods/properties/actions after {@code END_FUNCTION_BLOCK}; they belong to the POU directly
     * before them in the same text. Without one they stay top-level.
     */
    private static void attachOrAdd(Pou member, List<Declaration> out) {
        if (!out.isEmpty() && out.getLast() instanceof Pou owner && canOwn(owner.kind())) {
            List<Pou> members = new ArrayList<>(owner.members());
            members.add(member);
            out.set(out.size() - 1, withMembers(owner, members));
        } else {
            out.add(member);
        }
    }

    private static boolean canOwn(PouKind kind) {
        return kind == PouKind.FUNCTION_BLOCK
                || kind == PouKind.PROGRAM
                || kind == PouKind.CLASS
                || kind == PouKind.INTERFACE;
    }

    private static Pou withMembers(Pou p, List<Pou> members) {
        return new Pou(
                p.kind(),
                p.name(),
                p.modifiers(),
                p.returnType(),
                p.extendsTypes(),
                p.implementsTypes(),
                p.varBlocks(),
                members,
                p.body(),
                p.bodyLanguage(),
                p.pragmas(),
                p.span());
    }

    private Pou program(ProgramDeclarationContext ctx) {
        return pou(header(ctx.programHeader()), ctx.varSection(), ctx.pouBody(), span(ctx));
    }

    private Pou function(FunctionDeclarationContext ctx) {
        return pou(header(ctx.functionHeader()), ctx.varSection(), ctx.pouBody(), span(ctx));
    }

    private Pou functionBlock(FunctionBlockDeclarationContext ctx) {
        List<InheritanceClauseContext> late = new ArrayList<>();
        boolean afterGeneric = false;
        int sections = 0;
        for (ParseTree child : ctx.children == null ? List.<ParseTree>of() : ctx.children) {
            if (child instanceof VarSectionContext v) {
                afterGeneric = sections++ == 0 && isGeneric(v);
            } else if (child instanceof InheritanceClauseContext c) {
                lateClause(c, afterGeneric, late);
            }
        }
        Header h = withClauses(header(ctx.functionBlockHeader()), late);
        return pou(h, ctx.varSection(), ctx.pouBody(), span(ctx));
    }

    private Pou classDecl(ClassDeclarationContext ctx) {
        return pou(header(ctx.classHeader()), ctx.varSection(), ctx.pouBody(), span(ctx));
    }

    private Pou pou(Header h, List<VarSectionContext> vars, PouBodyContext body, Span span) {
        List<Pou> members = new ArrayList<>();
        List<Statement> statements = new ArrayList<>();
        if (body != null) {
            for (ParseTree child : body.children == null ? List.<ParseTree>of() : body.children) {
                if (child instanceof MemberDeclarationContext m) {
                    members.add(member(m, false));
                } else if (child instanceof StatementContext s) {
                    statement(s, statements);
                } else if (child instanceof LabelDefinitionContext l) {
                    statements.add(new Statement.Label(identifier(l.identifier()), span(l)));
                }
            }
        }
        return new Pou(
                h.kind(),
                h.name(),
                h.modifiers(),
                h.type(),
                h.extendsTypes(),
                h.implementsTypes(),
                varSections(vars),
                members,
                statements,
                "ST",
                h.pragmas(),
                span);
    }

    private Pou interfaceDecl(InterfaceDeclarationContext ctx) {
        Header h = header(ctx.interfaceHeader());
        List<Pou> members = new ArrayList<>();
        for (MemberDeclarationContext m : ctx.memberDeclaration()) {
            members.add(member(m, true));
        }
        return new Pou(
                h.kind(),
                h.name(),
                h.modifiers(),
                null,
                h.extendsTypes(),
                List.of(),
                List.of(),
                members,
                null,
                null,
                h.pragmas(),
                span(ctx));
    }

    private Pou member(MemberDeclarationContext ctx, boolean prototype) {
        if (ctx.methodDeclaration() != null) {
            return method(ctx.methodDeclaration(), prototype);
        }
        if (ctx.propertyDeclaration() != null) {
            return property(ctx.propertyDeclaration(), prototype);
        }
        return action(ctx.actionDeclaration());
    }

    private Pou method(MethodDeclarationContext ctx, boolean prototype) {
        Header h = header(ctx.methodHeader());
        List<Statement> body = prototype ? null : statementList(ctx.statementList());
        return new Pou(
                PouKind.METHOD,
                h.name(),
                h.modifiers(),
                h.type(),
                List.of(),
                List.of(),
                varSections(ctx.varSection()),
                List.of(),
                body,
                prototype ? null : "ST",
                h.pragmas(),
                span(ctx));
    }

    private Pou property(PropertyDeclarationContext ctx, boolean prototype) {
        Header h = header(ctx.propertyHeader());
        List<Pou> accessors = new ArrayList<>();
        for (PropertyAccessorContext a : ctx.propertyAccessor()) {
            boolean get = a.GET() != null;
            Token kw = get ? a.GET().getSymbol() : a.SET() != null ? a.SET().getSymbol() : a.getStart();
            accessors.add(new Pou(
                    get ? PouKind.PROPERTY_GET : PouKind.PROPERTY_SET,
                    new Identifier(kw.getText(), tokenSpan(kw)),
                    Set.of(),
                    null,
                    List.of(),
                    List.of(),
                    varSections(a.varSection()),
                    List.of(),
                    prototype ? null : statementList(a.statementList()),
                    prototype ? null : "ST",
                    pragmasBefore(a.getStart()),
                    span(a)));
        }
        return new Pou(
                PouKind.PROPERTY,
                h.name(),
                h.modifiers(),
                h.type(),
                List.of(),
                List.of(),
                varSections(ctx.varSection()),
                accessors,
                null,
                null,
                h.pragmas(),
                span(ctx));
    }

    private Pou action(ActionDeclarationContext ctx) {
        return new Pou(
                PouKind.ACTION,
                identifier(ctx.identifier()),
                Set.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                statementList(ctx.statementList()),
                "ST",
                pragmasBefore(ctx.getStart()),
                span(ctx));
    }

    // ---- headers ---------------------------------------------------------------------------------

    Header pouHeader(PouHeaderContext ctx) {
        ParserRuleContext h = (ParserRuleContext) ctx.getChild(0);
        return switch (h) {
            case ProgramHeaderContext p -> header(p);
            case FunctionHeaderContext f -> header(f);
            case FunctionBlockHeaderContext f -> header(f);
            case ClassHeaderContext c -> header(c);
            case InterfaceHeaderContext i -> header(i);
            case MethodHeaderContext m -> header(m);
            case PropertyHeaderContext p -> header(p);
            default -> throw new IllegalStateException("unknown header " + h.getClass());
        };
    }

    private Header header(ProgramHeaderContext ctx) {
        return new Header(
                PouKind.PROGRAM,
                identifier(ctx.identifier()),
                Set.of(),
                null,
                List.of(),
                List.of(),
                pragmasBefore(ctx.getStart()),
                span(ctx));
    }

    private Header header(FunctionHeaderContext ctx) {
        return new Header(
                PouKind.FUNCTION,
                identifier(ctx.identifier()),
                modifiers(ctx.modifier()),
                typeSpecOrNull(ctx.typeSpec()),
                List.of(),
                List.of(),
                pragmasBefore(ctx.getStart()),
                span(ctx));
    }

    private Header header(FunctionBlockHeaderContext ctx) {
        return inherited(PouKind.FUNCTION_BLOCK, ctx.identifier(), ctx.modifier(), ctx.inheritance(), ctx);
    }

    private Header header(ClassHeaderContext ctx) {
        return inherited(PouKind.CLASS, ctx.identifier(), ctx.modifier(), ctx.inheritance(), ctx);
    }

    private Header header(InterfaceHeaderContext ctx) {
        List<QualifiedName> ext = ctx.qualifiedNameList() == null ? List.of() : qualifiedNames(ctx.qualifiedNameList());
        return new Header(
                PouKind.INTERFACE,
                identifier(ctx.identifier()),
                Set.of(),
                null,
                ext,
                List.of(),
                pragmasBefore(ctx.getStart()),
                span(ctx));
    }

    private Header header(MethodHeaderContext ctx) {
        return new Header(
                PouKind.METHOD,
                identifier(ctx.identifier()),
                modifiers(ctx.modifier()),
                typeSpecOrNull(ctx.typeSpec()),
                List.of(),
                List.of(),
                pragmasBefore(ctx.getStart()),
                span(ctx));
    }

    private Header header(PropertyHeaderContext ctx) {
        return new Header(
                PouKind.PROPERTY,
                identifier(ctx.identifier()),
                modifiers(ctx.modifier()),
                typeSpecOrNull(ctx.typeSpec()),
                List.of(),
                List.of(),
                pragmasBefore(ctx.getStart()),
                span(ctx));
    }

    private Header inherited(
            PouKind kind,
            IdentifierContext name,
            List<ModifierContext> mods,
            InheritanceContext inh,
            ParserRuleContext ctx) {
        List<QualifiedName> ext = new ArrayList<>();
        List<QualifiedName> impl = new ArrayList<>();
        if (inh != null) {
            clauses(inh.inheritanceClause(), ext, impl);
        }
        return new Header(
                kind, identifier(name), modifiers(mods), null, ext, impl, pragmasBefore(ctx.getStart()), span(ctx));
    }

    /** Generic arguments of a base ({@code EXTENDS FB_Base<n>}) are not kept: the AST has the base name only. */
    private void clauses(List<InheritanceClauseContext> clauses, List<QualifiedName> ext, List<QualifiedName> impl) {
        for (InheritanceClauseContext c : clauses) {
            if (c.qualifiedName() != null) {
                ext.add(qualifiedName(c.qualifiedName()));
            } else if (c.qualifiedNameList() != null) {
                impl.addAll(qualifiedNames(c.qualifiedNameList()));
            }
        }
    }

    /**
     * EXTENDS / IMPLEMENTS after the VAR_GENERIC CONSTANT section of a generic FB (CODESYS: generic constants come
     * first). Anywhere else after the header the compiler rejects them; they are reported but still kept.
     */
    private void lateClause(InheritanceClauseContext c, boolean afterGeneric, List<InheritanceClauseContext> out) {
        if (!afterGeneric) {
            sink.report(Diagnostic.error(
                    SyntaxCodes.SYNTAX,
                    "'" + c.getStart().getText() + "' must follow the function block name"
                            + " or its leading VAR_GENERIC CONSTANT section",
                    tokenSpan(c.getStart())));
        }
        out.add(c);
    }

    private static boolean isGeneric(VarSectionContext v) {
        return v.varSectionKeyword() != null && v.varSectionKeyword().VAR_GENERIC() != null;
    }

    private Header withClauses(Header h, List<InheritanceClauseContext> late) {
        if (late.isEmpty()) {
            return h;
        }
        List<QualifiedName> ext = new ArrayList<>(h.extendsTypes());
        List<QualifiedName> impl = new ArrayList<>(h.implementsTypes());
        clauses(late, ext, impl);
        return new Header(h.kind(), h.name(), h.modifiers(), h.type(), ext, impl, h.pragmas(), h.span());
    }

    private static Set<Modifier> modifiers(List<ModifierContext> mods) {
        Set<Modifier> out = EnumSet.noneOf(Modifier.class);
        for (ModifierContext m : mods) {
            out.add(Modifier.valueOf(m.getStart().getText().toUpperCase(java.util.Locale.ROOT)));
        }
        return out;
    }

    // ---- configuration ---------------------------------------------------------------------------

    private Declaration.Configuration configuration(ConfigurationDeclarationContext ctx) {
        List<VarBlock> vars = new ArrayList<>();
        List<Declaration.ConfigElement> elements = new ArrayList<>();
        configItems(ctx.configurationItem(), vars, elements);
        return new Declaration.Configuration(identifier(ctx.identifier()), vars, elements, span(ctx));
    }

    private void configItems(
            List<ConfigurationItemContext> items, List<VarBlock> vars, List<Declaration.ConfigElement> elements) {
        for (ConfigurationItemContext i : items) {
            if (i.varSection() != null) {
                vars.add(varSection(i.varSection()));
            } else if (i.resourceDeclaration() != null) {
                ResourceDeclarationContext r = i.resourceDeclaration();
                List<VarBlock> rv = new ArrayList<>();
                List<Declaration.ConfigElement> re = new ArrayList<>();
                configItems(r.configurationItem(), rv, re);
                elements.add(new Declaration.Resource(
                        identifier(r.identifier()), qualifiedNameOrNull(r.qualifiedName()), rv, re, span(r)));
            } else if (i.taskConfiguration() != null) {
                TaskConfigurationContext t = i.taskConfiguration();
                elements.add(new Declaration.Task(identifier(t.identifier()), arguments(t.argumentList()), span(t)));
            } else if (i.programConfiguration() != null) {
                ProgramConfigurationContext p = i.programConfiguration();
                Identifier task = p.identifier().size() > 1 ? identifier(p.identifier(1)) : null;
                elements.add(new Declaration.ProgramInstance(
                        identifier(p.identifier(0)),
                        task,
                        qualifiedNameOrNull(p.qualifiedName()),
                        arguments(p.argumentList()),
                        span(p)));
            }
        }
    }

    // ============================================================================================
    // Variables and types
    // ============================================================================================

    List<VarBlock> varSections(List<VarSectionContext> sections) {
        List<VarBlock> out = new ArrayList<>(sections.size());
        for (VarSectionContext s : sections) {
            out.add(varSection(s));
        }
        return out;
    }

    private VarBlock varSection(VarSectionContext ctx) {
        VarBlock.Section section = section(ctx.varSectionKeyword());
        Set<VarBlock.Qualifier> qualifiers = EnumSet.noneOf(VarBlock.Qualifier.class);
        for (VarQualifierContext q : ctx.varQualifier()) {
            qualifiers.add(VarBlock.Qualifier.valueOf(q.getStart().getText().toUpperCase(java.util.Locale.ROOT)));
        }
        List<VarDecl> vars = new ArrayList<>();
        for (VarDeclarationContext d : ctx.varDeclaration()) {
            varDeclaration(d, vars);
        }
        return new VarBlock(section, qualifiers, vars, pragmasBefore(ctx.getStart()), span(ctx));
    }

    private static VarBlock.Section section(VarSectionKeywordContext ctx) {
        return switch (ctx.getStart().getType()) {
            case CodesysStLexer.VAR_INPUT -> VarBlock.Section.INPUT;
            case CodesysStLexer.VAR_OUTPUT -> VarBlock.Section.OUTPUT;
            case CodesysStLexer.VAR_IN_OUT -> VarBlock.Section.IN_OUT;
            case CodesysStLexer.VAR_GLOBAL -> VarBlock.Section.GLOBAL;
            case CodesysStLexer.VAR_EXTERNAL -> VarBlock.Section.EXTERNAL;
            case CodesysStLexer.VAR_TEMP -> VarBlock.Section.TEMP;
            case CodesysStLexer.VAR_STAT -> VarBlock.Section.STAT;
            case CodesysStLexer.VAR_INST -> VarBlock.Section.INST;
            case CodesysStLexer.VAR_ACCESS -> VarBlock.Section.ACCESS;
            case CodesysStLexer.VAR_CONFIG -> VarBlock.Section.CONFIG;
            default -> VarBlock.Section.VAR;
        };
    }

    private void varDeclaration(VarDeclarationContext ctx, List<VarDecl> out) {
        Span span = span(ctx);
        List<Pragma> pragmas = pragmasBefore(ctx.getStart());
        TypeRef type = typeSpec(ctx.typeSpec(), span);
        if (ctx.COLON().size() == 2) {
            // VAR_ACCESS: name : path : type [READ_ONLY|READ_WRITE];
            QualifiedName path = variablePath(ctx.variablePath(0));
            out.add(new VarDecl(identifier(ctx.identifier()), path, null, type, null, false, pragmas, span));
            return;
        }
        String address = ctx.location() == null || ctx.location().DIRECT_ADDRESS() == null
                ? null
                : ctx.location().DIRECT_ADDRESS().getText();
        Initializer init = null;
        boolean referenceInit = false;
        VariableInitContext vi = ctx.variableInit();
        if (vi != null) {
            if (vi.IDENT() != null) {
                referenceInit = true;
                if (!vi.IDENT().getText().equalsIgnoreCase("REF")) {
                    sink.report(Diagnostic.error(
                            SyntaxCodes.UNKNOWN_ASSIGNMENT_OPERATOR,
                            "unknown initialisation operator '" + vi.IDENT().getText() + "='; expected ':=' or 'REF='",
                            tokenSpan(vi.IDENT().getSymbol())));
                }
                init = expression(vi.expression(), span(vi));
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
            out.add(new VarDecl(path.last(), dotted ? path : null, address, type, init, referenceInit, pragmas, span));
        }
    }

    private QualifiedName variablePath(VariablePathContext ctx) {
        if (ctx == null || ctx.identifier().isEmpty()) {
            return null;
        }
        List<Identifier> parts = new ArrayList<>();
        for (IdentifierContext i : ctx.identifier()) {
            parts.add(identifier(i));
        }
        return new QualifiedName(parts, span(ctx));
    }

    private Initializer initializer(InitializerContext ctx) {
        if (ctx.arrayInitializer() != null) {
            ArrayInitializerContext a = ctx.arrayInitializer();
            List<Initializer.Element> elements = new ArrayList<>();
            for (ArrayInitElementContext e : a.arrayInitElement()) {
                if (e.INTEGER() != null) {
                    int repeat = parseIntOr(e.INTEGER().getText(), 1);
                    elements.add(new Initializer.Element(
                            repeat, e.initializer() == null ? null : initializer(e.initializer()), span(e)));
                } else {
                    elements.add(new Initializer.Element(1, initializerOrInvalid(e.initializer(), span(e)), span(e)));
                }
            }
            return new Initializer.ArrayInit(elements, span(a));
        }
        if (ctx.structInitializer() != null) {
            StructInitializerContext s = ctx.structInitializer();
            List<Initializer.FieldInit> fields = new ArrayList<>();
            for (StructInitElementContext f : s.structInitElement()) {
                fields.add(new Initializer.FieldInit(
                        identifier(f.identifier()), initializerOrInvalid(f.initializer(), span(f)), span(f)));
            }
            return new Initializer.StructInit(fields, span(s));
        }
        return expression(ctx.expression(), span(ctx));
    }

    private Initializer initializerOrInvalid(InitializerContext ctx, Span fallback) {
        return ctx == null ? new Expression.Invalid(fallback) : initializer(ctx);
    }

    List<Declaration.TypeDecl> dataTypeDeclaration(DataTypeDeclarationContext ctx) {
        List<Declaration.TypeDecl> out = new ArrayList<>();
        List<Pragma> blockPragmas = pragmasBefore(ctx.getStart());
        boolean first = true;
        for (TypeDefinitionContext d : ctx.typeDefinition()) {
            List<Pragma> pragmas = new ArrayList<>(first ? blockPragmas : List.of());
            pragmas.addAll(pragmasBefore(d.getStart()));
            first = false;
            Span span = span(d);
            out.add(new Declaration.TypeDecl(
                    identifier(d.identifier()),
                    qualifiedNameOrNull(d.qualifiedName()),
                    typeSpec(d.typeSpec(), span),
                    d.initializer() == null ? null : initializer(d.initializer()),
                    pragmas,
                    span));
        }
        return out;
    }

    private TypeRef typeSpecOrNull(TypeSpecContext ctx) {
        return ctx == null ? null : typeSpec(ctx, span(ctx));
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
                List<TypeRef.ConstructorArgs> inits = new ArrayList<>();
                if (a.arrayElementInit() != null) {
                    for (ConstructorArgumentsContext ca : a.arrayElementInit().constructorArguments()) {
                        inits.add(constructorArgs(ca));
                    }
                }
                yield new TypeRef.ArrayType(dims, typeSpec(a.typeSpec(), span), inits, span);
            }
            case StringTypeContext s ->
                new TypeRef.StringType(
                        s.WSTRING() != null, s.expression() == null ? null : expression(s.expression(), span), span);
            case PointerTypeContext p ->
                new TypeRef.PointerType(TypeRef.PointerKind.POINTER, typeSpec(p.typeSpec(), span), span);
            case ReferenceTypeContext r ->
                new TypeRef.PointerType(TypeRef.PointerKind.REFERENCE, typeSpec(r.typeSpec(), span), span);
            case RefToTypeContext r ->
                new TypeRef.PointerType(TypeRef.PointerKind.REF_TO, typeSpec(r.typeSpec(), span), span);
            case StructTypeContext s -> new TypeRef.StructType(members(s.varDeclaration()), span);
            case UnionTypeContext u -> new TypeRef.UnionType(members(u.varDeclaration()), span);
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

    private List<VarDecl> members(List<VarDeclarationContext> decls) {
        List<VarDecl> out = new ArrayList<>();
        for (VarDeclarationContext d : decls) {
            varDeclaration(d, out);
        }
        return out;
    }

    private TypeRef.Named namedType(NamedTypeContext ctx) {
        Span span = span(ctx);
        QualifiedName name = qualifiedNameOrNull(ctx.qualifiedName());
        if (name == null) {
            name = new QualifiedName(List.of(new Identifier("?", span)), span);
        }
        List<Expression> generics = new ArrayList<>();
        if (ctx.genericArguments() != null) {
            for (ExpressionContext e : ctx.genericArguments().expression()) {
                generics.add(expression(e, span));
            }
        }
        Expression lo = null;
        Expression hi = null;
        if (ctx.subrange() != null) {
            lo = expression(exprAt(ctx.subrange().expression(), 0), span);
            hi = expression(exprAt(ctx.subrange().expression(), 1), span);
        }
        TypeRef.ConstructorArgs init =
                ctx.constructorArguments() == null ? null : constructorArgs(ctx.constructorArguments());
        return new TypeRef.Named(name, generics, lo, hi, init, span);
    }

    private TypeRef.ConstructorArgs constructorArgs(ConstructorArgumentsContext ctx) {
        return new TypeRef.ConstructorArgs(arguments(ctx.argumentList()), span(ctx));
    }

    // ============================================================================================
    // Statements
    // ============================================================================================

    List<Statement> statementList(StatementListContext ctx) {
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
        if (ctx.getChildCount() == 0 || ctx.exception != null && ctx.getChildCount() == 0) {
            return new Statement.Invalid(span);
        }
        ParseTree c = ctx.getChild(0);
        return switch (c) {
            case DesignatorStatementContext d -> {
                List<Statement> one = new ArrayList<>(1);
                designatorStatement(d, one);
                yield one.getFirst();
            }
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
                new Statement.While(expression(w.expression(), span), statementList(w.statementList()), span);
            case RepeatStatementContext r ->
                new Statement.Repeat(statementList(r.statementList()), expression(r.expression(), span), span);
            case ExitStatementContext e -> new Statement.Exit(span);
            case ContinueStatementContext e -> new Statement.Continue(span);
            case ReturnStatementContext r ->
                new Statement.Return(r.expression() == null ? null : expression(r.expression(), span), span);
            case JmpStatementContext j -> new Statement.Jump(identifierOrInvalid(j.identifier(), span), span);
            case TryStatementContext t -> tryStatement(t);
            case EmptyStatementContext e -> new Statement.Empty(span);
            default -> new Statement.Invalid(span);
        };
    }

    /** Adds one statement, or several for a chained assignment {@code a := b := 0;}. */
    private void designatorStatement(DesignatorStatementContext ctx, List<Statement> out) {
        Span span = span(ctx);
        Expression target = designator(ctx.designator(), span);
        AssignmentTailContext tail = ctx.assignmentTail();
        if (tail == null) {
            if (!(target instanceof Expression.Call) && !(target instanceof Expression.Invalid)) {
                // TwinCAT accepts `bVar;` (no effect); report as a warning, not a syntax error
                sink.report(Diagnostic.warning(
                        SyntaxCodes.NOT_A_STATEMENT,
                        "statement has no effect: '" + ctx.designator().getText() + "' is neither assigned nor called",
                        target.span()));
            }
            out.add(new Statement.CallStatement(target, span));
            return;
        }
        Expression value = expression(tail.expression(), span(tail));
        List<DesignatorContext> chained = tail.designator();
        if (tail.IDENT().isEmpty()) {
            AssignOp op = tail.ATTEMPT_ASSIGN() != null ? AssignOp.ATTEMPT : AssignOp.ASSIGN;
            // evaluation order of `a := b := v`: b := v, then a := v
            for (int i = chained.size() - 1; i >= 0; i--) {
                out.add(new Statement.Assignment(designator(chained.get(i), span), op, value, span));
            }
            out.add(new Statement.Assignment(target, op, value, span));
            return;
        }
        // `a S= b R= v`: one operator per target, all driven by the final value
        List<TerminalNode> ops = tail.IDENT();
        for (int i = chained.size() - 1; i >= 0; i--) {
            out.add(new Statement.Assignment(
                    designator(chained.get(i), span), setResetOp(ops.get(i + 1)), value, span));
        }
        out.add(new Statement.Assignment(target, setResetOp(ops.get(0)), value, span));
    }

    private AssignOp setResetOp(TerminalNode ident) {
        String name = ident.getText();
        if (name.equalsIgnoreCase("S")) {
            return AssignOp.SET;
        }
        if (name.equalsIgnoreCase("R")) {
            return AssignOp.RESET;
        }
        if (name.equalsIgnoreCase("REF")) {
            return AssignOp.REF;
        }
        sink.report(Diagnostic.error(
                SyntaxCodes.UNKNOWN_ASSIGNMENT_OPERATOR,
                "unknown assignment operator '" + name + "='; expected ':=', 'S=', 'R=' or 'REF='",
                tokenSpan(ident.getSymbol())));
        return AssignOp.ASSIGN;
    }

    private Statement ifStatement(IfStatementContext ctx) {
        Span span = span(ctx);
        List<Statement.Branch> branches = new ArrayList<>();
        List<StatementListContext> lists = ctx.statementList();
        branches.add(new Statement.Branch(
                expression(ctx.expression(), span), statementList(listAt(lists, 0)), span(ctx.getStart(), ctx.THEN())));
        for (ElsifClauseContext e : ctx.elsifClause()) {
            branches.add(new Statement.Branch(
                    expression(e.expression(), span(e)), statementList(e.statementList()), span(e)));
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

    private Statement tryStatement(TryStatementContext ctx) {
        List<Statement> body = null;
        List<Statement> catchBody = null;
        List<Statement> finallyBody = null;
        Identifier exception = ctx.identifier() == null ? null : identifier(ctx.identifier());
        int section = 0; // 0 = try, 1 = catch, 2 = finally
        for (ParseTree c : ctx.children) {
            if (c instanceof TerminalNode t) {
                if (t.getSymbol().getType() == CodesysStLexer.CATCH) {
                    section = 1;
                } else if (t.getSymbol().getType() == CodesysStLexer.FINALLY) {
                    section = 2;
                }
            } else if (c instanceof StatementListContext l) {
                List<Statement> s = statementList(l);
                switch (section) {
                    case 0 -> body = s;
                    case 1 -> catchBody = s;
                    default -> finallyBody = s;
                }
            }
        }
        return new Statement.Try(body == null ? List.of() : body, exception, catchBody, finallyBody, span(ctx));
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
        return expression(ctx.expression(), span);
    }

    private Expression designator(DesignatorContext ctx, Span fallback) {
        if (ctx == null || ctx.designatorRoot() == null) {
            return new Expression.Invalid(ctx == null ? fallback : span(ctx));
        }
        DesignatorRootContext root = ctx.designatorRoot();
        Span rootSpan = span(root);
        Expression e;
        if (root.THIS() != null) {
            e = new Expression.ThisRef(rootSpan);
        } else if (root.SUPER() != null) {
            e = new Expression.SuperRef(rootSpan);
        } else if (root.DIRECT_ADDRESS() != null) {
            e = new Expression.DirectAddress(root.getText(), rootSpan);
        } else if (root.operatorCall() != null) {
            OperatorCallContext oc = root.operatorCall();
            Token op = oc.getStart();
            Expression callee =
                    new Expression.NameRef(new Identifier(op.getText(), tokenSpan(op)), false, tokenSpan(op));
            e = new Expression.Call(callee, arguments(oc.argumentList()), rootSpan);
        } else if (root.identifier() != null) {
            e = new Expression.NameRef(identifier(root.identifier()), root.DOT() != null, rootSpan);
        } else {
            return new Expression.Invalid(rootSpan);
        }
        int start = rootSpan.start();
        for (PostfixOpContext op : ctx.postfixOp()) {
            Span s = text.span(start, Math.max(start, span(op).end()));
            e = switch (op) {
                case MemberOpContext m -> new Expression.MemberAccess(e, identifierOrInvalid(m.identifier(), s), s);
                case BitOpContext b ->
                    new Expression.BitAccess(e, parseIntOr(b.INTEGER().getText(), -1), s);
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
                                    identifier(n.identifier()),
                                    false,
                                    n.expression() == null ? null : expression(n.expression(), span),
                                    span);
                        case OutputArgumentContext o ->
                            new Argument(
                                    ArgumentKind.OUTPUT,
                                    identifier(o.identifier()),
                                    o.NOT() != null,
                                    o.designator() == null ? null : designator(o.designator(), span),
                                    span);
                        case TypeArgContext t ->
                            new Argument(
                                    ArgumentKind.POSITIONAL,
                                    null,
                                    false,
                                    new Expression.TypeOperand(typeArgument(t.typeArgument(), span), span),
                                    span);
                        case PositionalArgumentContext p ->
                            new Argument(ArgumentKind.POSITIONAL, null, false, expression(p.expression(), span), span);
                        default ->
                            new Argument(ArgumentKind.POSITIONAL, null, false, new Expression.Invalid(span), span);
                    });
        }
        return out;
    }

    private TypeRef typeArgument(TypeArgumentContext ctx, Span span) {
        if (ctx.pointerType() != null) {
            return new TypeRef.PointerType(
                    TypeRef.PointerKind.POINTER, typeSpec(ctx.pointerType().typeSpec(), span), span(ctx));
        }
        if (ctx.referenceType() != null) {
            return new TypeRef.PointerType(
                    TypeRef.PointerKind.REFERENCE, typeSpec(ctx.referenceType().typeSpec(), span), span(ctx));
        }
        StringTypeContext st = ctx.stringType();
        return new TypeRef.StringType(
                st.WSTRING() != null, st.expression() == null ? null : expression(st.expression(), span), span(ctx));
    }

    private Expression literal(LiteralContext ctx) {
        if (ctx.typedLiteral() != null) {
            return typedLiteral(ctx.typedLiteral(), ctx.typedLiteral().HASH());
        }
        return coreLiteral(ctx.getStart(), span(ctx));
    }

    // ============================================================================================
    // Names
    // ============================================================================================

    private Identifier identifier(IdentifierContext ctx) {
        return identifierOf(ctx);
    }

    private Identifier identifierOrInvalid(IdentifierContext ctx, Span fallback) {
        return ctx == null ? missingIdentifier(fallback) : identifier(ctx);
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

    private QualifiedName qualifiedNameOrNull(QualifiedNameContext ctx) {
        return ctx == null ? null : qualifiedName(ctx);
    }

    private List<QualifiedName> qualifiedNames(QualifiedNameListContext ctx) {
        List<QualifiedName> out = new ArrayList<>();
        if (ctx != null) {
            for (QualifiedNameContext q : ctx.qualifiedName()) {
                out.add(qualifiedName(q));
            }
        }
        return out;
    }

    private static ExpressionContext exprAt(List<ExpressionContext> list, int i) {
        return i < list.size() ? list.get(i) : null;
    }

    private static StatementListContext listAt(List<StatementListContext> list, int i) {
        return i < list.size() ? list.get(i) : null;
    }
}
