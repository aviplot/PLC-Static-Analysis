package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.List;
import java.util.Set;

/** Top-level (library) elements of a source unit. */
public sealed interface Declaration extends Node {

    enum PouKind {
        PROGRAM,
        FUNCTION,
        FUNCTION_BLOCK,
        CLASS,
        INTERFACE,
        METHOD,
        PROPERTY,
        PROPERTY_GET,
        PROPERTY_SET,
        ACTION,
        TRANSITION,
        /** Siemens OB: a cyclic or event entry point, called by the operating system. */
        ORGANIZATION_BLOCK
    }

    enum Modifier {
        PUBLIC,
        PRIVATE,
        PROTECTED,
        INTERNAL,
        ABSTRACT,
        FINAL,
        OVERRIDE
    }

    /**
     * A program organisation unit or a member of one (method, property and its accessors, action).
     *
     * <p>{@code body} is null when the POU has none (interface members, abstract methods, declarations only) or when it
     * is written in a language we do not parse; then {@code bodyLanguage} names that language (e.g. {@code "LD"},
     * TwinCAT {@code "NWL"}). {@code returnType} is the result type of functions/methods and the type of properties.
     * {@code info} holds the Siemens block header ({@code TITLE}, {@code VERSION}, …); null for other dialects.
     */
    record Pou(
            PouKind kind,
            Identifier name,
            Set<Modifier> modifiers,
            TypeRef returnType,
            List<QualifiedName> extendsTypes,
            List<QualifiedName> implementsTypes,
            List<VarBlock> varBlocks,
            List<Pou> members,
            List<Statement> body,
            String bodyLanguage,
            List<Pragma> pragmas,
            BlockInfo info,
            Span span)
            implements Declaration {

        public Pou(
                PouKind kind,
                Identifier name,
                Set<Modifier> modifiers,
                TypeRef returnType,
                List<QualifiedName> extendsTypes,
                List<QualifiedName> implementsTypes,
                List<VarBlock> varBlocks,
                List<Pou> members,
                List<Statement> body,
                String bodyLanguage,
                List<Pragma> pragmas,
                Span span) {
            this(
                    kind,
                    name,
                    modifiers,
                    returnType,
                    extendsTypes,
                    implementsTypes,
                    varBlocks,
                    members,
                    body,
                    bodyLanguage,
                    pragmas,
                    null,
                    span);
        }

        public Pou {
            modifiers = Set.copyOf(modifiers);
            extendsTypes = List.copyOf(extendsTypes);
            implementsTypes = List.copyOf(implementsTypes);
            varBlocks = List.copyOf(varBlocks);
            members = List.copyOf(members);
            body = body == null ? null : List.copyOf(body);
            pragmas = List.copyOf(pragmas);
        }

        public boolean has(Modifier m) {
            return modifiers.contains(m);
        }
    }

    /** One entry of a {@code TYPE … END_TYPE} block; {@code info} is the Siemens UDT header, else null. */
    record TypeDecl(
            Identifier name,
            QualifiedName extendsType,
            TypeRef type,
            Initializer init,
            List<Pragma> pragmas,
            BlockInfo info,
            Span span)
            implements Declaration {
        public TypeDecl {
            pragmas = List.copyOf(pragmas);
        }

        public TypeDecl(
                Identifier name,
                QualifiedName extendsType,
                TypeRef type,
                Initializer init,
                List<Pragma> pragmas,
                Span span) {
            this(name, extendsType, type, init, pragmas, null, span);
        }
    }

    /**
     * The header of a Siemens block (TIA "Structure of an external source file"). Absent attributes are null.
     * {@code retention} is the DB-level {@code RETAIN}/{@code NON_RETAIN}.
     */
    record BlockInfo(
            String title,
            String author,
            String family,
            String name,
            String version,
            boolean knowHowProtected,
            boolean unlinked,
            Retention retention,
            Span span)
            implements Node {

        public enum Retention {
            UNSPECIFIED,
            RETAIN,
            NON_RETAIN
        }
    }

    /**
     * A Siemens data block. Exactly one layout is given: its own {@code STRUCT} ({@code layout}), its own VAR sections
     * ({@code varBlocks}), or {@code basedOn} — an FB (instance DB) or a UDT (typed DB), which only the semantic model
     * can tell apart. {@code initialValues} are the assignments after {@code BEGIN}.
     */
    record DataBlock(
            Identifier name,
            TypeRef layout,
            List<VarBlock> varBlocks,
            QualifiedName basedOn,
            List<Statement> initialValues,
            List<Pragma> pragmas,
            BlockInfo info,
            Span span)
            implements Declaration {
        public DataBlock {
            varBlocks = List.copyOf(varBlocks);
            initialValues = List.copyOf(initialValues);
            pragmas = List.copyOf(pragmas);
        }
    }

    /**
     * A VAR section outside any POU: a global variable list (GVL), or a stray block in a fragment file.
     * {@code listName} is the TwinCAT GVL name (needed for {@code {attribute 'qualified_only'}} access), else null.
     */
    record GlobalVars(Identifier listName, VarBlock block, Span span) implements Declaration {}

    record Namespace(QualifiedName name, List<Declaration> members, Span span) implements Declaration {
        public Namespace {
            members = List.copyOf(members);
        }
    }

    record Using(List<QualifiedName> names, Span span) implements Declaration {
        public Using {
            names = List.copyOf(names);
        }
    }

    /** IEC configuration: resources, tasks and program instances (inputs for the task model). */
    record Configuration(Identifier name, List<VarBlock> varBlocks, List<ConfigElement> elements, Span span)
            implements Declaration {
        public Configuration {
            varBlocks = List.copyOf(varBlocks);
            elements = List.copyOf(elements);
        }
    }

    sealed interface ConfigElement extends Node {}

    record Resource(
            Identifier name, QualifiedName on, List<VarBlock> varBlocks, List<ConfigElement> elements, Span span)
            implements ConfigElement {
        public Resource {
            varBlocks = List.copyOf(varBlocks);
            elements = List.copyOf(elements);
        }
    }

    /** {@code TASK t(INTERVAL := T#10ms, PRIORITY := 1);} */
    record Task(Identifier name, List<Expression.Argument> settings, Span span) implements ConfigElement {
        public Task {
            settings = List.copyOf(settings);
        }
    }

    /** {@code PROGRAM inst WITH task : Type(…);} */
    record ProgramInstance(
            Identifier name, Identifier task, QualifiedName type, List<Expression.Argument> arguments, Span span)
            implements ConfigElement {
        public ProgramInstance {
            arguments = List.copyOf(arguments);
        }
    }
}
