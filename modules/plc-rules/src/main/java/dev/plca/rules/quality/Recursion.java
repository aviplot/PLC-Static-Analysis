package dev.plca.rules.quality;

import dev.plca.core.diag.Diagnostic;
import dev.plca.rules.ProjectContext;
import dev.plca.rules.ProjectRule;
import dev.plca.semantic.CallSite;
import dev.plca.semantic.PouSymbol;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** PLCA-Q007 (PLCopen CP13): a POU that calls itself, directly or through other POUs. One finding per cycle. */
public final class Recursion implements ProjectRule {

    @Override
    public String id() {
        return "PLCA-Q007";
    }

    @Override
    public void check(ProjectContext ctx) {
        for (List<PouSymbol> cycle : ctx.model().callGraph().cycles()) {
            List<CallSite> edges = new ArrayList<>();
            for (int i = 0; i < cycle.size(); i++) {
                PouSymbol from = cycle.get(i);
                PouSymbol to = cycle.get((i + 1) % cycle.size());
                ctx.model().callGraph().callsFrom(from).stream()
                        .filter(c -> c.callee() == to)
                        .findFirst()
                        .ifPresent(edges::add);
            }
            if (edges.isEmpty()) {
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
}
