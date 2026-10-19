package dev.plca.lsp;

import dev.plca.analysis.Snapshot;
import dev.plca.analysis.nav.Navigator;
import dev.plca.analysis.nav.Outline;
import dev.plca.core.source.SourceFile;
import dev.plca.report.ToolInfo;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * The plca language server (M8): diagnostics from the parsers, the semantic model and the rules, plus go-to-definition,
 * references, hover, document outline and workspace symbols. All analysis is the CLI's ({@code plc-analysis}); this
 * class only translates LSP messages for {@link Session}.
 */
public final class PlcaLanguageServer implements LanguageServer, LanguageClientAware {

    /** Commands the server executes ({@code workspace/executeCommand}). */
    public static final String ANALYZE_WORKSPACE = "plca.analyzeWorkspace";

    private final Session session = new Session();
    private final Runnable onExit;
    private final Documents documents = new Documents();
    private final Workspace workspace = new Workspace();
    private Path root;
    private boolean shutdown;
    private int exitCode = 1;

    /** @param onExit called on {@code exit}; the CLI ends the process there */
    public PlcaLanguageServer(Runnable onExit) {
        this.onExit = onExit;
    }

    /**
     * Serves LSP over the streams until {@code exit} or until the client disconnects; returns the exit code ({@code 0}
     * after a {@code shutdown} request, as the protocol asks).
     */
    public static int run(InputStream in, OutputStream out) throws Exception {
        CompletableFuture<Void> exited = new CompletableFuture<>();
        PlcaLanguageServer server = new PlcaLanguageServer(() -> exited.complete(null));
        ExecutorService messages = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "plca-lsp");
            t.setDaemon(true);
            return t;
        });
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out, messages, null);
        server.connect(launcher.getRemoteProxy());
        Future<Void> listening = launcher.startListening();
        CompletableFuture<Void> disconnected = CompletableFuture.runAsync(
                () -> {
                    try {
                        listening.get();
                    } catch (Exception e) {
                        // stream closed or listener cancelled
                    }
                },
                messages);
        CompletableFuture.anyOf(exited, disconnected).join();
        server.session.shutdown();
        listening.cancel(true);
        messages.shutdownNow();
        return server.exitCode;
    }

    @Override
    public void connect(LanguageClient client) {
        session.connect(client);
    }

    Session session() {
        return session;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        root = root(params);
        session.configure(Options.read(params.getInitializationOptions(), session.settings()));

        ServerCapabilities caps = new ServerCapabilities();
        TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
        sync.setOpenClose(true);
        sync.setChange(TextDocumentSyncKind.Full); // decision 4: we re-parse whole files anyway
        sync.setSave(new SaveOptions(false));
        caps.setTextDocumentSync(sync);
        caps.setDefinitionProvider(true);
        caps.setReferencesProvider(true);
        caps.setHoverProvider(true);
        caps.setDocumentSymbolProvider(true);
        caps.setWorkspaceSymbolProvider(true);
        caps.setExecuteCommandProvider(new ExecuteCommandOptions(List.of(ANALYZE_WORKSPACE)));
        return CompletableFuture.completedFuture(
                new InitializeResult(caps, new ServerInfo(ToolInfo.NAME, ToolInfo.VERSION)));
    }

    /** The first workspace folder, else the root URI, else the working directory. */
    private static Path root(InitializeParams params) {
        if (params.getWorkspaceFolders() != null
                && !params.getWorkspaceFolders().isEmpty()) {
            Path p = Convert.path(params.getWorkspaceFolders().getFirst().getUri());
            if (p != null) {
                return p;
            }
        }
        @SuppressWarnings("deprecation")
        String rootUri = params.getRootUri();
        if (rootUri != null && Convert.path(rootUri) != null) {
            return Convert.path(rootUri);
        }
        return Path.of("").toAbsolutePath();
    }

    @Override
    public void initialized(InitializedParams params) {
        session.open(root);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        shutdown = true;
        exitCode = 0;
        session.shutdown();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        exitCode = shutdown ? 0 : 1;
        onExit.run();
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return documents;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspace;
    }

    /** Runs {@code q} at a document position: path and file offset in the current state. */
    private <T> CompletableFuture<T> at(String uri, Position position, PositionQuery<T> q) {
        Path file = Convert.path(uri);
        if (file == null) {
            return CompletableFuture.completedFuture(null);
        }
        return session.query(nav -> {
            String path = session.displayPath(file);
            Optional<SourceFile> f = nav.snapshot().file(path).map(s -> s.file());
            if (f.isEmpty()) {
                return null;
            }
            int offset = Convert.offset(f.get().lineIndex(), f.get().text().length(), position);
            return q.apply(nav, path, offset);
        });
    }

    @FunctionalInterface
    private interface PositionQuery<T> {
        T apply(Navigator nav, String path, int offset);
    }

    private final class Documents implements TextDocumentService {

        @Override
        public void didOpen(DidOpenTextDocumentParams params) {
            Path file = Convert.path(params.getTextDocument().getUri());
            if (file != null) {
                session.edit(
                        file,
                        params.getTextDocument().getVersion(),
                        params.getTextDocument().getText());
            }
        }

        @Override
        public void didChange(DidChangeTextDocumentParams params) {
            Path file = Convert.path(params.getTextDocument().getUri());
            if (file != null && !params.getContentChanges().isEmpty()) {
                // full sync: the last change holds the whole text
                session.edit(
                        file,
                        params.getTextDocument().getVersion(),
                        params.getContentChanges().getLast().getText());
            }
        }

        @Override
        public void didClose(DidCloseTextDocumentParams params) {
            Path file = Convert.path(params.getTextDocument().getUri());
            if (file != null) {
                session.close(file);
            }
        }

        @Override
        public void didSave(DidSaveTextDocumentParams params) {
            session.saved();
        }

        @Override
        public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
                DefinitionParams params) {
            return at(params.getTextDocument().getUri(), params.getPosition(), (nav, path, offset) -> {
                List<Location> out = nav.symbolAt(path, offset)
                        .flatMap(t -> nav.definition(t.symbol()))
                        .map(span -> List.of(Convert.location(nav.snapshot(), span)))
                        .orElse(List.of());
                return Either.forLeft(out);
            });
        }

        @Override
        public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
            boolean declaration =
                    params.getContext() != null && params.getContext().isIncludeDeclaration();
            return at(
                    params.getTextDocument().getUri(),
                    params.getPosition(),
                    (nav, path, offset) -> nav.symbolAt(path, offset)
                            .map(t -> nav.references(t, declaration).stream()
                                    .map(s -> Convert.location(nav.snapshot(), s))
                                    .toList())
                            .orElse(List.of()));
        }

        @Override
        public CompletableFuture<Hover> hover(HoverParams params) {
            return at(
                    params.getTextDocument().getUri(),
                    params.getPosition(),
                    (nav, path, offset) -> nav.hover(path, offset)
                            .map(h -> new Hover(
                                    new MarkupContent(MarkupKind.MARKDOWN, h.markdown()),
                                    Convert.range(nav.snapshot(), h.range())))
                            .orElse(null));
        }

        @Override
        public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
                DocumentSymbolParams params) {
            Path file = Convert.path(params.getTextDocument().getUri());
            if (file == null) {
                return CompletableFuture.completedFuture(List.of());
            }
            return session.query(nav -> {
                Snapshot s = nav.snapshot();
                return new Outline(s)
                        .document(session.displayPath(file)).stream()
                                .map(i -> Either.<SymbolInformation, DocumentSymbol>forRight(Convert.symbol(s, i)))
                                .toList();
            });
        }
    }

    private final class Workspace implements WorkspaceService {

        @Override
        public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
                WorkspaceSymbolParams params) {
            return session.query(nav -> {
                Snapshot s = nav.snapshot();
                List<WorkspaceSymbol> out = new Outline(s)
                        .workspace(params.getQuery() == null ? "" : params.getQuery(), 500).stream()
                                .map(sym -> Convert.symbol(s, sym))
                                .toList();
                return Either.forRight(out);
            });
        }

        @Override
        public void didChangeConfiguration(DidChangeConfigurationParams params) {
            // settings arrive through initializationOptions; the extension restarts the server when they change
        }

        @Override
        public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
            for (FileEvent e : params.getChanges()) {
                Path file = Convert.path(e.getUri());
                if (file != null) {
                    session.changedOnDisk(file);
                }
            }
        }

        @Override
        public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
            if (ANALYZE_WORKSPACE.equals(params.getCommand())) {
                return session.reanalyze();
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
