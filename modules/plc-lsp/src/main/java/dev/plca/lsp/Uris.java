package dev.plca.lsp;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * How the server turns document URIs into files and back. Editors use {@code file:} URIs of the real files
 * ({@link #files()}); the web workbench (M9) shows the client {@code file:///project/…} and keeps the upload directory
 * to itself ({@link #mapped}).
 */
public interface Uris {

    /** The file of a URI, or null when the URI is not one this server serves. */
    Path path(String uri);

    /** The URI of a file of the workspace, by its display path (relative, {@code /}-separated). */
    String uri(Path root, String displayPath);

    /** The file a text names (a path in a message), as the client should see it. */
    default String mask(String text) {
        return text;
    }

    /** {@code file:} URIs of the real files. */
    static Uris files() {
        return new Uris() {
            @Override
            public Path path(String uri) {
                return Convert.path(uri);
            }

            @Override
            public String uri(Path root, String displayPath) {
                return root.resolve(displayPath).toUri().toString();
            }
        };
    }

    /**
     * URIs under {@code prefix} (such as {@code file:///project/}) for the files under {@code root}; anything else, and
     * any path that would leave {@code root}, is not served.
     */
    static Uris mapped(String prefix, Path root) {
        String base = prefix.endsWith("/") ? prefix : prefix + "/";
        Path dir = root.toAbsolutePath().normalize();
        String rootText = dir.toString();
        return new Uris() {
            @Override
            public Path path(String uri) {
                if (uri == null) {
                    return null;
                }
                String rest;
                if (uri.equals(base) || uri.equals(base.substring(0, base.length() - 1))) {
                    rest = "";
                } else if (uri.startsWith(base)) {
                    rest = uri.substring(base.length());
                } else {
                    return null;
                }
                String relative = URLDecoder.decode(rest.replace("+", "%2B"), StandardCharsets.UTF_8);
                if (relative.indexOf('\0') >= 0 || relative.startsWith("/") || relative.contains("\\")) {
                    return null;
                }
                Path p = dir.resolve(relative).normalize();
                return p.startsWith(dir) ? p : null;
            }

            @Override
            public String uri(Path r, String displayPath) {
                StringBuilder sb = new StringBuilder(base);
                String[] segments = displayPath.replace('\\', '/').split("/");
                for (int i = 0; i < segments.length; i++) {
                    if (i > 0) {
                        sb.append('/');
                    }
                    sb.append(encode(segments[i])); // as a URI path segment: spaces, #, %, non-ASCII
                }
                return sb.toString();
            }

            @Override
            public String mask(String text) {
                if (text == null) {
                    return null;
                }
                // both separators: on Windows a path in a message may also be written with '/'
                return text.replace(rootText + java.io.File.separator, "")
                        .replace(rootText + "/", "")
                        .replace(rootText, ".");
            }
        };
    }

    /** Percent-encodes what is not allowed in a URI path segment. */
    private static String encode(String segment) {
        StringBuilder sb = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9'
                    || "-._~!$&'()*+,;=:@".indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%')
                        .append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return sb.toString();
    }
}
