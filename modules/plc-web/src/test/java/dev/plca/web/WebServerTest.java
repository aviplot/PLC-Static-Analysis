package dev.plca.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The workbench server over real HTTP and WebSocket connections. */
class WebServerTest {

    static final Path PROJECTS = Path.of("../plc-semantic/src/test/resources/projects");

    @TempDir
    Path tmp;

    WebServer server;
    Path work;

    @BeforeEach
    void start() throws Exception {
        work = tmp.resolve("work");
        server = WebServer.start(options());
    }

    WebOptions options() {
        return WebOptions.defaults()
                .withPort(0)
                .withWorkDir(tmp.resolve("work"))
                .withLimits(new WebOptions.Limits(8L << 20, 64L << 20, 1000, 100, 4))
                .withLifetimes(Duration.ofSeconds(60), Duration.ofMinutes(10));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A browser: its own cookie jar, its Origin header on state-changing requests. */
    final class Browser {
        final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        String base() {
            return "http://127.0.0.1:" + server.port();
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(URI.create(base() + path)).GET().build(), body());
        }

        HttpResponse<String> send(String method, String path, byte[] data, Map<String, String> headers)
                throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(data))
                    .setHeader("Origin", base());
            headers.forEach(b::setHeader);
            return http.send(b.build(), body());
        }

        JsonObject upload(String name, byte[] zip) throws Exception {
            HttpResponse<String> r = send("POST", "/api/projects", zip, Map.of(WebServer.NAME_HEADER, name));
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            return JsonParser.parseString(r.body()).getAsJsonObject();
        }

        String cookie() {
            return ((CookieManager) http.cookieHandler().orElseThrow())
                    .getCookieStore().getCookies().stream()
                            .map(c -> c.getName() + "=" + c.getValue())
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("");
        }
    }

    static HttpResponse.BodyHandler<String> body() {
        return HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8);
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** The semantic test projects as one zip (as a folder upload of the browser makes it). */
    static byte[] testProjects(String... names) throws IOException {
        Map<String, byte[]> entries = new java.util.TreeMap<>();
        for (String name : names) {
            Path dir = PROJECTS.resolve(name);
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    entries.put(name + "/" + dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                }
            }
        }
        return zip(entries);
    }

    // ---- uploads ------------------------------------------------------------------------------

    @Test
    void uploadKeepsSourcesAndConfigurationOnly() throws Exception {
        Browser b = new Browser();
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("a/MAIN.st", "PROGRAM MAIN VAR x : INT; END_VAR x := 1; END_PROGRAM".getBytes());
        entries.put("a/readme.md", "# hi".getBytes());
        entries.put("a/plca.yaml", "rules:\n  disable: [PLCA-Q001]\n".getBytes());
        entries.put("a/tool.exe", new byte[] {1, 2, 3});
        entries.put("__MACOSX/a/._MAIN.st", new byte[] {0});
        JsonObject p = b.upload("demo", zip(entries));
        assertThat(p.get("files").getAsInt()).isEqualTo(2);
        assertThat(p.get("skipped").getAsInt()).isEqualTo(3);
        String id = p.get("id").getAsString();
        HttpResponse<String> tree = b.get("/api/projects/" + id + "/tree");
        assertThat(tree.body()).contains("a/MAIN.st", "a/plca.yaml").doesNotContain("readme", "tool.exe");
        assertThat(Files.list(work).toList()).hasSize(1);
        Path dir = work.resolve("p-" + id);
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
                    .isEqualTo("rwx------");
        }
    }

    @Test
    void entriesThatLeaveTheDirectoryAreNotWritten() throws Exception {
        Browser b = new Browser();
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        byte[] st = "PROGRAM P END_PROGRAM".getBytes();
        entries.put("ok/P.st", st);
        entries.put("../escape.st", st);
        entries.put("ok/../../escape2.st", st);
        entries.put("/abs.st", st);
        entries.put("C:/drive.st", st);
        entries.put("ok\\..\\..\\back.st", st);
        entries.put("ok/./dot.st", st);
        JsonObject p = b.upload("slip", zip(entries));
        assertThat(p.get("files").getAsInt()).isEqualTo(1);
        assertThat(p.get("skipped").getAsInt()).isEqualTo(6);
        try (Stream<Path> s = Files.walk(tmp)) {
            assertThat(s.filter(f -> f.getFileName().toString().contains("escape")
                                    || f.getFileName().toString().contains("abs")
                                    || f.getFileName().toString().contains("drive")
                                    || f.getFileName().toString().contains("back"))
                            .toList())
                    .isEmpty();
        }
    }

    @Test
    void refusesBadUploads() throws Exception {
        Browser b = new Browser();
        HttpResponse<String> notZip = b.send("POST", "/api/projects", "hello".getBytes(), Map.of());
        assertThat(notZip.statusCode()).isEqualTo(400);
        HttpResponse<String> noSources =
                b.send("POST", "/api/projects", zip(Map.of("a.txt", "x".getBytes())), Map.of());
        assertThat(noSources.statusCode()).isEqualTo(400);
        assertThat(noSources.body()).contains("no PLC sources");

        byte[] big = new byte[9 << 20];
        new java.util.Random(1).nextBytes(big); // incompressible: the zip itself is over the 8 MB upload limit
        HttpResponse<String> tooLarge = b.send("POST", "/api/projects", zip(Map.of("x.st", big)), Map.of());
        assertThat(tooLarge.statusCode()).isEqualTo(400);
        assertThat(tooLarge.body()).contains("larger than 8 MB");

        byte[] zeros = new byte[40 << 20];
        HttpResponse<String> bomb = b.send("POST", "/api/projects", zip(Map.of("x.st", zeros)), Map.of());
        assertThat(bomb.statusCode()).isEqualTo(400);
        assertThat(bomb.body()).contains("zip bomb");

        assertThat(Files.list(work).toList())
                .as("refused uploads leave nothing behind")
                .isEmpty();
    }

    @Test
    void limitsProjectsPerSession() throws Exception {
        Browser b = new Browser();
        byte[] z = zip(Map.of("P.st", "PROGRAM P END_PROGRAM".getBytes()));
        for (int i = 0; i < 4; i++) {
            b.upload("p" + i, z);
        }
        HttpResponse<String> fifth = b.send("POST", "/api/projects", z, Map.of());
        assertThat(fifth.statusCode()).isEqualTo(400);
        assertThat(fifth.body()).contains("at most 4 projects");
    }

    // ---- who may talk to the server -----------------------------------------------------------

    @Test
    void projectsBelongToTheirSession() throws Exception {
        Browser alice = new Browser();
        Browser mallory = new Browser();
        String id = alice.upload("a", testProjects("plant")).get("id").getAsString();
        mallory.get("/api/session");
        assertThat(mallory.get("/api/projects/" + id + "/tree").statusCode()).isEqualTo(404);
        assertThat(mallory.send("DELETE", "/api/projects/" + id, new byte[0], Map.of())
                        .statusCode())
                .isEqualTo(404);
        assertThat(alice.get("/api/projects/" + id + "/tree").statusCode()).isEqualTo(200);
        CompletableFuture<WebSocket> ws = new Lsp(mallory, id).connect();
        assertThat(ws).failsWithin(10, TimeUnit.SECONDS);
    }

    @Test
    void refusesForeignHostsAndOrigins() throws Exception {
        Browser b = new Browser();
        b.get("/");
        HttpResponse<String> origin =
                b.send("POST", "/api/projects", new byte[0], Map.of("Origin", "http://evil.test"));
        assertThat(origin.statusCode()).isEqualTo(403);
        // the JDK client does not let us set Host; a raw request does
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", server.port())) {
            s.getOutputStream()
                    .write("GET /api/session HTTP/1.1\r\nHost: rebind.evil.test\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            String reply = new String(s.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertThat(reply).startsWith("HTTP/1.1 403");
        }
        String id = b.upload("a", testProjects("plant")).get("id").getAsString();
        HttpClient http = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> noOrigin = http.newWebSocketBuilder()
                .header("Cookie", b.cookie())
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/lsp/" + id), new WebSocket.Listener() {});
        assertThat(noOrigin).failsWithin(10, TimeUnit.SECONDS);
    }

    @Test
    void aTokenIsRequiredWhenConfigured() throws Exception {
        server.close();
        server = WebServer.start(options().withToken("s3cret"));
        Browser b = new Browser();
        assertThat(b.get("/").statusCode()).isEqualTo(401);
        assertThat(b.get("/api/session").statusCode()).isEqualTo(401);
        assertThat(b.get("/?token=wrong").statusCode()).isEqualTo(401);
        HttpResponse<String> first = b.get("/?token=s3cret");
        assertThat(first.statusCode()).isEqualTo(303);
        assertThat(first.headers().firstValue("Location").orElseThrow()).doesNotContain("token");
        assertThat(b.get("/").statusCode()).isEqualTo(200);
        assertThat(b.get("/api/session").statusCode()).isEqualTo(200);
        assertThat(server.url()).endsWith("/?token=s3cret");
    }

    @Test
    void securityHeadersAndNoServerVersion() throws Exception {
        HttpResponse<String> r = new Browser().get("/");
        assertThat(r.headers().firstValue("Content-Security-Policy").orElseThrow())
                .contains("default-src 'self'", "connect-src 'self'", "frame-ancestors 'none'");
        assertThat(r.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(r.headers().firstValue("Server")).isEmpty();
        List<String> cookies = r.headers().allValues("Set-Cookie");
        assertThat(cookies).anySatisfy(c -> assertThat(c).contains("plca_session=", "HttpOnly", "SameSite=Strict"));
    }

    // ---- files, reports, compare --------------------------------------------------------------

    @Test
    void filesAndReportsNeverShowTheServerPath() throws Exception {
        Browser b = new Browser();
        String id = b.upload("all", testProjects("plant", "rockwell_line", "plcopen_mixer"))
                .get("id")
                .getAsString();
        HttpResponse<String> main = b.get("/api/projects/" + id + "/file?path=plant/MAIN.st");
        assertThat(main.statusCode()).isEqualTo(200);
        assertThat(main.body()).isEqualTo(Files.readString(PROJECTS.resolve("plant/MAIN.st")));
        assertThat(b.get("/api/projects/" + id + "/file?path=plant/../../../etc/passwd")
                        .statusCode())
                .isEqualTo(404);
        assertThat(b.get("/api/projects/" + id + "/file?path=plant/nope.st").statusCode())
                .isEqualTo(404);
        String workPath = work.toAbsolutePath().toString();
        for (String format : List.of("json", "sarif", "html")) {
            HttpResponse<String> r = b.get("/api/projects/" + id + "/report?format=" + format);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.headers().firstValue("Content-Disposition").orElseThrow())
                    .contains("all-plca." + format);
            assertThat(r.body()).contains("PLCA-").doesNotContain(workPath).doesNotContain(tmp.toString());
        }
        assertThat(b.get("/api/projects/" + id + "/report?format=json").body()).contains("\"file:///project/\"");
        assertThat(b.get("/api/projects/" + id + "/report?format=pdf").statusCode())
                .isEqualTo(400);
    }

    @Test
    void comparesTwoVersions() throws Exception {
        Browser b = new Browser();
        String base = b.upload("v1", testProjects("plant")).get("id").getAsString();
        Map<String, byte[]> v2 = new java.util.TreeMap<>();
        Path plant = PROJECTS.resolve("plant");
        try (Stream<Path> s = Files.list(plant)) {
            for (Path p : s.toList()) {
                v2.put("plant/" + p.getFileName(), Files.readAllBytes(p));
            }
        }
        // v2: a new unused variable in MAIN (a new finding), and functions.st removed (its findings are fixed)
        String main = new String(v2.get("plant/MAIN.st"), StandardCharsets.UTF_8);
        v2.put(
                "plant/MAIN.st",
                main.replaceFirst("(?i)VAR\\b", "VAR\n    iNeverUsed : INT;").getBytes());
        v2.remove("plant/functions.st");
        String head = b.upload("v2", zip(v2)).get("id").getAsString();
        HttpResponse<String> r = b.send(
                "POST",
                "/api/compare",
                ("{\"base\":\"" + base + "\",\"head\":\"" + head + "\"}").getBytes(),
                Map.of("Content-Type", "application/json"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonObject c = JsonParser.parseString(r.body()).getAsJsonObject();
        assertThat(c.get("added").getAsInt()).isPositive();
        JsonArray findings = c.getAsJsonArray("findings");
        assertThat(findings.asList().stream()
                        .map(JsonElement::getAsJsonObject)
                        .filter(f -> f.get("status").getAsString().equals("new"))
                        .map(f -> f.get("message").getAsString()))
                .anySatisfy(m -> assertThat(m).contains("iNeverUsed"));
        JsonArray files = c.getAsJsonArray("files");
        assertThat(files.toString()).contains("{\"path\":\"plant/MAIN.st\",\"change\":\"changed\"");
        assertThat(files.toString()).contains("\"path\":\"plant/functions.st\",\"change\":\"removed\"");
        assertThat(files.toString()).contains("\"path\":\"plant/FB_Base.st\",\"change\":\"unchanged\"");
        assertThat(c.get("unchanged").getAsInt() + c.get("added").getAsInt())
                .isEqualTo(findings.asList().stream()
                        .filter(f ->
                                !f.getAsJsonObject().get("status").getAsString().equals("fixed"))
                        .count());
    }

    // ---- language server over WebSocket -------------------------------------------------------

    /** A JSON-RPC client over the JDK's WebSocket. */
    final class Lsp implements WebSocket.Listener {
        final Browser browser;
        final String id;
        final List<JsonObject> received = new CopyOnWriteArrayList<>();
        final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
        final StringBuilder partial = new StringBuilder();
        WebSocket socket;
        int nextId = 1;

        Lsp(Browser browser, String id) {
            this.browser = browser;
            this.id = id;
        }

        CompletableFuture<WebSocket> connect() {
            return browser.http
                    .newWebSocketBuilder()
                    .header("Origin", browser.base())
                    .header("Cookie", browser.cookie())
                    .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/lsp/" + id), this)
                    .thenApply(w -> socket = w);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                JsonObject m = JsonParser.parseString(partial.toString()).getAsJsonObject();
                partial.setLength(0);
                received.add(m);
                if (m.has("id") && !m.has("method")) {
                    CompletableFuture<JsonObject> f = pending.remove(m.get("id").getAsInt());
                    if (f != null) {
                        f.complete(m);
                    }
                }
            }
            ws.request(1);
            return null;
        }

        synchronized JsonObject request(String method, JsonElement params) throws Exception {
            int n = nextId++;
            JsonObject m = new JsonObject();
            m.addProperty("jsonrpc", "2.0");
            m.addProperty("id", n);
            m.addProperty("method", method);
            m.add("params", params);
            CompletableFuture<JsonObject> f = new CompletableFuture<>();
            pending.put(n, f);
            socket.sendText(m.toString(), true).get(10, TimeUnit.SECONDS);
            return f.get(30, TimeUnit.SECONDS);
        }

        void notify(String method, JsonElement params) throws Exception {
            JsonObject m = new JsonObject();
            m.addProperty("jsonrpc", "2.0");
            m.addProperty("method", method);
            m.add("params", params);
            socket.sendText(m.toString(), true).get(10, TimeUnit.SECONDS);
        }

        JsonObject await(Predicate<JsonObject> p) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < end) {
                for (JsonObject m : received) {
                    if (p.test(m)) {
                        return m;
                    }
                }
                Thread.sleep(20);
            }
            throw new AssertionError("no such message; received " + received);
        }

        JsonObject initialize(String root) throws Exception {
            JsonObject params = JsonParser.parseString("{\"processId\":null,\"capabilities\":{},"
                            + "\"workspaceFolders\":[{\"uri\":\"" + root + "\",\"name\":\"project\"}],"
                            + "\"initializationOptions\":{\"fastDelayMs\":5,\"fullDelayMs\":20,"
                            + "\"config\":\"/etc/elsewhere.yaml\"}}")
                    .getAsJsonObject();
            return request("initialize", params);
        }
    }

    static JsonObject params(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void languageServerOverWebSocket() throws Exception {
        Browser b = new Browser();
        String id = b.upload("all", testProjects("plant", "rockwell_line", "plcopen_mixer"))
                .get("id")
                .getAsString();
        Lsp lsp = new Lsp(b, id);
        lsp.connect().get(10, TimeUnit.SECONDS);
        JsonObject init = lsp.initialize("file:///project/");
        assertThat(init.getAsJsonObject("result")
                        .getAsJsonObject("capabilities")
                        .get("definitionProvider")
                        .getAsBoolean())
                .isTrue();
        lsp.notify("initialized", new JsonObject());

        JsonObject diag = lsp.await(m -> "textDocument/publishDiagnostics"
                        .equals(m.has("method") ? m.get("method").getAsString() : "")
                && m.getAsJsonObject("params").get("uri").getAsString().endsWith("plant/MAIN.st"));
        assertThat(diag.getAsJsonObject("params").get("uri").getAsString()).isEqualTo("file:///project/plant/MAIN.st");

        // definition of a FB type used in MAIN: answers with a project URI
        String main = Files.readString(PROJECTS.resolve("plant/MAIN.st"));
        int at = main.indexOf("FB_Motor");
        assertThat(at).isPositive();
        int line = (int) main.substring(0, at).chars().filter(c -> c == '\n').count();
        int ch = at - main.lastIndexOf('\n', at - 1) - 1;
        JsonObject def = lsp.request(
                "textDocument/definition",
                params("{\"textDocument\":{\"uri\":\"file:///project/plant/MAIN.st\"},\"position\":{\"line\":" + line
                        + ",\"character\":" + (ch + 2) + "}}"));
        assertThat(def.get("result").toString()).contains("file:///project/plant/FB_Motor.st");

        // the ladder of the L5X
        JsonObject diagrams =
                lsp.request("plca/diagrams", params("{\"uri\":\"file:///project/rockwell_line/Line1.L5X\"}"));
        assertThat(diagrams.get("result").toString()).contains("MainProgram/MainRoutine");
        JsonObject page = lsp.request(
                "plca/diagram",
                params("{\"uri\":\"file:///project/rockwell_line/Line1.L5X\",\"name\":\"MainProgram/MainRoutine\","
                        + "\"from\":1,\"count\":2}"));
        JsonObject result = page.getAsJsonObject("result");
        assertThat(result.get("total").getAsInt()).isGreaterThan(2);
        assertThat(result.getAsJsonArray("parts")).hasSize(2);
        assertThat(result.getAsJsonArray("parts")
                        .get(0)
                        .getAsJsonObject()
                        .get("svg")
                        .getAsString())
                .startsWith("<svg")
                .contains("data-range=");

        // each diagram has its text range; a page can start at the part holding a position (a finding)
        JsonObject routine = diagrams.getAsJsonArray("result").get(0).getAsJsonObject();
        assertThat(routine.getAsJsonObject("range")
                        .getAsJsonObject("end")
                        .get("line")
                        .getAsInt())
                .isGreaterThan(routine.getAsJsonObject("range")
                        .getAsJsonObject("start")
                        .get("line")
                        .getAsInt());
        String svg = result.getAsJsonArray("parts")
                .get(1)
                .getAsJsonObject()
                .get("svg")
                .getAsString();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("data-range=\"(\\d+),(\\d+),").matcher(svg);
        assertThat(m.find()).isTrue();
        JsonObject atPage = lsp.request(
                "plca/diagram",
                params("{\"uri\":\"file:///project/rockwell_line/Line1.L5X\",\"name\":\"MainProgram/MainRoutine\","
                        + "\"at\":{\"line\":" + m.group(1) + ",\"character\":" + m.group(2) + "},\"count\":1}"));
        assertThat(atPage.getAsJsonObject("result").get("from").getAsInt()).isEqualTo(2);

        // files outside the project do not exist for this server
        JsonObject outside = lsp.request(
                "textDocument/hover",
                params(
                        "{\"textDocument\":{\"uri\":\"file:///etc/passwd\"},\"position\":{\"line\":0,\"character\":0}}"));
        assertThat(outside.get("result").isJsonNull()).isTrue();

        String all = lsp.received.toString();
        assertThat(all).doesNotContain(work.toAbsolutePath().toString()).doesNotContain(tmp.toString());
        lsp.socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(10, TimeUnit.SECONDS);
    }

    @Test
    void initializeOutsideTheProjectFails() throws Exception {
        Browser b = new Browser();
        String id = b.upload("p", testProjects("plant")).get("id").getAsString();
        Lsp lsp = new Lsp(b, id);
        lsp.connect().get(10, TimeUnit.SECONDS);
        JsonObject init = lsp.initialize("file:///etc/");
        assertThat(init.has("error")).isTrue();
    }

    @Test
    void configurationIsSandboxed() throws Exception {
        Path secret = tmp.resolve("secret.json");
        Files.writeString(secret, "TOP-SECRET-CONTENT");
        Browser b = new Browser();
        Map<String, byte[]> entries = new java.util.TreeMap<>();
        entries.put("MAIN.st", "PROGRAM MAIN VAR x : INT; END_VAR x := 1; END_PROGRAM".getBytes());
        entries.put("plca.yaml", ("baseline: " + secret.toAbsolutePath() + "\n").getBytes());
        String id = b.upload("p", zip(entries)).get("id").getAsString();
        Lsp lsp = new Lsp(b, id);
        lsp.connect().get(10, TimeUnit.SECONDS);
        lsp.initialize("file:///project/");
        lsp.notify("initialized", new JsonObject());
        JsonObject problem = lsp.await(m -> m.has("method")
                && m.get("method").getAsString().equals("textDocument/publishDiagnostics")
                && m.getAsJsonObject("params").get("uri").getAsString().equals("file:///project/plca.yaml")
                && !m.getAsJsonObject("params").getAsJsonArray("diagnostics").isEmpty());
        assertThat(problem.toString()).contains("the baseline must be a file of the project");
        assertThat(lsp.received.toString()).doesNotContain("TOP-SECRET").doesNotContain(work.toString());
        HttpResponse<String> report = b.get("/api/projects/" + id + "/report?format=json");
        assertThat(report.statusCode()).isEqualTo(400);
        assertThat(report.body())
                .contains("baseline must be a file of the project")
                .doesNotContain("TOP-SECRET");
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    @Test
    void projectsAreDeletedWhenTheirSessionEnds() throws Exception {
        Browser b = new Browser();
        String closed = b.upload("closed", testProjects("plant")).get("id").getAsString();
        String idle = b.upload("idle", testProjects("plant")).get("id").getAsString();
        String deleted = b.upload("deleted", testProjects("plant")).get("id").getAsString();
        Lsp lsp = new Lsp(b, closed);
        lsp.connect().get(10, TimeUnit.SECONDS);
        lsp.initialize("file:///project/");
        lsp.socket.sendClose(WebSocket.NORMAL_CLOSURE, "page closed").get(10, TimeUnit.SECONDS);
        assertThat(b.send("DELETE", "/api/projects/" + deleted, new byte[0], Map.of())
                        .statusCode())
                .isEqualTo(200);
        assertThat(work.resolve("p-" + deleted)).doesNotExist();

        long now = System.currentTimeMillis();
        // the closed project waits for the grace period (a reload), the idle one for the idle timeout
        long deadline = System.currentTimeMillis() + 10_000;
        while (server.projectCount() == 2 && System.currentTimeMillis() < deadline) {
            server.sweepNow(now + Duration.ofSeconds(61).toMillis());
            Thread.sleep(20);
        }
        assertThat(work.resolve("p-" + closed)).doesNotExist();
        assertThat(work.resolve("p-" + idle)).exists();
        server.sweepNow(now + Duration.ofMinutes(11).toMillis());
        assertThat(work.resolve("p-" + idle)).doesNotExist();
    }

    @Test
    void stopDeletesUploadsAndStartRemovesLeftovers() throws Exception {
        Browser b = new Browser();
        String id = b.upload("p", testProjects("plant")).get("id").getAsString();
        server.close();
        assertThat(work.resolve("p-" + id)).doesNotExist();
        Path leftover = Files.createDirectories(work.resolve("p-0123456789abcdef0123456789abcdef/x"));
        Files.writeString(leftover.resolve("a.st"), "x");
        Path other = Files.createDirectories(work.resolve("not-ours"));
        server = WebServer.start(options());
        assertThat(work.resolve("p-0123456789abcdef0123456789abcdef")).doesNotExist();
        assertThat(other).exists();
    }

    @Test
    void keepUploadsKeepsThem() throws Exception {
        server.close();
        server = WebServer.start(options().withKeepUploads(true));
        Browser b = new Browser();
        String id = b.upload("p", testProjects("plant")).get("id").getAsString();
        server.sweepNow(System.currentTimeMillis() + Duration.ofDays(1).toMillis());
        server.close();
        assertThat(work.resolve("p-" + id)).exists();
        server = null;
    }

    @Test
    void servesTheAppOrAPlaceholder() throws Exception {
        HttpResponse<String> r = new Browser().get("/");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).containsAnyOf("<div id=\"app\"", "The workbench app is not built");
        // Jetty itself refuses ambiguous paths (400) before they reach us
        assertThat(new Browser().get("/assets/../../etc/passwd").statusCode()).isIn(400, 404);
        assertThat(new Browser().get("/assets/%2e%2e/%2e%2e/etc/passwd").statusCode())
                .isIn(400, 404);
        assertThat(new Browser().get("/secret.txt").statusCode()).isEqualTo(404);
        List<String> rules = new ArrayList<>();
        JsonParser.parseString(new Browser().get("/api/rules").body())
                .getAsJsonArray()
                .forEach(e -> rules.add(e.getAsJsonObject().get("id").getAsString()));
        assertThat(rules).contains("PLCA-Q001", "PLCA-P001");
    }

    @Test
    void cachesOnlyHashedAssets() throws Exception {
        assertThat(WebServer.cacheControl("assets/app.js")).isEqualTo("no-cache");
        assertThat(WebServer.cacheControl("assets/editor.worker.js")).isEqualTo("no-cache");
        assertThat(WebServer.cacheControl("index.html")).isEqualTo("no-cache");
        assertThat(WebServer.cacheControl("assets/atkinson-hyperlegible-latin-400-normal-4XQA3ZPE.woff2"))
                .contains("immutable");
        Browser b = new Browser();
        HttpResponse<String> r = b.get("/");
        if (r.headers().firstValue("ETag").isPresent()) { // the app is bundled into this build
            String etag = r.headers().firstValue("ETag").orElseThrow();
            HttpResponse<String> again = b.http.send(
                    HttpRequest.newBuilder(URI.create(b.base() + "/"))
                            .header("If-None-Match", etag)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(again.statusCode()).isEqualTo(304);
        }
    }

    @Test
    void exportWritesEditedFilesInTheirOriginalEncoding() throws Exception {
        Charset cp1252 = Charset.forName("windows-1252");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] utf8 = "PROGRAM Q (* Größe *) END_PROGRAM".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + utf8.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(utf8, 0, withBom, bom.length, utf8.length);
        Browser b = new Browser();
        String id = b.upload(
                        "enc",
                        zip(Map.of(
                                "a.st", "PROGRAM A (* Größe *) END_PROGRAM".getBytes(cp1252),
                                "q.st", withBom,
                                "c.st", "PROGRAM C (* ä *) END_PROGRAM".getBytes(cp1252))))
                .get("id")
                .getAsString();
        String body = "{\"files\": [{\"path\": \"a.st\", \"text\": \"PROGRAM A (* Höhe *) END_PROGRAM\"},"
                + " {\"path\": \"q.st\", \"text\": \"PROGRAM Q (* Weiß *) END_PROGRAM\"},"
                + " {\"path\": \"c.st\", \"text\": \"PROGRAM C (* a → b *) END_PROGRAM\"}]}";
        HttpResponse<byte[]> r = b.http.send(
                HttpRequest.newBuilder(URI.create(b.base() + "/api/projects/" + id + "/export"))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .setHeader("Origin", b.base())
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).isEqualTo(200);
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(r.body()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                files.put(e.getName(), z.readAllBytes());
            }
        }
        assertThat(files.get("a.st")).isEqualTo("PROGRAM A (* Höhe *) END_PROGRAM".getBytes(cp1252));
        assertThat(new String(files.get("q.st"), StandardCharsets.UTF_8))
                .isEqualTo("\uFEFFPROGRAM Q (* Weiß *) END_PROGRAM");
        assertThat(new String(files.get("c.st"), StandardCharsets.UTF_8))
                .isEqualTo("\uFEFFPROGRAM C (* a → b *) END_PROGRAM");
        String header = URLDecoder.decode(
                r.headers().firstValue(WebServer.REENCODED_HEADER).orElseThrow(), StandardCharsets.UTF_8);
        JsonArray reencoded = JsonParser.parseString(header).getAsJsonArray();
        assertThat(reencoded).hasSize(1);
        JsonObject c = reencoded.get(0).getAsJsonObject();
        assertThat(c.get("path").getAsString()).isEqualTo("c.st");
        assertThat(c.get("original").getAsString()).isEqualTo("windows-1252");
        assertThat(c.get("characters").getAsString()).isEqualTo("→");

        HttpResponse<String> unknown = b.send(
                "POST",
                "/api/projects/" + id + "/export",
                "{\"files\": [{\"path\": \"../x.st\", \"text\": \"\"}]}".getBytes(StandardCharsets.UTF_8),
                Map.of());
        assertThat(unknown.statusCode()).isEqualTo(404);
    }
}
