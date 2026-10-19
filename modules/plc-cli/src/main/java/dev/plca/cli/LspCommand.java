package dev.plca.cli;

import dev.plca.lsp.PlcaLanguageServer;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code plca lsp}: the language server on standard input and output, for editors (M8). */
@Command(
        name = "lsp",
        mixinStandardHelpOptions = true,
        description = "Runs the language server over standard input/output (for editor extensions).")
final class LspCommand implements Callable<Integer> {

    @Option(names = "--stdio", description = "Accepted for clients that pass it; stdio is the only transport.")
    boolean stdio;

    @Override
    public Integer call() throws Exception {
        // stdout carries the protocol: nothing else may write to it
        java.io.PrintStream protocol = System.out;
        System.setOut(System.err);
        return PlcaLanguageServer.run(System.in, protocol);
    }
}
