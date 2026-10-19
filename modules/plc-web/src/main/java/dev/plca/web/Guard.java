package dev.plca.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

/**
 * Who may talk to the workbench, checked on every request and WebSocket upgrade.
 *
 * <ul>
 *   <li><b>Host</b>: on a loopback address only {@code localhost}, {@code 127.0.0.1} and {@code [::1]} with our port,
 *       which stops DNS-rebinding pages from reaching a local server.
 *   <li><b>Origin</b>: when a browser sends one, it must be our own (cross-site requests and WebSocket hijacking); a
 *       WebSocket upgrade must send one.
 *   <li><b>Token</b> (when configured): {@code ?token=} on the first page load, then an HttpOnly cookie.
 *   <li><b>Session</b>: an HttpOnly, SameSite=Strict cookie with a random id; projects belong to it.
 * </ul>
 */
final class Guard {

    static final String SESSION = "plca_session";
    static final String TOKEN = "plca_token";

    /** Response headers of every page and API answer. */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'"
            + " data: blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; frame-ancestors"
            + " 'none'; base-uri 'none'; form-action 'self'; object-src 'none'";

    private final boolean loopback;
    private final int port;
    private final String token;

    Guard(String host, int port, String token) {
        this.loopback = isLoopback(host);
        this.port = port;
        this.token = token;
    }

    static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** Null when the request may proceed, else the HTTP status to answer with. */
    Integer check(Request request, boolean upgrade) {
        String host = request.getHeaders().get(HttpHeader.HOST);
        if (loopback && !allowedHost(host)) {
            return 403;
        }
        String origin = request.getHeaders().get(HttpHeader.ORIGIN);
        if (origin != null && !sameOrigin(origin, host, request)) {
            return 403;
        }
        if (upgrade && origin == null) {
            return 403;
        }
        if (token != null && !tokenOk(request)) {
            return 401;
        }
        return null;
    }

    private boolean allowedHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String name : Set.of("localhost", "127.0.0.1", "[::1]")) {
            if (h.equals(name + ":" + port) || port == 80 && h.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameOrigin(String origin, String host, Request request) {
        if (host == null) {
            return false;
        }
        String scheme = request.isSecure() ? "https://" : "http://";
        return origin.equalsIgnoreCase(scheme + host);
    }

    /** The token from the query (first load) or the cookie. */
    boolean tokenOk(Request request) {
        String q = Request.extractQueryParameters(request).getValue("token");
        return equal(q, token) || equal(cookie(request, TOKEN), token);
    }

    /** True when the request carries the token in its query: the page then sets the cookie and drops the query. */
    boolean tokenInQuery(Request request) {
        return token != null && equal(Request.extractQueryParameters(request).getValue("token"), token);
    }

    void setTokenCookie(Response response) {
        Response.putCookie(response, cookie(TOKEN, token));
    }

    private static boolean equal(String a, String b) {
        return a != null
                && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static String cookie(Request request, String name) {
        for (HttpCookie c : Request.getCookies(request)) {
            if (c.getName().equals(name)) {
                return c.getValue();
            }
        }
        return null;
    }

    /** The request's session id; a new one (and its cookie) when it has none or a malformed one. */
    static String session(Request request, Response response) {
        String s = cookie(request, SESSION);
        if (s != null && s.matches("[0-9a-f]{32}")) {
            return s;
        }
        s = Projects.randomId(16);
        Response.putCookie(response, cookie(SESSION, s));
        return s;
    }

    private static HttpCookie cookie(String name, String value) {
        return HttpCookie.build(name, value)
                .path("/")
                .httpOnly(true)
                .sameSite(HttpCookie.SameSite.STRICT)
                .build();
    }

    static void securityHeaders(Response response) {
        var h = response.getHeaders();
        h.put("Content-Security-Policy", CSP);
        h.put("X-Content-Type-Options", "nosniff");
        h.put("Referrer-Policy", "no-referrer");
        h.put("X-Frame-Options", "DENY");
        h.put("Cross-Origin-Opener-Policy", "same-origin");
        h.put("Cross-Origin-Resource-Policy", "same-origin");
        h.put(HttpHeader.CACHE_CONTROL, "no-store");
    }
}
