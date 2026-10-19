package dev.plca.frontend.plcopen;

import dev.plca.ast.Comment;
import dev.plca.ast.Declaration;
import dev.plca.ast.Declaration.Pou;
import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.ast.Initializer;
import dev.plca.ast.Pragma;
import dev.plca.ast.QualifiedName;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.TypeRef;
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
import dev.plca.core.source.XmlText;
import dev.plca.frontend.st.StTexts;
import dev.plca.frontend.st.SyntaxCodes;
import dev.plca.frontend.st.kit.Frontend;
import dev.plca.frontend.st.kit.XmlInput;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Entry point of the PLCopen XML frontend: a TC6 export ({@code tc6_0200}/{@code tc6_0201}) to one {@link SourceUnit}
 * of dialect {@link Dialect#PLCOPEN}. ST is parsed with the CODESYS grammar; LD/FBD networks and SFC charts are kept as
 * graphs and lowered to statements. The mapping is documented in {@code docs/grammar/plcopen.md}. Never throws for bad
 * input (design rule 2).
 *
 * <p>Other XML files (a project folder has many) are skipped without a diagnostic.
 *
 * <p>Not thread-safe: use one instance per thread (it reuses lexer and parser objects).
 */
public final class PlcOpenFrontend implements Frontend {

    public static final Set<String> EXTENSIONS = Set.of(".xml");

    private static final Pattern TC6 = Pattern.compile("http://www\\.plcopen\\.org/xml/tc6_0(100|200|201)");

    private final StTexts st;

    public PlcOpenFrontend() {
        this(true);
    }

    /** {@code twoStage = false} parses with full LL only; results are identical (for benchmarks). */
    public PlcOpenFrontend(boolean twoStage) {
        this.st = new StTexts(twoStage);
    }

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    /** Whether {@code text} is a PLCopen TC6 document (by its namespace, within the first 4 KB). */
    public static boolean isPlcOpen(String text) {
        return TC6.matcher(text.substring(0, Math.min(text.length(), 4096))).find();
    }

    @Override
    public Result parse(SourceFile file, SourceRegistry registry, DiagnosticSink sink) {
        if (!isPlcOpen(file.text())) {
            return new Result(
                    new SourceUnit(file.path(), Dialect.PLCOPEN, List.of(), List.of(), List.of()), Stats.NONE);
        }
        Stats before = st.stats();
        Run run = new Run(file, registry, sink);
        try {
            run.unit();
        } catch (StackOverflowError e) {
            run.internal("input is nested too deeply to analyse");
        } catch (RuntimeException e) {
            run.internal("internal error while reading the PLCopen export: " + e);
        }
        Stats after = st.stats();
        Stats delta = new Stats(
                after.texts() - before.texts(),
                after.sllFallbacks() - before.sllFallbacks(),
                after.cleanFallbacks() - before.cleanFallbacks(),
                after.syntaxErrors() - before.syntaxErrors());
        return new Result(
                new SourceUnit(file.path(), Dialect.PLCOPEN, run.declarations, run.comments, run.pragmas), delta);
    }

    /** The translation of one file. */
    final class Run {
        final SourceFile file;
        final SourceRegistry registry;
        final DiagnosticSink sink;
        final SourceText whole;
        final List<Declaration> declarations = new ArrayList<>();
        final List<Comment> comments = new ArrayList<>();
        final List<Pragma> pragmas = new ArrayList<>();
        final Expressions expressions;
        /** Numbers synthetic variables ({@code $fbd1}, {@code $act_A}) uniquely per POU. */
        int temps;

        Run(SourceFile file, SourceRegistry registry, DiagnosticSink sink) {
            this.file = file;
            this.registry = registry;
            this.sink = sink;
            this.whole = registry.register(file);
            this.expressions = new Expressions(this);
        }

        void unit() {
            PlcOpenReader.Result r = PlcOpenReader.read(file.text(), this::translate);
            if (r.error() != null) {
                sink.report(Diagnostic.error(
                        SyntaxCodes.MALFORMED_XML,
                        "malformed XML: " + r.error() + "; the rest of the file is not analysed",
                        Span.at(whole.id(), r.errorOffset())));
            }
        }

        private void translate(XNode n) {
            switch (n.tag) {
                case "fileHeader" -> {
                    String producer = java.util.stream.Stream.of(n.attr("companyName"), n.attr("productName"))
                            .filter(x -> x != null && !x.isBlank())
                            .collect(java.util.stream.Collectors.joining(" / "));
                    pragmas.add(attribute("Producer", producer, elementSpan(n)));
                }
                case "dataType" -> declarations.add(dataType(n));
                case "pou" -> declarations.add(pou(n));
                case "configuration" -> configuration(n);
                case "globalVars" -> globalVars(n, n.attr("name"));
                default -> {}
            }
        }

        // ---- data types ---------------------------------------------------------------------

        Declaration dataType(XNode n) {
            Identifier name = name(n, "name");
            XNode base = n.child("baseType");
            TypeRef type = typeOf(base, elementSpan(n));
            Initializer init = initializer(n.child("initialValue"), name.name());
            return new Declaration.TypeDecl(name, null, type, init, attributePragmas(n), elementSpan(n));
        }

        /** The type held by a {@code type}/{@code baseType}/{@code returnType} element (its first child). */
        TypeRef typeOf(XNode holder, Span fallback) {
            XNode t = holder == null ? null : holder.first();
            if (t == null) {
                return new TypeRef.Invalid(fallback);
            }
            Span s = elementSpan(t);
            return switch (t.tag) {
                case "derived" -> named(t.attr("name"), nameSpan(t, "name"));
                case "string", "wstring" -> {
                    String len = t.attr("length");
                    Expression length =
                            len == null || len.isBlank() ? null : expressions.literal(len, nameSpan(t, "length"));
                    yield new TypeRef.StringType(t.tag.equals("wstring"), length, s);
                }
                case "array" -> {
                    List<TypeRef.Dimension> dims = new ArrayList<>();
                    for (XNode d : t.children("dimension")) {
                        dims.add(new TypeRef.Dimension(
                                expressions.literal(nonNull(d.attr("lower")), nameSpan(d, "lower")),
                                expressions.literal(nonNull(d.attr("upper")), nameSpan(d, "upper")),
                                elementSpan(d)));
                    }
                    yield new TypeRef.ArrayType(dims, typeOf(t.child("baseType"), s), List.of(), s);
                }
                case "pointer" ->
                    new TypeRef.PointerType(TypeRef.PointerKind.POINTER, typeOf(t.child("baseType"), s), s);
                case "reference" ->
                    new TypeRef.PointerType(TypeRef.PointerKind.REFERENCE, typeOf(t.child("baseType"), s), s);
                case "struct" -> new TypeRef.StructType(variables(t, ""), s);
                case "enum" -> {
                    List<TypeRef.EnumValue> values = new ArrayList<>();
                    XNode vs = t.child("values");
                    if (vs != null) {
                        for (XNode v : vs.children("value")) {
                            String val = v.attr("value");
                            values.add(new TypeRef.EnumValue(
                                    name(v, "name"),
                                    val == null ? null : expressions.literal(val, nameSpan(v, "value")),
                                    elementSpan(v)));
                        }
                    }
                    TypeRef base = t.child("baseType") == null ? null : typeOf(t.child("baseType"), s);
                    yield new TypeRef.EnumType(values, base instanceof TypeRef.Named nb ? nb : null, s);
                }
                case "subrangeSigned", "subrangeUnsigned" -> {
                    XNode range = t.child("range");
                    TypeRef base = typeOf(t.child("baseType"), s);
                    if (base instanceof TypeRef.Named nb && range != null) {
                        yield new TypeRef.Named(
                                nb.name(),
                                List.of(),
                                expressions.literal(nonNull(range.attr("lower")), nameSpan(range, "lower")),
                                expressions.literal(nonNull(range.attr("upper")), nameSpan(range, "upper")),
                                null,
                                s);
                    }
                    yield base;
                }
                // elementary types: <BOOL/>, <INT/>, <TIME/>, … (TC6 writes them upper case)
                default -> named(t.tag, s);
            };
        }

        TypeRef.Named named(String name, Span s) {
            String n = name == null || name.isBlank() ? "<missing>" : name;
            List<Identifier> parts = new ArrayList<>();
            for (String p : n.split("\\.")) {
                parts.add(new Identifier(p, s));
            }
            return new TypeRef.Named(new QualifiedName(parts, s), List.of(), null, null, null, s);
        }

        // ---- variables -------------------------------------------------------------------------

        /** The {@code variable} children of a section, struct or global list. */
        List<VarDecl> variables(XNode holder, String scope) {
            List<VarDecl> out = new ArrayList<>();
            for (XNode v : holder.children("variable")) {
                Identifier name = name(v, "name");
                String address = v.attr("address");
                TypeRef type = typeOf(v.child("type"), elementSpan(v));
                Initializer init =
                        type instanceof TypeRef.StringType ? stringInitializer(v.child("initialValue")) : null;
                if (init == null) {
                    init = initializer(v.child("initialValue"), name.name());
                }
                out.add(new VarDecl(
                        name,
                        null,
                        address == null || address.isBlank() ? null : address,
                        type,
                        init,
                        false,
                        attributePragmas(v),
                        elementSpan(v)));
            }
            return out;
        }

        /** {@code simpleValue}, {@code arrayValue}, {@code structValue} of an {@code initialValue}/{@code value}. */
        Initializer initializer(XNode holder, String what) {
            XNode v = holder == null ? null : holder.first();
            if (v == null) {
                return null;
            }
            return switch (v.tag) {
                case "simpleValue" -> {
                    XNode.Attr a = v.attribute("value");
                    yield a == null ? null : expressions.attribute(a, elementSpan(v));
                }
                case "arrayValue" -> {
                    List<Initializer.Element> elements = new ArrayList<>();
                    for (XNode e : v.children("value")) {
                        int repeat = 1;
                        try {
                            repeat = e.attr("repetitionValue") == null
                                    ? 1
                                    : Integer.parseInt(e.attr("repetitionValue").strip());
                        } catch (NumberFormatException ignored) {
                            // keep 1
                        }
                        elements.add(new Initializer.Element(repeat, initializer(e, what), elementSpan(e)));
                    }
                    yield new Initializer.ArrayInit(elements, elementSpan(v));
                }
                case "structValue" -> {
                    List<Initializer.FieldInit> fields = new ArrayList<>();
                    for (XNode e : v.children("value")) {
                        fields.add(new Initializer.FieldInit(name(e, "member"), initializer(e, what), elementSpan(e)));
                    }
                    yield new Initializer.StructInit(fields, elementSpan(v));
                }
                default -> null;
            };
        }

        /** Beremiz writes string initial values without quotes ({@code value="Initial String"}). */
        Initializer stringInitializer(XNode holder) {
            XNode v = holder == null ? null : holder.first();
            XNode.Attr a = v != null && v.tag.equals("simpleValue") ? v.attribute("value") : null;
            if (a == null
                    || a.value().strip().startsWith("'")
                    || a.value().strip().startsWith("\"")) {
                return null;
            }
            Span s = a.start() >= 0 ? whole.span(a.start(), a.end()) : elementSpan(v);
            String quoted = "'" + a.value().replace("$", "$$").replace("'", "$'") + "'";
            return new Expression.Literal(Expression.LiteralKind.STRING, quoted, null, s);
        }

        /** The sections of a structured {@code interface}. */
        List<VarBlock> sections(XNode iface) {
            List<VarBlock> out = new ArrayList<>();
            if (iface == null) {
                return out;
            }
            for (XNode s : iface.children) {
                VarBlock.Section section = switch (s.tag) {
                    case "inputVars" -> VarBlock.Section.INPUT;
                    case "outputVars" -> VarBlock.Section.OUTPUT;
                    case "inOutVars" -> VarBlock.Section.IN_OUT;
                    case "localVars" -> VarBlock.Section.VAR;
                    case "tempVars" -> VarBlock.Section.TEMP;
                    case "externalVars" -> VarBlock.Section.EXTERNAL;
                    case "globalVars" -> VarBlock.Section.GLOBAL;
                    case "accessVars" -> VarBlock.Section.ACCESS;
                    default -> null;
                };
                if (section != null) {
                    out.add(block(s, section));
                }
            }
            return out;
        }

        VarBlock block(XNode s, VarBlock.Section section) {
            Set<VarBlock.Qualifier> q = EnumSet.noneOf(VarBlock.Qualifier.class);
            if (s.flag("constant")) {
                q.add(VarBlock.Qualifier.CONSTANT);
            }
            if (s.flag("retain")) {
                q.add(VarBlock.Qualifier.RETAIN);
            }
            if (s.flag("nonretain")) {
                q.add(VarBlock.Qualifier.NON_RETAIN);
            }
            if (s.flag("persistent")) {
                q.add(VarBlock.Qualifier.PERSISTENT);
            }
            return new VarBlock(section, q, variables(s, ""), List.of(), elementSpan(s));
        }

        void globalVars(XNode g, String listName) {
            VarBlock b = block(g, VarBlock.Section.GLOBAL);
            Identifier list = listName == null || listName.isBlank() ? null : name(g, "name");
            declarations.add(new Declaration.GlobalVars(list, b, b.span()));
        }

        // ---- POUs ------------------------------------------------------------------------------

        Pou pou(XNode n) {
            temps = 0;
            String type = nonNull(n.attr("pouType"));
            PouKind kind = switch (type) {
                case "program" -> PouKind.PROGRAM;
                case "function" -> PouKind.FUNCTION;
                case "functionBlock" -> PouKind.FUNCTION_BLOCK;
                default -> {
                    sink.report(Diagnostic.warning(
                            PlcOpenCodes.UNKNOWN_POU_TYPE,
                            "POU '" + n.attr("name") + "' has pouType '" + type + "'; read as a function block",
                            elementSpan(n)));
                    yield PouKind.FUNCTION_BLOCK;
                }
            };
            return pouLike(n, kind, true);
        }

        /**
         * A POU or member with an {@code interface} and a {@code body}: the CODESYS plain-text interface when present
         * (decision 1), else the structured one.
         */
        Pou pouLike(XNode n, PouKind kind, boolean withMembers) {
            XNode iface = n.child("interface");
            StTexts.Declarations plain = plainInterface(iface, n);
            List<Pou> members = withMembers ? members(n) : new ArrayList<>();
            Identifier name =
                    plain != null && plain.header() != null ? plain.header().name() : name(n, "name");
            List<VarBlock> blocks;
            TypeRef returnType;
            List<QualifiedName> extendsTypes;
            List<QualifiedName> implementsTypes;
            Set<Declaration.Modifier> modifiers;
            List<Pragma> attrs = new ArrayList<>(attributePragmas(iface));
            if (plain != null) {
                StTexts.Header h = plain.header();
                blocks = plain.varBlocks();
                returnType = h != null ? h.type() : iface == null ? null : nullableType(iface.child("returnType"));
                extendsTypes = h != null ? h.extendsTypes() : List.of();
                implementsTypes = h != null ? h.implementsTypes() : List.of();
                modifiers = h != null ? h.modifiers() : plain.modifiers();
                if (h != null) {
                    attrs.addAll(h.pragmas());
                }
            } else {
                blocks = sections(iface);
                returnType = iface == null ? null : nullableType(iface.child("returnType"));
                extendsTypes = inheritance(iface, "Extends");
                implementsTypes = inheritance(iface, "Implements");
                modifiers = Set.of();
            }
            Bodies.Translated body = Bodies.translate(this, n, name.name(), blocks);
            List<VarBlock> allBlocks = new ArrayList<>(blocks);
            allBlocks.addAll(body.extraBlocks());
            return new Pou(
                    kind,
                    name,
                    modifiers,
                    returnType,
                    extendsTypes,
                    implementsTypes,
                    allBlocks,
                    members,
                    body.statements(),
                    body.language(),
                    attrs,
                    elementSpan(n));
        }

        private TypeRef nullableType(XNode holder) {
            return holder == null || holder.first() == null ? null : typeOf(holder, elementSpan(holder));
        }

        /** CODESYS {@code InterfaceAsPlainText}: the declaration as source text, parsed like a TwinCAT declaration. */
        StTexts.Declarations plainInterface(XNode iface, XNode owner) {
            XNode text = iface == null ? null : find(iface, "InterfaceAsPlainText");
            if (text == null) {
                text = find(owner, "InterfaceAsPlainText"); // methods carry it next to their interface
            }
            XNode content = text == null ? null : textElement(text);
            if (content == null || content.contentStart < 0) {
                return null;
            }
            SourceText t = registry.registerXmlContent(file, content.contentStart, content.contentEnd);
            if (t.text().isBlank()) {
                return null;
            }
            return st.declarations(t, sink, comments, pragmas);
        }

        /** CODESYS {@code pouinheritance} addData: {@code <Inheritance><Extends>B</Extends>…}. */
        private List<QualifiedName> inheritance(XNode iface, String kind) {
            List<QualifiedName> out = new ArrayList<>();
            XNode inh = iface == null ? null : find(iface, "Inheritance");
            if (inh != null) {
                for (XNode e : inh.children(kind)) {
                    String text = text(e).strip();
                    if (!text.isEmpty()) {
                        out.add(named(text, elementSpan(e)).name());
                    }
                }
            }
            return out;
        }

        /** Standard actions and transitions, CODESYS methods and properties. */
        List<Pou> members(XNode n) {
            List<Pou> out = new ArrayList<>();
            XNode actions = n.child("actions");
            if (actions != null) {
                for (XNode a : actions.children("action")) {
                    out.add(pouLike(a, PouKind.ACTION, false));
                }
            }
            XNode transitions = n.child("transitions");
            if (transitions != null) {
                for (XNode t : transitions.children("transition")) {
                    out.add(pouLike(t, PouKind.TRANSITION, false));
                }
            }
            XNode add = n.child("addData");
            if (add != null) {
                for (XNode data : add.children("data")) {
                    for (XNode m : data.children) {
                        switch (m.tag) {
                            case "Method" -> out.add(pouLike(m, PouKind.METHOD, false));
                            case "Property" -> out.add(property(m));
                            default -> {}
                        }
                    }
                }
            }
            return out;
        }

        Pou property(XNode p) {
            XNode iface = p.child("interface");
            List<Pou> accessors = new ArrayList<>();
            for (String tag : List.of("GetAccessor", "SetAccessor")) {
                XNode a = p.child(tag);
                if (a != null) {
                    Pou acc = pouLike(a, tag.startsWith("Get") ? PouKind.PROPERTY_GET : PouKind.PROPERTY_SET, false);
                    accessors.add(new Pou(
                            acc.kind(),
                            new Identifier(tag.startsWith("Get") ? "GET" : "SET", elementSpan(a)),
                            acc.modifiers(),
                            null,
                            List.of(),
                            List.of(),
                            acc.varBlocks(),
                            List.of(),
                            acc.body(),
                            acc.bodyLanguage(),
                            acc.pragmas(),
                            acc.span()));
                }
            }
            return new Pou(
                    PouKind.PROPERTY,
                    name(p, "name"),
                    Set.of(),
                    iface == null ? null : nullableType(iface.child("returnType")),
                    List.of(),
                    List.of(),
                    List.of(),
                    accessors,
                    null,
                    null,
                    List.of(),
                    elementSpan(p));
        }

        // ---- configurations -----------------------------------------------------------------------

        void configuration(XNode c) {
            List<VarBlock> vars = new ArrayList<>();
            for (XNode g : c.children("globalVars")) {
                vars.add(block(g, VarBlock.Section.GLOBAL));
            }
            List<Declaration.ConfigElement> elements = new ArrayList<>();
            for (XNode r : c.children("resource")) {
                List<VarBlock> rvars = new ArrayList<>();
                for (XNode g : r.children("globalVars")) {
                    rvars.add(block(g, VarBlock.Section.GLOBAL));
                }
                List<Declaration.ConfigElement> inner = new ArrayList<>();
                for (XNode t : r.children("task")) {
                    inner.add(task(t));
                    for (XNode pi : t.children("pouInstance")) {
                        Identifier inst = name(pi, "name");
                        Identifier task = name(t, "name");
                        inner.add(new Declaration.ProgramInstance(
                                inst,
                                task,
                                named(pi.attr("typeName"), nameSpan(pi, "typeName"))
                                        .name(),
                                List.of(),
                                elementSpan(pi)));
                    }
                }
                for (XNode pi : r.children("pouInstance")) {
                    inner.add(new Declaration.ProgramInstance(
                            name(pi, "name"),
                            null,
                            named(pi.attr("typeName"), nameSpan(pi, "typeName")).name(),
                            List.of(),
                            elementSpan(pi)));
                }
                elements.add(new Declaration.Resource(name(r, "name"), null, rvars, inner, elementSpan(r)));
            }
            declarations.add(new Declaration.Configuration(name(c, "name"), vars, elements, elementSpan(c)));
        }

        Declaration.Task task(XNode t) {
            List<Expression.Argument> settings = new ArrayList<>();
            String priority = t.attr("priority");
            if (priority != null && !priority.isBlank()) {
                settings.add(setting("PRIORITY", expressions.literal(priority, nameSpan(t, "priority"))));
            }
            XNode.Attr interval = t.attribute("interval");
            if (interval == null || interval.value().isBlank()) {
                XNode ts = find(t, "TaskSettings"); // CODESYS keeps the interval in addData
                interval = ts == null ? null : ts.attribute("Interval");
            }
            if (interval != null && !interval.value().isBlank()) {
                String iso = isoDuration(interval.value());
                Span s = interval.start() >= 0 ? whole.span(interval.start(), interval.end()) : elementSpan(t);
                settings.add(setting(
                        "INTERVAL",
                        iso != null
                                ? new Expression.Literal(Expression.LiteralKind.TIME, iso, "T", s)
                                : expressions.attribute(interval, elementSpan(t))));
            }
            return new Declaration.Task(name(t, "name"), settings, elementSpan(t));
        }

        private Expression.Argument setting(String name, Expression value) {
            return new Expression.Argument(
                    Expression.ArgumentKind.INPUT, new Identifier(name, value.span()), false, value, value.span());
        }

        // ---- shared helpers ------------------------------------------------------------------------

        /** CODESYS {@code attributes} addData: {@code <Attribute Name Value/>} → attribute pragmas. */
        List<Pragma> attributePragmas(XNode holder) {
            List<Pragma> out = new ArrayList<>();
            XNode attrs = holder == null ? null : find(holder, "Attributes");
            if (attrs != null) {
                for (XNode a : attrs.children("Attribute")) {
                    out.add(attribute(nonNull(a.attr("Name")), a.attr("Value"), elementSpan(a)));
                }
            }
            return out;
        }

        /** The first descendant named {@code tag} within {@code addData} children (CODESYS extensions). */
        XNode find(XNode n, String tag) {
            if (n == null) {
                return null;
            }
            XNode add = n.child("addData");
            if (add == null) {
                return null;
            }
            for (XNode data : add.children("data")) {
                XNode hit = data.child(tag);
                if (hit != null) {
                    return hit;
                }
            }
            return null;
        }

        /** The {@code xhtml}/{@code p} element holding text inside {@code n} (or {@code n} if none). */
        XNode textElement(XNode n) {
            for (XNode c : n.children) {
                if (c.tag.equals("xhtml") || c.tag.equals("p")) {
                    return c;
                }
            }
            return n;
        }

        String text(XNode n) {
            if (n == null || n.contentStart < 0) {
                return "";
            }
            return XmlText.decode(file.text(), n.contentStart, n.contentEnd).text();
        }

        Identifier name(XNode n, String attr) {
            XNode.Attr a = n.attribute(attr);
            String v = a == null || a.value().isBlank() ? "<missing>" : a.value();
            return new Identifier(v, nameSpan(n, attr));
        }

        Span nameSpan(XNode n, String attr) {
            XNode.Attr a = n.attribute(attr);
            if (a != null && a.start() >= 0) {
                return whole.span(a.start(), a.end());
            }
            return Span.at(
                    whole.id(), Math.max(0, Math.min(n.offset, file.text().length())));
        }

        Span elementSpan(XNode n) {
            int s = Math.max(0, Math.min(n.offset, file.text().length()));
            return whole.span(s, XmlInput.endOfStartTag(file.text(), s));
        }

        Pragma attribute(String name, String value, Span span) {
            return new Pragma(Pragma.Kind.ATTRIBUTE, name, value, name + "=\"" + value + "\"", span);
        }

        StTexts st() {
            return st;
        }

        void info(String code, String message, Span span) {
            sink.report(Diagnostic.of(code, Severity.INFO, message, span));
        }

        void internal(String message) {
            sink.report(Diagnostic.of(SyntaxCodes.INTERNAL, Severity.ERROR, message, Span.at(whole.id(), 0)));
        }

        String nextTemp(String prefix) {
            return prefix + (++temps);
        }
    }

    /** TC6 2.01 writes task intervals as {@code xsd:duration}: {@code PT0.02S} → {@code 20ms}; null otherwise. */
    static String isoDuration(String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(?i)P(?:(\\d+)D)?T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?")
                .matcher(v.strip());
        if (!m.matches()) {
            return null;
        }
        double ms = 0;
        double[] scale = {86_400_000, 3_600_000, 60_000, 1000};
        for (int g = 1; g <= 4; g++) {
            if (m.group(g) != null) {
                ms += Double.parseDouble(m.group(g)) * scale[g - 1];
            }
        }
        return (ms == Math.rint(ms) ? Long.toString((long) ms) : Double.toString(ms)) + "ms";
    }

    static String nonNull(String s) {
        return s == null ? "" : s;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
