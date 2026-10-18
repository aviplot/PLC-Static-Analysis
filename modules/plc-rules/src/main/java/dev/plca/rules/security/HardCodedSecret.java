package dev.plca.rules.security;

import dev.plca.ast.Declaration;
import dev.plca.ast.Expression;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.Expression.LiteralKind;
import dev.plca.ast.Initializer;
import dev.plca.ast.Statement;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import dev.plca.rules.ProjectContext;
import dev.plca.rules.ProjectRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.Statements;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLCA-S001: credentials and IP addresses written into the program as string literals.
 *
 * <ul>
 *   <li><b>Credential</b>: a non-empty string literal assigned to, initialising, passed as, or compared with a
 *       variable/parameter whose name looks like a secret ({@code namePattern}: password, pwd, secret, token, …).
 *   <li><b>IP address</b>: a string literal that is exactly an IPv4 address (optionally with {@code :port}), except
 *       loopback, the unspecified/broadcast addresses and netmasks; more can be allowed with {@code allowedAddresses}.
 * </ul>
 *
 * Project rule because initial values in GVLs matter as much as code in bodies.
 */
public final class HardCodedSecret implements ProjectRule {

    private static final Pattern IPV4 =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?::\\d{1,5})?$");

    @Override
    public String id() {
        return "PLCA-S001";
    }

    @Override
    public void check(ProjectContext ctx) {
        new Scan(ctx).run();
    }

    private static final class Scan {
        final ProjectContext ctx;
        final Pattern secretName;
        final Set<String> allowed;
        /** Literals already reported as credentials (not reported again as IP addresses). */
        final Set<Expression> reported = Collections.newSetFromMap(new IdentityHashMap<>());

        final Map<Declaration.Pou, PouSymbol> pous = new IdentityHashMap<>();

        Scan(ProjectContext ctx) {
            this.ctx = ctx;
            this.secretName = ctx.patternParam("namePattern");
            this.allowed = new HashSet<>(ctx.listParam("allowedAddresses"));
            ctx.model().pous().forEach(p -> {
                if (p.declaration() != null) {
                    pous.put(p.declaration(), p);
                }
            });
        }

        void run() {
            for (var unit : ctx.model().units()) {
                unit.declarations().forEach(this::declaration);
            }
        }

        private void declaration(Declaration d) {
            switch (d) {
                case Declaration.Pou p -> pou(p);
                case Declaration.GlobalVars g -> block(g.block(), null);
                case Declaration.Namespace n -> n.members().forEach(this::declaration);
                default -> {}
            }
        }

        private void pou(Declaration.Pou d) {
            PouSymbol p = pous.get(d);
            d.varBlocks().forEach(b -> block(b, p));
            Statements.forEachDeep(d.body(), s -> statement(s, p));
            Walk.expressions(d.body(), e -> expression(e, p));
            d.members().forEach(this::pou);
        }

        private void block(VarBlock b, PouSymbol p) {
            for (VarDecl v : b.variables()) {
                if (isSecretName(v.name().name()) && v.init() instanceof Expression e) {
                    credential(e, v.name().name(), p);
                }
                initializer(v.init(), p);
            }
        }

        private void initializer(Initializer init, PouSymbol p) {
            Walk.expressions(init, e -> expression(e, p));
        }

        private void statement(Statement s, PouSymbol p) {
            if (s instanceof Statement.Assignment a && isSecretName(Walk.lastName(a.target()))) {
                credential(a.value(), Walk.lastName(a.target()), p);
            }
        }

        /** Arguments and comparisons with secret-named counterparts, then IP literals. */
        private void expression(Expression e, PouSymbol p) {
            switch (e) {
                case Expression.Call c -> {
                    for (Expression.Argument a : c.arguments()) {
                        if (a.name() != null && isSecretName(a.name().name())) {
                            credential(a.value(), a.name().name(), p);
                        }
                    }
                }
                case Expression.Binary b
                when b.op() == BinaryOp.EQ || b.op() == BinaryOp.NE -> {
                    if (isSecretName(Walk.lastName(b.left()))) {
                        credential(b.right(), Walk.lastName(b.left()), p);
                    }
                    if (isSecretName(Walk.lastName(b.right()))) {
                        credential(b.left(), Walk.lastName(b.right()), p);
                    }
                }
                case Expression.Literal l
                when isString(l) && !reported.contains(l) -> {
                    String value = StringLiterals.decode(l.text());
                    if (isIpAddress(value)) {
                        ctx.report(
                                p,
                                l.span(),
                                "Hard-coded IP address '" + value + "'; read it from configuration instead",
                                List.of());
                    }
                }
                default -> {}
            }
        }

        private void credential(Expression value, String name, PouSymbol p) {
            if (value instanceof Expression.Literal l
                    && isString(l)
                    && !StringLiterals.decode(l.text()).isEmpty()
                    && reported.add(l)) {
                ctx.report(
                        p,
                        l.span(),
                        "Hard-coded credential: string literal used for '" + name
                                + "'; keep secrets out of the PLC program",
                        List.of());
            }
        }

        private boolean isSecretName(String name) {
            return name != null && secretName.matcher(name).find();
        }

        private boolean isIpAddress(String s) {
            Matcher m = IPV4.matcher(s);
            if (!m.matches() || allowed.contains(s)) {
                return false;
            }
            long bits = 0;
            for (int i = 1; i <= 4; i++) {
                int octet = Integer.parseInt(m.group(i));
                if (octet > 255) {
                    return false;
                }
                bits = bits << 8 | octet;
            }
            String host = s.contains(":") ? s.substring(0, s.indexOf(':')) : s;
            if (allowed.contains(host) || host.startsWith("127.")) {
                return false;
            }
            // netmasks: ones followed by zeros (also covers 0.0.0.0 and 255.255.255.255)
            long inverted = ~bits & 0xFFFFFFFFL;
            return (inverted & (inverted + 1)) != 0;
        }

        private static boolean isString(Expression.Literal l) {
            return l.kind() == LiteralKind.STRING || l.kind() == LiteralKind.WSTRING;
        }
    }
}
