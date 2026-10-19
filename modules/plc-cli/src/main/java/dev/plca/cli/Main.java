package dev.plca.cli;

import dev.plca.report.ToolInfo;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** {@code plca}: static analysis for PLC programs. */
@Command(
        name = "plca",
        mixinStandardHelpOptions = true,
        versionProvider = Main.Version.class,
        description = "Static analysis for PLC programs (CODESYS / TwinCAT Structured Text).",
        subcommands = {AnalyzeCommand.class, RulesCommand.class, LspCommand.class, CommandLine.HelpCommand.class})
public final class Main implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Runs the CLI without exiting (tests, embedding). */
    public static int run(String... args) {
        return commandLine().execute(args);
    }

    /** The configured command line, e.g. to redirect its output in tests. */
    public static CommandLine commandLine() {
        return new CommandLine(new Main())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((e, cmd, parsed) -> {
                    cmd.getErr().println("plca: internal error: " + e);
                    e.printStackTrace(cmd.getErr());
                    return AnalyzeCommand.INTERNAL;
                });
    }

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "missing command: analyze, rules or lsp");
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {ToolInfo.NAME + " " + ToolInfo.VERSION};
        }
    }
}
