package dev.plca.semantic;

import dev.plca.ast.Node;

/**
 * A Rockwell tag created by the I/O configuration or the controller: module tags ({@code Local:1:I}, {@code Rack1:2:O},
 * {@code Valve_IO:I1}) and status flags ({@code S:FS}, {@code S:N}). Their layout comes from the module profile, which
 * the export does not contain, so members are not resolved. {@code area} is the last {@code :}-part's letter:
 * {@code I}nput, {@code O}utput, {@code C}onfiguration, {@code S}tatus; {@code status} marks controller flags.
 */
public record IoTagSymbol(String name, char area, boolean status) implements Symbol {

    /** Parses a tag name with colons; null when {@code name} has none. */
    public static IoTagSymbol of(String name) {
        int colon = name.indexOf(':');
        if (colon < 0) {
            return null;
        }
        boolean status = colon == 1 && Character.toUpperCase(name.charAt(0)) == 'S';
        String last = name.substring(name.lastIndexOf(':') + 1);
        char area = last.isEmpty() ? '?' : Character.toUpperCase(last.charAt(0));
        return new IoTagSymbol(name, status ? 'S' : area, status);
    }

    public boolean isOutput() {
        return !status && area == 'O';
    }

    @Override
    public Node declaration() {
        return null;
    }
}
