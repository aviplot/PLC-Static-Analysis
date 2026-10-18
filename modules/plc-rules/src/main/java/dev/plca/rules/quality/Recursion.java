package dev.plca.rules.quality;

import dev.plca.ast.Expression;
import dev.plca.ast.VarBlock;
import dev.plca.core.diag.Diagnostic;
import dev.plca.rules.ProjectContext;
import dev.plca.rules.ProjectRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.CallSite;
import dev.plca.semantic.ElementaryKind;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Type;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PLCA-Q007 (PLCopen CP13): a POU that calls itself, directly or through other POUs. One finding per cycle. Cycles
 * through a name declared more than once (SIMATIC AX overloads, duplicate copies) are skipped: the call target is
 * ambiguous.
 */
public final class Recursion implements ProjectRule {

    @Override
    public String id() {
        return "PLCA-Q007";
    }

    @Override
    public void check(ProjectContext ctx) {
        Map<String, Integer> declared = new HashMap<>();
        ctx.model().pous().forEach(p -> declared.merge(p.qualifiedName().toLowerCase(Locale.ROOT), 1, Integer::sum));
        for (List<PouSymbol> cycle : ctx.model().callGraph().cycles()) {
            // overloads (SIMATIC AX) or duplicate copies: which declaration a call means is not known
            if (cycle.stream().anyMatch(p -> declared.get(p.qualifiedName().toLowerCase(Locale.ROOT)) > 1)) {
                continue;
            }
            List<CallSite> edges = new ArrayList<>();
            for (int i = 0; i < cycle.size(); i++) {
                PouSymbol from = cycle.get(i);
                PouSymbol to = cycle.get((i + 1) % cycle.size());
                ctx.model().callGraph().callsFrom(from).stream()
                        .filter(c -> c.callee() == to)
                        .findFirst()
                        .ifPresent(edges::add);
            }
            if (edges.isEmpty() || cycle.size() == 1 && !fitsOwnSignature(ctx.model(), edges.getFirst())) {
                continue;
            }
            CallSite first = edges.getFirst();
            String message;
            if (cycle.size() == 1) {
                message = cycle.getFirst().qualifiedName() + " calls itself (recursion)";
            } else if (edges.size() < cycle.size()) {
                // a strongly connected component that is not a simple ring
                message = "Recursive calls among "
                        + cycle.stream().map(PouSymbol::qualifiedName).collect(Collectors.joining(", "));
            } else {
                message = "Recursive call cycle: "
                        + cycle.stream().map(PouSymbol::qualifiedName).collect(Collectors.joining(" -> "))
                        + " -> " + cycle.getFirst().qualifiedName();
            }
            List<Diagnostic.Related> related = edges.subList(1, edges.size()).stream()
                    .map(c -> new Diagnostic.Related(
                            c.span(),
                            c.caller().qualifiedName() + " calls " + c.callee().qualifiedName()))
                    .toList();
            ctx.report(first.caller(), first.span(), message, related);
        }
    }

    /**
     * A self-call whose arguments do not fit the POU's own parameters (too many positional arguments, BOOL passed to a
     * STRING input) calls an overload of the same name (SIMATIC AX) from a library, not itself.
     */
    private static boolean fitsOwnSignature(SemanticModel m, CallSite site) {
        Expression.Call call = findCall(site);
        if (call == null) {
            return true;
        }
        PouSymbol p = site.callee();
        long inputs = p.variables().values().stream()
                .filter(v -> v.section() == VarBlock.Section.INPUT || v.section() == VarBlock.Section.IN_OUT)
                .count();
        long positional = call.arguments().stream()
                .filter(a -> a.kind() == Expression.ArgumentKind.POSITIONAL)
                .count();
        if (positional > inputs) {
            return false;
        }
        for (Expression.Argument a : call.arguments()) {
            VariableSymbol param = m.parameter(a).orElse(null);
            if (param != null && a.value() != null && a.kind() != Expression.ArgumentKind.OUTPUT) {
                String pc = category(param.type());
                String ac = category(m.type(a.value()));
                if (pc != null && ac != null && !pc.equals(ac)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Coarse type classes for the overload check; null when unknown. */
    private static String category(Type t) {
        Type r = t.resolved();
        if (r instanceof Type.StringType) {
            return "string";
        }
        if (r instanceof Type.IntLiteral || r instanceof Type.RealLiteral) {
            return "number";
        }
        ElementaryKind k = Type.elementary(r);
        if (k == null) {
            return null;
        }
        return switch (k.category()) {
            case BOOL -> "bool";
            case SIGNED, UNSIGNED, REAL, BIT_STRING -> "number";
            default -> null;
        };
    }

    private static Expression.Call findCall(CallSite site) {
        if (site.caller().declaration() == null) {
            return null;
        }
        Expression.Call[] found = {null};
        Walk.expressions(site.caller().declaration().body(), e -> {
            if (e instanceof Expression.Call c && c.span().equals(site.span())) {
                found[0] = c;
            }
        });
        return found[0];
    }
}
