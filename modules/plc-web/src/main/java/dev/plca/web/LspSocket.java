package dev.plca.web;

import dev.plca.lsp.PlcaLanguageServer;
import dev.plca.lsp.Uris;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.json.JsonRpcMethod;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * One browser connection to its own language server, rooted at the project's directory and sandboxed. A WebSocket text
 * message is one JSON-RPC message (no {@code Content-Length} header, as LSP over WebSocket clients send it). Outgoing
 * messages are queued and sent one at a time, as a Jetty session allows; a client that stops reading is disconnected
 * rather than buffered without bound.
 */
public final class LspSocket implements Session.Listener.AutoDemanding {

    private static final Logger LOG = Logger.getLogger(LspSocket.class.getName());

    /** The URI prefix the browser sees for the project's files. */
    static final String PREFIX = "file:///project/";

    static final int MAX_QUEUED = 20_000;

    private final Projects.Project project;
    private final Runnable onClose;
    private final Deque<String> queue = new ArrayDeque<>();
    private boolean sending;
    private boolean closed;
    private Session session;
    private PlcaLanguageServer server;
    private MessageJsonHandler json;
    private RemoteEndpoint endpoint;

    LspSocket(Projects.Project project, Runnable onClose) {
        this.project = project;
        this.onClose = onClose;
    }

    @Override
    public void onWebSocketOpen(Session s) {
        this.session = s;
        project.sockets.incrementAndGet();
        project.lastClosed = 0;
        project.touch(System.currentTimeMillis());
        server = new PlcaLanguageServer(this::close, Uris.mapped(PREFIX, project.dir), true);
        Map<String, JsonRpcMethod> methods = new LinkedHashMap<>();
        methods.putAll(ServiceEndpoints.getSupportedMethods(LanguageClient.class));
        methods.putAll(ServiceEndpoints.getSupportedMethods(PlcaLanguageServer.class));
        json = new MessageJsonHandler(methods);
        MessageConsumer outgoing = message -> send(json.serialize(message));
        endpoint = new RemoteEndpoint(outgoing, ServiceEndpoints.toEndpoint(server));
        json.setMethodProvider(endpoint);
        server.connect(ServiceEndpoints.toServiceObject(endpoint, LanguageClient.class));
    }

    @Override
    public void onWebSocketText(String text) {
        project.touch(System.currentTimeMillis());
        Message message;
        try {
            message = json.parseMessage(text);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "unreadable message", e);
            return; // RemoteEndpoint answers malformed requests it can parse; garbage is dropped
        }
        try {
            endpoint.consume(message);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "message failed", e);
        }
    }

    @Override
    public void onWebSocketClose(int status, String reason, Callback callback) {
        shutdown();
        callback.succeed();
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        LOG.log(Level.FINE, "websocket error", cause);
        shutdown();
    }

    private void send(String text) {
        synchronized (queue) {
            if (closed) {
                return;
            }
            if (queue.size() >= MAX_QUEUED) {
                LOG.warning("client is not reading; closing its connection");
                close();
                return;
            }
            queue.add(text);
            if (!sending) {
                next();
            }
        }
    }

    /** Sends the head of the queue; called with the queue's lock held. */
    private void next() {
        String text = queue.poll();
        if (text == null || closed) {
            sending = false;
            return;
        }
        sending = true;
        session.sendText(
                text,
                Callback.from(
                        () -> {
                            synchronized (queue) {
                                next();
                            }
                        },
                        x -> {
                            LOG.log(Level.FINE, "send failed", x);
                            close();
                        }));
    }

    private void close() {
        Session s = session;
        if (s != null && s.isOpen()) {
            s.close(1000, "bye", Callback.NOOP);
        }
        shutdown();
    }

    private void shutdown() {
        synchronized (queue) {
            if (closed) {
                return;
            }
            closed = true;
            queue.clear();
        }
        if (server != null) {
            server.shutdown();
        }
        project.touch(System.currentTimeMillis());
        if (project.sockets.decrementAndGet() == 0) {
            project.lastClosed = System.currentTimeMillis();
        }
        onClose.run();
    }
}
