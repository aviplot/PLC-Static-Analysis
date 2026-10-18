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
        TRANSITION
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
            Span span)
            implements Declaration {

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

    /** One entry of a {@code TYPE … END_TYPE} block. */
    record TypeDecl(
            Identifier name, QualifiedName extendsType, TypeRef type, Initializer init, List<Pragma> pragmas, Span span)
            implements Declaration {
        public TypeDecl {
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
