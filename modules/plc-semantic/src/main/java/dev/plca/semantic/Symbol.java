package dev.plca.semantic;

import dev.plca.ast.Node;

/** Something a name can refer to. */
public sealed interface Symbol
        permits PouSymbol,
                VariableSymbol,
                TypeSymbol,
                EnumValueSymbol,
                GvlSymbol,
                BuiltinFunction,
                LibrarySymbol,
                InstructionSymbol,
                IoTagSymbol {

    /** Name as declared. */
    String name();

    /** The declaring AST node, or null for builtins and library symbols. */
    Node declaration();
}
