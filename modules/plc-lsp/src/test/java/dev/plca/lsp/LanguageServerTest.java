package dev.plca.lsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.plca.core.testing.Golden;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The server end to end over JSON-RPC: a client launcher on piped streams, as an editor would connect. */
class LanguageServerTest {

    private static final Path PROJECTS = Path.of("../plc-semantic/src/test/resources/projects");

    @TempDir
    Path tmp;

    private PlcaLanguageServer server;
    private LanguageServer remote;
    private final TestClient client = new TestClient();
    private final ExecutorService threads = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    /** Records what the server sends. */
    static final class TestClient implements LanguageClient {
        final Map<String, PublishDiagnosticsParams> diagnostics = new ConcurrentHashMap<>();
        final List<String> messages = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void telemetryEvent(Object object) {}

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams p) {
            diagnostics.put(p.getUri(), p);
        }

        @Override
        public void showMessage(MessageParams p) {
            messages.add(p.getType() + ": " + p.getMessage());
        }

        @Override
        public java.util.concurrent.CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams p) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams p) {}
    }

    @BeforeEach
    void start() throws Exception {
        for (String project : List.of("plant", "rockwell_line")) {
            Path from = PROJECTS.resolve(project);
            try (Stream<Path> s = Files.walk(from)) {
                for (Path p : s.toList()) {
                    Path to = tmp.resolve(project).resolve(from.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(to);
                    } else {
                        Files.copy(p, to);
                    }
                }
            }
        }
        PipedInputStream serverIn = new PipedInputStream(1 << 16);
        PipedOutputStream clientOut = new PipedOutputStream(serverIn);
        PipedInputStream clientIn = new PipedInputStream(1 << 16);
        PipedOutputStream serverOut = new PipedOutputStream(clientIn);

        server = new PlcaLanguageServer(() -> {});
        Launcher<LanguageClient> serverLauncher =
                LSPLauncher.createServerLauncher(server, serverIn, serverOut, threads, null);
        server.connect(serverLauncher.getRemoteProxy());
        serverLauncher.startListening();
        Launcher<LanguageServer> clientLauncher =
                LSPLauncher.createClientLauncher(client, clientIn, clientOut, threads, null);
        clientLauncher.startListening();
        remote = clientLauncher.getRemoteProxy();

        InitializeParams init = new InitializeParams();
        init.setCapabilities(new ClientCapabilities());
        init.setWorkspaceFolders(List.of(new WorkspaceFolder(tmp.toUri().toString(), "tmp")));
        JsonObject options = new JsonObject();
        options.addProperty("fastDelayMs", 5);
        options.addProperty("fullDelayMs", 30);
        options.addProperty("threads", 2);
        init.setInitializationOptions(options);
        InitializeResult result = remote.initialize(init).get(10, TimeUnit.SECONDS);
        assertThat(result.getCapabilities().getHoverProvider().getLeft()).isTrue();
        remote.initialized(new InitializedParams());
        idle();
    }

    @AfterEach
    void stop() throws Exception {
        remote.shutdown().get(10, TimeUnit.SECONDS);
        remote.exit();
        threads.shutdownNow();
    }

    /** Waits until the server is idle and everything it published has reached the client. */
    private void idle() throws Exception {
        // LSP4J dispatches messages in order: once this answers, every notification sent before it was handled
        remote.getWorkspaceService().symbol(new WorkspaceSymbolParams("zzzz")).get(10, TimeUnit.SECONDS);
        server.session().awaitIdle(20_000);
        // a request round trip: its response travels behind every notification sent before it
        remote.getWorkspaceService().symbol(new WorkspaceSymbolParams("zzzz")).get(10, TimeUnit.SECONDS);
    }

    private String uri(String path) {
        return tmp.resolve(path).toUri().toString();
    }

    private List<String> codes(String path) {
        PublishDiagnosticsParams p = client.diagnostics.get(uri(path));
        return p == null
                ? List.of()
                : p.getDiagnostics().stream()
                        .map(d -> d.getCode().getLeft() + "@"
                                + (d.getRange().getStart().getLine() + 1))
                        .sorted()
                        .toList();
    }

    @Test
    void publishesTheCliFindingsAfterLoading() {
        assertThat(client.diagnostics.keySet()).contains(uri("plant/MAIN.st"), uri("rockwell_line/Line1.L5X"));
        assertThat(String.join("\n", codes("rockwell_line/Line1.L5X"))).contains("PLCA-");
    }

    @Test
    void editsUpdateDiagnosticsWithTheEditorVersion() throws Exception {
        String main = Files.readString(tmp.resolve("plant/MAIN.st"));
        String uri = uri("plant/MAIN.st");
        remote.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "iec-st", 1, main)));
        idle();
        List<String> before = codes("plant/MAIN.st");

        // twenty quick keystrokes; only the last text may be reported
        String broken = main.replace("n := i + 1;", "n := i + ;");
        for (int v = 2; v <= 21; v++) {
            String text = v < 21 ? main.replace("n := i + 1;", "n := i + " + v + ";") : broken;
            remote.getTextDocumentService()
                    .didChange(new DidChangeTextDocumentParams(
                            new VersionedTextDocumentIdentifier(uri, v),
                            List.of(new TextDocumentContentChangeEvent(text))));
        }
        idle();
        PublishDiagnosticsParams p = client.diagnostics.get(uri);
        assertThat(p.getVersion()).isEqualTo(21);
        assertThat(codes("plant/MAIN.st")).contains("PLC-SYN-001@13");

        remote.getTextDocumentService()
                .didChange(new DidChangeTextDocumentParams(
                        new VersionedTextDocumentIdentifier(uri, 22),
                        List.of(new TextDocumentContentChangeEvent(main))));
        idle();
        assertThat(codes("plant/MAIN.st")).isEqualTo(before);

        // closing an edited buffer goes back to the disk content
        remote.getTextDocumentService()
                .didChange(new DidChangeTextDocumentParams(
                        new VersionedTextDocumentIdentifier(uri, 23),
                        List.of(new TextDocumentContentChangeEvent(broken))));
        remote.getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
        idle();
        assertThat(codes("plant/MAIN.st")).isEqualTo(before);
    }

    @Test
    void projectRulesSeeUnsavedBuffers() throws Exception {
        // a function in a buffer that was never saved, called nowhere: Q008 (a project rule) reports it
        String uri = uri("plant/F_Extra.st");
        String code = "FUNCTION F_Extra : INT\nF_Extra := 1;\nEND_FUNCTION\n";
        remote.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "iec-st", 1, code)));
        idle();
        assertThat(Files.exists(tmp.resolve("plant/F_Extra.st"))).isFalse();
        assertThat(codes("plant/F_Extra.st")).contains("PLCA-Q008@1");

        // calling it from MAIN (also unsaved) clears the finding
        String main = Files.readString(tmp.resolve("plant/MAIN.st")).replace("n := i + 1;", "n := F_Extra();");
        remote.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri("plant/MAIN.st"), "iec-st", 1, main)));
        idle();
        assertThat(codes("plant/F_Extra.st")).doesNotContain("PLCA-Q008@1");
    }

    @Test
    void filesChangedOnDiskAndConfiguration() throws Exception {
        Files.writeString(tmp.resolve("plant/F_Div.st"), "FUNCTION F_Div : INT\nF_Div := 1 / 0;\nEND_FUNCTION\n");
        remote.getWorkspaceService()
                .didChangeWatchedFiles(new DidChangeWatchedFilesParams(
                        List.of(new FileEvent(uri("plant/F_Div.st"), FileChangeType.Created))));
        idle();
        assertThat(codes("plant/F_Div.st")).anyMatch(c -> c.startsWith("PLCA-"));

        // a broken plca.yaml is reported on its line; the defaults stay in force
        Files.writeString(tmp.resolve("plca.yaml"), "rules:\n  slect: [all]\n");
        remote.getWorkspaceService()
                .didChangeWatchedFiles(new DidChangeWatchedFilesParams(
                        List.of(new FileEvent(uri("plca.yaml"), FileChangeType.Created))));
        idle();
        List<Diagnostic> config = client.diagnostics.get(uri("plca.yaml")).getDiagnostics();
        assertThat(config).hasSize(1);
        assertThat(config.getFirst().getRange().getStart().getLine()).isEqualTo(1);
        assertThat(codes("plant/F_Div.st")).anyMatch(c -> c.startsWith("PLCA-"));

        // a valid one that disables every rule clears the findings
        String disabled = "rules:\n  select: [PLCA-Q007]\n";
        Files.writeString(tmp.resolve("plca.yaml"), disabled);
        remote.getWorkspaceService()
                .didChangeWatchedFiles(new DidChangeWatchedFilesParams(
                        List.of(new FileEvent(uri("plca.yaml"), FileChangeType.Changed))));
        idle();
        assertThat(client.diagnostics.get(uri("plca.yaml")).getDiagnostics()).isEmpty();
        assertThat(codes("plant/F_Div.st")).noneMatch(c -> c.startsWith("PLCA-") && !c.startsWith("PLCA-Q007"));

        Files.delete(tmp.resolve("plant/F_Div.st"));
        remote.getWorkspaceService()
                .didChangeWatchedFiles(new DidChangeWatchedFilesParams(
                        List.of(new FileEvent(uri("plant/F_Div.st"), FileChangeType.Deleted))));
        idle();
        assertThat(client.diagnostics.get(uri("plant/F_Div.st")).getDiagnostics())
                .isEmpty();
    }

    @Test
    void requests() throws Exception {
        StringBuilder out = new StringBuilder();
        String main = uri("plant/MAIN.st");
        Position fbMotorCall = new Position(10, 2); // fbMotor(bEnable := …
        Position speed = new Position(11, 20); // fbMotor.Speed

        List<? extends Location> def = remote.getTextDocumentService()
                .definition(new DefinitionParams(new TextDocumentIdentifier(main), speed))
                .get(10, TimeUnit.SECONDS)
                .getLeft();
        out.append("definition Speed: ").append(locations(def)).append('\n');

        ReferenceParams refs =
                new ReferenceParams(new TextDocumentIdentifier(main), fbMotorCall, new ReferenceContext(true));
        out.append("references fbMotor: ")
                .append(locations(
                        remote.getTextDocumentService().references(refs).get(10, TimeUnit.SECONDS)))
                .append('\n');

        Hover h = remote.getTextDocumentService()
                .hover(new HoverParams(new TextDocumentIdentifier(main), fbMotorCall))
                .get(10, TimeUnit.SECONDS);
        out.append("hover fbMotor ")
                .append(range(h.getRange()))
                .append(":\n")
                .append(h.getContents().getRight().getValue())
                .append('\n');

        // a position in a TwinCAT XML file: the variable in the GVL's CDATA
        Hover x = remote.getTextDocumentService()
                .hover(new HoverParams(new TextDocumentIdentifier(uri("plant/GVL_IO.TcGVL")), new Position(5, 6)))
                .get(10, TimeUnit.SECONDS);
        out.append("hover in TcGVL ")
                .append(range(x.getRange()))
                .append(":\n")
                .append(x.getContents().getRight().getValue())
                .append('\n');

        Hover none = remote.getTextDocumentService()
                .hover(new HoverParams(new TextDocumentIdentifier(uri("plant/GVL_IO.TcGVL")), new Position(0, 3)))
                .get(10, TimeUnit.SECONDS);
        out.append("hover in XML markup: ").append(none).append('\n');

        out.append("outline FB_Motor.st:\n");
        for (var e : remote.getTextDocumentService()
                .documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri("plant/FB_Motor.st"))))
                .get(10, TimeUnit.SECONDS)) {
            symbol(e.getRight(), 1, out);
        }
        out.append("workspace 'start':\n");
        for (var s : remote.getWorkspaceService()
                .symbol(new WorkspaceSymbolParams("start"))
                .get(10, TimeUnit.SECONDS)
                .getRight()) {
            out.append("  ")
                    .append(s.getKind())
                    .append(' ')
                    .append(s.getName())
                    .append(' ')
                    .append(locations(List.of(s.getLocation().getLeft())))
                    .append('\n');
        }
        Golden.assertMatches(out.toString(), "requests.txt");
    }

    private static void symbol(DocumentSymbol s, int depth, StringBuilder out) {
        out.append("  ".repeat(depth))
                .append(s.getKind())
                .append(' ')
                .append(s.getName())
                .append(s.getDetail() == null || s.getDetail().isEmpty() ? "" : " : " + s.getDetail())
                .append(' ')
                .append(range(s.getRange()))
                .append(" sel ")
                .append(range(s.getSelectionRange()))
                .append('\n');
        if (s.getChildren() != null) {
            s.getChildren().forEach(c -> symbol(c, depth + 1, out));
        }
    }

    private String locations(List<? extends Location> ls) {
        List<String> out = new ArrayList<>();
        for (Location l : ls) {
            String rel = tmp.toUri().relativize(java.net.URI.create(l.getUri())).toString();
            out.add(rel + " " + range(l.getRange()));
        }
        return String.join(", ", out);
    }

    private static String range(Range r) {
        return r.getStart().getLine() + ":" + r.getStart().getCharacter() + "-"
                + r.getEnd().getLine() + ":" + r.getEnd().getCharacter();
    }

    @Test
    void ruleDocumentation() throws Exception {
        Object doc = remote.getWorkspaceService()
                .executeCommand(
                        new org.eclipse.lsp4j.ExecuteCommandParams(PlcaLanguageServer.RULE_DOC, List.of("plca-q003")))
                .get(10, TimeUnit.SECONDS);
        assertThat(String.valueOf(doc)).contains("REAL");
        Object none = remote.getWorkspaceService()
                .executeCommand(new org.eclipse.lsp4j.ExecuteCommandParams(PlcaLanguageServer.RULE_DOC, List.of("X")))
                .get(10, TimeUnit.SECONDS);
        assertThat(none).isNull();
    }

    @Test
    void messagesNeverIncludeInternalErrors() {
        assertThat(client.messages).noneMatch(m -> m.contains("internal error"));
    }
}
