package dev.plca.cli;

import dev.plca.rules.RuleDescriptor;
import dev.plca.rules.RuleRegistry;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code plca rules [id]}: lists the rules, or prints one rule's documentation. */
@Command(name = "rules", mixinStandardHelpOptions = true, description = "List rules or show one rule's documentation.")
final class RulesCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<rule>", description = "Rule id to document.")
    String rule;

    @Option(names = "--params", description = "Also list each rule's parameters.")
    boolean showParams;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        RuleRegistry registry = RuleRegistry.standard();
        if (rule != null) {
            RuleDescriptor d = registry.descriptor(rule).orElse(null);
            if (d == null) {
                spec.commandLine().getErr().println("plca: unknown rule " + rule);
                return AnalyzeCommand.USAGE;
            }
            out.println(d.id() + " " + d.title());
            out.println("severity: " + d.severity().name().toLowerCase(Locale.ROOT) + ", category: "
                    + d.category().key() + (d.plcopen() == null ? "" : ", PLCopen " + d.plcopen())
                    + (d.cwe() == null ? "" : ", " + d.cwe())
                    + (d.enabledByDefault() ? "" : ", off by default (select it to run it)"));
            d.dialectSeverity()
                    .forEach((dialect, sev) ->
                            out.println("severity for " + dialect.name().toLowerCase(Locale.ROOT) + ": "
                                    + sev.name().toLowerCase(Locale.ROOT)));
            params(out, d);
            out.println();
            out.print(d.markdown());
            out.flush();
            return 0;
        }
        out.printf(
                Locale.ROOT,
                "%-10s %-7s %-8s %-9s %-7s %s%n",
                "ID",
                "DEFAULT",
                "SEVERITY",
                "CATEGORY",
                "PLCOPEN",
                "TITLE");
        for (RuleDescriptor d : registry.descriptors()) {
            out.printf(
                    Locale.ROOT,
                    "%-10s %-7s %-8s %-9s %-7s %s%n",
                    d.id(),
                    d.enabledByDefault() ? "on" : "off",
                    d.severity().name().toLowerCase(Locale.ROOT),
                    d.category().key(),
                    d.plcopen() == null ? "-" : d.plcopen(),
                    d.title());
            if (showParams) {
                params(out, d);
            }
        }
        out.flush();
        return 0;
    }

    private static void params(PrintWriter out, RuleDescriptor d) {
        for (RuleDescriptor.Parameter p : d.parameters()) {
            out.printf(
                    Locale.ROOT,
                    "    %s.%s (%s, default %s): %s%n",
                    d.id(),
                    p.name(),
                    p.type().name().toLowerCase(Locale.ROOT),
                    p.defaultValue().isEmpty() ? "\"\"" : p.defaultValue(),
                    p.description());
        }
    }
}
