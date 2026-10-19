package dev.plca.web;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Settings of {@code plca web}.
 *
 * @param host interface to bind; a loopback address unless the workbench is shared (then a token is required)
 * @param port 0 picks a free port
 * @param token required from every client (query {@code ?token=} once, then a cookie), or null on loopback
 * @param keepUploads keep project directories after their session ends (local use)
 * @param workDir parent of the project directories ({@code <tmp>/plca-web} by default)
 * @param grace how long a project outlives its last WebSocket (page reloads)
 * @param idle how long a project lives without any request or connection
 */
public record WebOptions(
        String host,
        int port,
        String token,
        boolean keepUploads,
        Path workDir,
        Limits limits,
        Duration grace,
        Duration idle,
        int threads) {

    /** Upload limits; see {@link ZipExtractor}. */
    public record Limits(
            long maxUploadBytes, long maxExtractedBytes, int maxEntries, int maxRatio, int projectsPerSession) {
        public static Limits defaults() {
            return new Limits(200L << 20, 1L << 30, 100_000, 100, 4);
        }
    }

    public static WebOptions defaults() {
        return new WebOptions(
                "127.0.0.1",
                8080,
                null,
                false,
                Path.of(System.getProperty("java.io.tmpdir"), "plca-web"),
                Limits.defaults(),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    public WebOptions withPort(int p) {
        return new WebOptions(host, p, token, keepUploads, workDir, limits, grace, idle, threads);
    }

    public WebOptions withHost(String h) {
        return new WebOptions(h, port, token, keepUploads, workDir, limits, grace, idle, threads);
    }

    public WebOptions withToken(String t) {
        return new WebOptions(host, port, t, keepUploads, workDir, limits, grace, idle, threads);
    }

    public WebOptions withKeepUploads(boolean k) {
        return new WebOptions(host, port, token, k, workDir, limits, grace, idle, threads);
    }

    public WebOptions withWorkDir(Path d) {
        return new WebOptions(host, port, token, keepUploads, d, limits, grace, idle, threads);
    }

    public WebOptions withLimits(Limits l) {
        return new WebOptions(host, port, token, keepUploads, workDir, l, grace, idle, threads);
    }

    public WebOptions withLifetimes(Duration g, Duration i) {
        return new WebOptions(host, port, token, keepUploads, workDir, limits, g, i, threads);
    }
}
