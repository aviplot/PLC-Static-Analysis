package dev.plca.semantic;

import dev.plca.ast.Node;
import dev.plca.ast.ladder.InstructionSet;

/**
 * A Rockwell instruction used as a call ({@code TON(t, …)}, {@code COP(a, b, n)}, {@code JSR(r, 0)}), resolved only in
 * POUs of dialect ROCKWELL. {@code definition} is null for an instruction neither the instruction table nor the project
 * knows: its operands then count as possibly read or written ({@link Access.Kind#UNKNOWN}).
 */
public record InstructionSymbol(String name, InstructionSet.Instruction definition) implements Symbol {
    @Override
    public Node declaration() {
        return null;
    }

    public boolean isKnown() {
        return definition != null;
    }
}
