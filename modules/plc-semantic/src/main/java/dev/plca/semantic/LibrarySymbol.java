package dev.plca.semantic;

import dev.plca.ast.Node;

/**
 * A name the project does not define, typically from a library that is not part of the export
 * ({@code Tc2_MC2.MC_Power}, {@code Tc3_EventLogger.FB_TcMessage}). Rules treat it as "unknown, don't report".
 */
public record LibrarySymbol(String name) implements Symbol {
    @Override
    public Node declaration() {
        return null;
    }
}
