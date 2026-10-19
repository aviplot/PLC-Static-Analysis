package dev.plca.cli;

import dev.plca.web.WebOptions;
import dev.plca.web.WebServer;
import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code plca web}: the code review workbench in the browser (M9). */
@Command(
        name = "web",
        mixinStandardHelpOptions = true,
        description = {
            "Runs the web workbench: upload a project export, review findings, ladder diagrams and differences.",
            "Binds to 127.0.0.1 by default. Binding to another address requires a token (one is generated when",
            "--token is not given). Uploads are deleted when their browser session ends."
        })
final class WebCommand implements Callable<Integer> {

    @Option(names = "--host", description = "Address to bind (default: ${DEFAULT-VALUE}).", defaultValue = "127.0.0.1")
    String host;

    @Option(
            names = "--port",
            description = "Port; 0 picks a free one (default: ${DEFAULT-VALUE}).",
            defaultValue = "8080")
    int port;

    @Option(names = "--token", description = "Access token clients must present (required off loopback).")
    String token;

    @Option(names = "--keep-uploads", description = "Keep uploaded projects after their session ends.")
    boolean keepUploads;

    @Option(names = "--work-dir", description = "Directory for uploads (default: <tmp>/plca-web).")
    Path workDir;

    @Option(names = "--open", description = "Open the workbench in the default browser.")
    boolean open;

    /** Held so the level survives garbage collection of unreferenced loggers. */
    private static final java.util.logging.Logger JETTY = java.util.logging.Logger.getLogger("org.eclipse.jetty");

    @Override
    public Integer call() throws Exception {
        JETTY.setLevel(java.util.logging.Level.WARNING); // no start-up chatter
        WebOptions o = WebOptions.defaults().withHost(host).withPort(port).withKeepUploads(keepUploads);
        if (workDir != null) {
            o = o.withWorkDir(workDir);
        }
        String t = token;
        if (t == null && !isLoopback(host)) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            t = HexFormat.of().formatHex(b);
        }
        if (t != null) {
            o = o.withToken(t);
        }
        WebServer server = WebServer.start(o);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "plca-web-stop"));
        System.err.println("plca web: " + server.url());
        if (!isLoopback(host)) {
            System.err.println("plca web: listening on " + host + "; plain HTTP: put it behind a TLS proxy when it"
                    + " leaves this machine");
        }
        if (open && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(server.url()));
        }
        server.join();
        return 0;
    }

    private static boolean isLoopback(String host) {
        try {
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
