package dev.plca.semantic;

import dev.plca.core.Dialect;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rockwell alias tags ({@code TagType="Alias" AliasFor="Arr[3].Run"}): the alias shares its base tag's storage, so
 * reads and writes through it are accesses of the base at the alias's path ({@link VariableSymbol#overlayOf()},
 * {@link VariableSymbol#overlayPath()}). An alias of a module tag ({@code Local:2:O.Data.0}) is an I/O point
 * ({@link VariableSymbol#ioAlias()}). Aliases of aliases are followed to the base.
 */
final class RockwellAliases {

    private static final Pattern SELECTOR = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_]*)|\\.(\\d+)|\\[([^\\]]*)\\]");
    private static final int MAX_CHAIN = 16;

    private final SemanticModel model;
    private final GlobalScope global;
    private final List<VariableSymbol> aliases = new ArrayList<>();

    RockwellAliases(SemanticModel model, GlobalScope global) {
        this.model = model;
        this.global = global;
    }

    /** Links every alias to its base (after all declarations, before types are resolved). */
    void link() {
        for (PouSymbol p : model.pous) {
            if (p.dialect() == Dialect.ROCKWELL) {
                for (VariableSymbol v : p.variables().values()) {
                    link(v, p);
                }
            }
        }
        for (GvlSymbol g : global.gvls()) {
            for (VariableSymbol v : g.variables().values()) {
                link(v, null);
            }
        }
        // aliases of aliases: point at the final base
        for (VariableSymbol v : aliases) {
            VariableSymbol base = v.overlayOf();
            String path = v.overlayPath();
            for (int k = 0; base != null && base.overlayOf() != null && k < MAX_CHAIN; k++) {
                path = base.overlayPath() + path;
                base = base.overlayOf();
            }
            if (base != null && base != v) {
                v.setOverlay(base, path);
                if (base.ioAlias() != null) {
                    v.setIoAlias(base.ioAlias());
                }
            }
        }
    }

    private void link(VariableSymbol v, PouSymbol scope) {
        if (v.declaration() == null || v.declaration().overlay() == null) {
            return;
        }
        String target = v.declaration().overlay().name();
        int end = rootEnd(target);
        String root = target.substring(0, end);
        String path = target.substring(end);
        IoTagSymbol io = IoTagSymbol.of(root);
        if (io != null) {
            v.setIoAlias(io);
            return;
        }
        Optional<VariableSymbol> base = scope == null ? Optional.empty() : scope.findVariable(root);
        if (base.isEmpty()) {
            base = global.globalVariable(root);
        }
        base.filter(b -> b != v).ifPresent(b -> {
            v.setOverlay(b, path);
            aliases.add(v);
        });
    }

    /** End of the root tag name: the first {@code .} or {@code [} (module names keep their colons). */
    private static int rootEnd(String path) {
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.' || c == '[') {
                return i;
            }
        }
        return path.length();
    }

    /** Gives every alias the type at its path (after types are resolved). */
    void typeAliases() {
        for (VariableSymbol v : aliases) {
            v.setType(typeAt(v.overlayOf().type(), v.overlayPath()));
        }
    }

    private static Type typeAt(Type t, String path) {
        Matcher m = SELECTOR.matcher(path);
        int at = 0;
        while (at < path.length() && m.find(at) && m.start() == at) {
            Type r = t.resolved();
            if (m.group(1) != null) {
                Symbol member = r instanceof Type.Struct s
                        ? s.symbol().findMember(m.group(1)).orElse(null)
                        : r instanceof Type.Instance i
                                ? i.pou().findVariable(m.group(1)).orElse(null)
                                : null;
                if (!(member instanceof VariableSymbol mv)) {
                    return Type.UNKNOWN;
                }
                t = mv.type();
            } else if (m.group(2) != null) {
                t = Type.BOOL;
            } else {
                if (!(r instanceof Type.Array a)) {
                    return Type.UNKNOWN;
                }
                t = a.element();
            }
            at = m.end();
        }
        return at == path.length() ? t : Type.UNKNOWN;
    }
}
