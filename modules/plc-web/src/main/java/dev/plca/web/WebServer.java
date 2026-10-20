package dev.plca.web;

import dev.plca.analysis.ConfigFile;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.TextEncoder;
import dev.plca.report.AnalysisResult;
import dev.plca.report.Baseline;
import dev.plca.report.ReportFormat;
import dev.plca.report.ToolInfo;
import dev.plca.rules.RuleDescriptor;
import dev.plca.rules.RuleRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The web workbench's server (M9): the browser app, the project API and one language server per WebSocket, on one port.
 * Everything it serves comes from this process; nothing is fetched from elsewhere and nothing is reported anywhere.
 *
 * <pre>
 * GET    /                                the app (static files under /assets/)
 * GET    /api/session                     this session's projects, version, limits
 * GET    /api/rules                       rule descriptors (filters, documentation)
 * POST   /api/projects                    body: a zip; header X-Plca-Name: the project name (URL-encoded)
 * GET    /api/projects/{id}/tree          the project's files
 * GET    /api/projects/{id}/file?path=    a file's text, decoded as the analyzer decodes it
 * GET    /api/projects/{id}/report?format=json|sarif|html
 * POST   /api/projects/{id}/export        {"files": [{"path", "text"}]}: a zip of edited files in their original encoding
 * DELETE /api/projects/{id}
 * POST   /api/compare                     {"base": id, "head": id}
 * GET    /api/about                       version and third-party notices
 * WS     /lsp/{id}                        LSP over WebSocket, file:///project/… URIs
 * </pre>
 */
public final class WebServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WebServer.class.getName());
    private static final String STATIC = "/dev/plca/web/static/";
    static final String NAME_HEADER = "X-Plca-Name";
    static final String REENCODED_HEADER = "X-Plca-Reencoded";

    private final WebOptions options;
    private final Server server;
    private final ServerConnector connector;
    private final Projects projects;
    /** Set once the port is known (it may have been 0). */
    private volatile Guard guard;

    private final RuleRegistry rules = RuleRegistry.standard();
    private final ObjectMapper json = JsonMapper.builder().build();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "plca-web-sweeper");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, byte[]> staticCache = new ConcurrentHashMap<>();
    private final Map<String, String> staticEtags = new ConcurrentHashMap<>();

    private WebServer(WebOptions options) throws IOException {
        this.options = options;
        this.projects = new Projects(options);
        QueuedThreadPool pool = new QueuedThreadPool(64, 4);
        pool.setName("plca-web");
        pool.setDaemon(true);
        this.server = new Server(pool);
        HttpConfiguration http = new HttpConfiguration();
        http.setSendServerVersion(false);
        http.setSendXPoweredBy(false);
        this.connector = new ServerConnector(server, new HttpConnectionFactory(http));
        connector.setHost(options.host());
        connector.setPort(options.port());
        server.addConnector(connector);
    }

    /** Starts a server; {@link #port()} tells the port when {@code options.port()} was 0. */
    public static WebServer start(WebOptions options) throws Exception {
        WebServer w = new WebServer(options);
        w.run();
        return w;
    }

    private void run() throws Exception {
        WebSocketUpgradeHandler ws = WebSocketUpgradeHandler.from(server, container -> {
            container.setMaxTextMessageSize(64L << 20);
            container.setIdleTimeout(Duration.ofHours(12));
            container.addMapping("/lsp/*", this::upgrade);
        });
        ws.setHandler(new Api());
        server.setHandler(ws);
        guard = new Guard(options.host(), options.port(), options.token());
        server.start();
        guard = new Guard(options.host(), connector.getLocalPort(), options.token());
        long period = Math.max(1000, Math.min(options.grace().toMillis(), 30_000) / 2);
        sweeper.scheduleAtFixedRate(
                () -> {
                    try {
                        projects.sweep(System.currentTimeMillis());
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "sweep failed", e);
                    }
                },
                period,
                period,
                TimeUnit.MILLISECONDS);
    }

    private Guard guard() {
        return guard;
    }

    public int port() {
        return connector.getLocalPort();
    }

    /** The URL to open (with the token, when one is required). */
    public String url() {
        String host = options.host().contains(":") ? "[" + options.host() + "]" : options.host();
        if (host.equals("0.0.0.0") || host.equals("[::]")) {
            host = "localhost";
        }
        return "http://" + host + ":" + port() + "/" + (options.token() == null ? "" : "?token=" + options.token());
    }

    int projectCount() {
        return projects.size();
    }

    void sweepNow(long now) {
        projects.sweep(now);
    }

    /** Stops serving and deletes the uploads (unless they are kept). */
    @Override
    public void close() {
        sweeper.shutdownNow();
        try {
            server.stop();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "stopping the server failed", e);
        } finally {
            projects.deleteAll();
        }
    }

    public void join() throws InterruptedException {
        server.join();
    }

    // ---- WebSocket ------------------------------------------------------------------------------

    private Object upgrade(ServerUpgradeRequest request, ServerUpgradeResponse response, Callback callback) {
        Integer refused = guard().check(request, true);
        String session = Guard.cookie(request, Guard.SESSION);
        String id = Request.getPathInContext(request).substring("/lsp/".length());
        Optional<Projects.Project> p =
                refused != null || session == null ? Optional.empty() : projects.get(session, id);
        if (p.isEmpty()) {
            Response.writeError(request, response, callback, refused != null ? refused : HttpStatus.NOT_FOUND_404);
            return null;
        }
        return new LspSocket(p.get(), () -> {});
    }

    // ---- HTTP -----------------------------------------------------------------------------------

    private final class Api extends Handler.Abstract {

        @Override
        public boolean handle(Request request, Response response, Callback callback) {
            Guard.securityHeaders(response);
            Integer refused = guard().check(request, false);
            if (refused != null) {
                text(response, callback, refused, refused == 401 ? "a token is required" : "forbidden");
                return true;
            }
            String path = Request.getPathInContext(request);
            String method = request.getMethod();
            try {
                if (path.startsWith("/api/")) {
                    String session = Guard.session(request, response);
                    api(method, path.substring(4), session, request, response, callback);
                } else if (method.equals("GET") || method.equals("HEAD")) {
                    if (guard().tokenInQuery(request)) {
                        guard().setTokenCookie(response);
                        Response.sendRedirect(request, response, callback, HttpStatus.SEE_OTHER_303, path, true);
                        return true;
                    }
                    Guard.session(request, response);
                    serveStatic(path, request, response, callback);
                } else {
                    text(response, callback, 405, "method not allowed");
                }
            } catch (ApiException e) {
                text(response, callback, e.status, e.getMessage());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "request failed: " + method + " " + path, e);
                text(response, callback, 500, "internal error");
            }
            return true;
        }
    }

    static final class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    record ProjectInfo(String id, String name, int files, int skipped) {}

    record SessionInfo(String version, List<ProjectInfo> projects, WebOptions.Limits limits) {}

    record FileInfo(String path, long size) {}

    record RuleInfo(
            String id,
            String title,
            String severity,
            String category,
            List<String> dialects,
            boolean enabledByDefault,
            String plcopen,
            String markdown) {}

    private void api(String method, String path, String session, Request request, Response response, Callback cb)
            throws Exception {
        String[] parts = path.split("/");
        // parts[0] is "" (path starts with '/')
        switch (method + " " + (parts.length > 1 ? parts[1] : "")) {
            case "GET session" ->
                json(response, cb, new SessionInfo(ToolInfo.VERSION, infos(session), options.limits()));
            case "GET rules" -> json(response, cb, rules());
            case "GET about" -> text(response, cb, 200, about());
            case "POST projects" -> {
                if (parts.length == 4 && parts[3].equals("export")) {
                    export(project(session, parts[2]), request, response, cb);
                    return;
                }
                if (parts.length != 2) {
                    throw new ApiException(404, "not found");
                }
                String name = request.getHeaders().get(NAME_HEADER);
                name = name == null ? "project" : URLDecoder.decode(name, StandardCharsets.UTF_8);
                Projects.Project p;
                try (InputStream in = Request.asInputStream(request)) {
                    p = projects.create(session, name, in, System.currentTimeMillis());
                } catch (ZipExtractor.UploadException e) {
                    throw new ApiException(400, e.getMessage());
                }
                json(response, cb, info(p));
            }
            case "GET projects" -> {
                if (parts.length == 2) {
                    json(response, cb, infos(session));
                    return;
                }
                Projects.Project p = project(session, parts[2]);
                String what = parts.length > 3 ? parts[3] : "";
                switch (what) {
                    case "tree" -> json(response, cb, tree(p));
                    case "file" -> {
                        String rel = Request.extractQueryParameters(request).getValue("path");
                        Path f = file(p, rel);
                        byte[] data = Files.readAllBytes(f);
                        String text = SourceFile.fromBytes(rel, data).text();
                        text(response, cb, 200, text);
                    }
                    case "report" ->
                        report(p, Request.extractQueryParameters(request).getValue("format"), response, cb);
                    default -> throw new ApiException(404, "not found");
                }
            }
            case "DELETE projects" -> {
                if (parts.length != 3 || !projects.delete(session, parts[2])) {
                    throw new ApiException(404, "no such project");
                }
                json(response, cb, Map.of("deleted", parts[2]));
            }
            case "POST compare" -> {
                JsonNode body;
                try (InputStream in = Request.asInputStream(request)) {
                    body = json.readTree(in.readNBytes(64 * 1024));
                } catch (RuntimeException e) {
                    throw new ApiException(400, "expected {\"base\": id, \"head\": id}");
                }
                Projects.Project base = project(session, body.path("base").asString(null));
                Projects.Project head = project(session, body.path("head").asString(null));
                AnalysisResult b = analyze(base);
                AnalysisResult h = analyze(head);
                json(response, cb, Analyses.compare(base, b, head, h));
            }
            default -> throw new ApiException(404, "not found");
        }
    }

    /** A file written in another charset than it came in, and the characters that made it so. */
    record Reencoded(String path, String original, String written, String characters) {}

    /**
     * Edited texts back as the IDE exported them: each file in its original charset and BOM ({@link TextEncoder}), or
     * UTF-8 with BOM when the text no longer fits; those files are listed in the {@code X-Plca-Reencoded} header (JSON,
     * URL-encoded).
     */
    private void export(Projects.Project p, Request request, Response response, Callback cb) throws Exception {
        JsonNode body;
        try (InputStream in = Request.asInputStream(request)) {
            byte[] data = in.readNBytes(
                    (int) Math.min(Integer.MAX_VALUE - 8, options.limits().maxExtractedBytes() * 2 + 1));
            if (data.length > options.limits().maxExtractedBytes() * 2) {
                throw new ApiException(413, "edited files too large");
            }
            body = json.readTree(data);
        } catch (RuntimeException e) {
            throw new ApiException(400, "expected {\"files\": [{\"path\": …, \"text\": …}]}");
        }
        List<Reencoded> reencoded = new ArrayList<>();
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip, StandardCharsets.UTF_8)) {
            for (JsonNode f : body.path("files")) {
                String rel = f.path("path").asString(null);
                String text = f.path("text").asString(null);
                if (text == null) {
                    throw new ApiException(400, "a file without text");
                }
                TextEncoder.Encoded e = TextEncoder.encodeLike(text, Files.readAllBytes(file(p, rel)));
                if (e.fellBack()) {
                    reencoded.add(
                            new Reencoded(rel, e.requested().name(), e.charset().name(), e.unmappable()));
                }
                z.putNextEntry(new ZipEntry(rel));
                z.write(e.data());
                z.closeEntry();
            }
        }
        response.getHeaders()
                .put(
                        REENCODED_HEADER,
                        URLEncoder.encode(json.writeValueAsString(reencoded), StandardCharsets.UTF_8)
                                .replace("+", "%20"));
        bytes(response, cb, 200, "application/zip", zip.toByteArray());
    }

    private Projects.Project project(String session, String id) throws ApiException {
        Projects.Project p = projects.get(session, id).orElseThrow(() -> new ApiException(404, "no such project"));
        p.touch(System.currentTimeMillis());
        return p;
    }

    private static ProjectInfo info(Projects.Project p) {
        return new ProjectInfo(p.id, p.name, p.files.size(), p.skipped);
    }

    private List<ProjectInfo> infos(String session) {
        return projects.list(session).stream().map(WebServer::info).toList();
    }

    private static List<FileInfo> tree(Projects.Project p) throws IOException {
        List<FileInfo> out = new ArrayList<>(p.files.size());
        for (String f : p.files) {
            Path file = p.dir.resolve(f);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                out.add(new FileInfo(f, Files.size(file)));
            }
        }
        return out;
    }

    /** A file of the project by its relative path; only files the upload kept. */
    private static Path file(Projects.Project p, String rel) throws ApiException {
        if (rel == null || !p.files.contains(rel)) {
            throw new ApiException(404, "no such file");
        }
        Path f = p.dir.resolve(rel).normalize();
        if (!f.startsWith(p.dir) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(404, "no such file");
        }
        return f;
    }

    private List<RuleInfo> rules() {
        List<RuleInfo> out = new ArrayList<>();
        for (RuleDescriptor d : rules.descriptors()) {
            out.add(new RuleInfo(
                    d.id(),
                    d.title(),
                    d.severity().name().toLowerCase(Locale.ROOT),
                    d.category().name().toLowerCase(Locale.ROOT),
                    d.dialects().stream()
                            .map(x -> x.name().toLowerCase(Locale.ROOT))
                            .sorted()
                            .toList(),
                    d.enabledByDefault(),
                    d.plcopen(),
                    d.markdown()));
        }
        return out;
    }

    private AnalysisResult analyze(Projects.Project p) throws ApiException, IOException {
        try {
            return Analyses.analyze(p.dir, rules, options.threads());
        } catch (ConfigFile.ConfigException | Baseline.BaselineException | IllegalArgumentException e) {
            String message = e.getMessage() == null ? "invalid configuration" : e.getMessage();
            throw new ApiException(400, "plca.yaml: " + message.replace(p.dir.toString() + "/", ""));
        }
    }

    private void report(Projects.Project p, String format, Response response, Callback cb) throws Exception {
        ReportFormat f;
        String type;
        String ext;
        switch (format == null ? "" : format) {
            case "json" -> {
                f = ReportFormat.JSON;
                type = "application/json";
                ext = "json";
            }
            case "sarif" -> {
                f = ReportFormat.SARIF;
                type = "application/sarif+json";
                ext = "sarif";
            }
            case "html" -> {
                f = ReportFormat.HTML;
                type = "text/html";
                ext = "html";
            }
            default -> throw new ApiException(400, "format: json, sarif or html");
        }
        AnalysisResult r = analyze(p);
        StringWriter w = new StringWriter();
        f.write(r, w);
        String file = p.name.replaceAll("[^A-Za-z0-9._-]", "_") + "-plca." + ext;
        response.getHeaders().put(HttpHeader.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"");
        bytes(response, cb, 200, type + "; charset=utf-8", w.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String about() {
        StringBuilder sb = new StringBuilder();
        sb.append(ToolInfo.NAME).append(' ').append(ToolInfo.VERSION).append("\n\n");
        try (InputStream in = WebServer.class.getResourceAsStream(STATIC + "THIRD_PARTY_NOTICES.txt")) {
            if (in != null) {
                sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // no notices bundled (development build)
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/dev/plca/web/THIRD_PARTY_NOTICES-server.txt")) {
            if (in != null) {
                sb.append('\n').append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // ditto
        }
        return sb.toString();
    }

    // ---- static files ---------------------------------------------------------------------------

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "ttf", "font/ttf",
            "woff2", "font/woff2",
            "svg", "image/svg+xml",
            "png", "image/png",
            "json", "application/json",
            "txt", "text/plain; charset=utf-8",
            "map", "application/json");

    private void serveStatic(String path, Request request, Response response, Callback cb) throws ApiException {
        String rel = path.equals("/") ? "index.html" : path.substring(1);
        if (!rel.equals("index.html") && !rel.startsWith("assets/") && !rel.equals("THIRD_PARTY_NOTICES.txt")
                || rel.contains("..")
                || rel.contains("//")
                || rel.contains("\\")) {
            throw new ApiException(404, "not found");
        }
        String ext = rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String type = TYPES.get(ext);
        if (type == null) {
            throw new ApiException(404, "not found");
        }
        byte[] data = staticCache.computeIfAbsent(rel, WebServer::load);
        if (data.length == 0) {
            if (rel.equals("index.html")) {
                bytes(response, cb, 200, type, PLACEHOLDER.getBytes(StandardCharsets.UTF_8));
                return;
            }
            throw new ApiException(404, "not found");
        }
        // app.js, app.css and editor.worker.js keep their names across builds: the browser must ask each time (a cheap
        // 304 by ETag), or it runs the old app for an hour after a rebuild. Only content-hashed names are cached.
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, cacheControl(rel));
        String etag = staticEtags.computeIfAbsent(rel, r -> etag(data));
        response.getHeaders().put(HttpHeader.ETAG, etag);
        if (etag.equals(request.getHeaders().get(HttpHeader.IF_NONE_MATCH))) {
            response.setStatus(HttpStatus.NOT_MODIFIED_304);
            cb.succeeded();
            return;
        }
        bytes(response, cb, 200, type, data);
    }

    /** Long caching for content-hashed assets (esbuild's {@code name-HASH.ext}), revalidation for everything else. */
    static String cacheControl(String rel) {
        return HASHED.matcher(rel).find() ? "private, max-age=31536000, immutable" : "no-cache";
    }

    private static final Pattern HASHED = Pattern.compile("-[A-Z0-9]{8}\\.[a-z0-9]+$");

    private static String etag(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            return "\"" + HexFormat.of().formatHex(h, 0, 12) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] load(String rel) {
        try (InputStream in = WebServer.class.getResourceAsStream(STATIC + rel)) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    static final String PLACEHOLDER =
            "<!DOCTYPE html><html lang=\"en\"><meta charset=\"utf-8\"><title>PLC workbench</title>"
                    + "<body style=\"font:15px system-ui;margin:3em\"><h1>The workbench app is not built</h1><p>This server"
                    + " works (the API and the language server are up), but the browser app was not bundled into it. Build it"
                    + " with <code>./gradlew :editors:web:bundle</code> (needs Node.js), then rebuild <code>plca</code>.</p>";

    // ---- responses ------------------------------------------------------------------------------

    private void json(Response response, Callback cb, Object value) {
        bytes(response, cb, 200, "application/json", json.writeValueAsBytes(value));
    }

    private static void text(Response response, Callback cb, int status, String text) {
        bytes(response, cb, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(Response response, Callback cb, int status, String type, byte[] data) {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, type);
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, data.length);
        response.write(true, ByteBuffer.wrap(data), cb);
    }
}
