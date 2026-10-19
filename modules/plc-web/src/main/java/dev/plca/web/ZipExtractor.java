package dev.plca.web;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Unpacks an uploaded zip into a fresh project directory, streaming, keeping only the files {@code keep} accepts.
 * Guards against what an archive can do to a server: entry names that leave the directory (zip-slip: {@code ..},
 * absolute paths, drive letters, backslashes, NUL), archives larger than the upload limit, too many entries, more
 * extracted bytes than allowed, and a suspicious compression ratio (zip bombs). Symbolic links cannot be created: every
 * entry becomes a regular file with {@code CREATE_NEW}. Sizes are counted on the bytes actually read, never taken from
 * the headers.
 */
final class ZipExtractor {

    /** Why an upload was refused; the message is shown to the user. */
    static final class UploadException extends Exception {
        private static final long serialVersionUID = 1L;

        UploadException(String message) {
            super(message);
        }
    }

    record Result(List<String> files, int skipped, long bytes) {}

    /**
     * Below this many extracted bytes the compression ratio is not checked (small, very regular sources compress well).
     */
    static final long RATIO_FLOOR = 16L << 20;

    static final int MAX_NAME = 1024;
    static final int MAX_DEPTH = 64;

    private ZipExtractor() {}

    static Result extract(InputStream upload, Path dir, WebOptions.Limits limits, Predicate<String> keep)
            throws UploadException, IOException {
        Counting raw = new Counting(upload, limits.maxUploadBytes());
        List<String> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int entries = 0;
        int skipped = 0;
        long extracted = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > limits.maxEntries()) {
                    throw new UploadException("the archive has more than " + limits.maxEntries() + " entries");
                }
                if (e.isDirectory()) {
                    continue;
                }
                String name = safeName(e.getName());
                if (name == null || !keep.test(name) || !seen.add(name.toLowerCase(Locale.ROOT))) {
                    skipped++;
                    drain(zip, buffer);
                    continue;
                }
                OutputStream out;
                try {
                    Path target = dir.resolve(name).normalize();
                    if (!target.startsWith(dir)) {
                        throw new InvalidPathException(name, "outside the project");
                    }
                    Files.createDirectories(target.getParent());
                    out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (InvalidPathException | FileSystemException ex) {
                    // a name this file system cannot hold, or one that clashes with an earlier entry ("a" and "a/b")
                    skipped++;
                    drain(zip, buffer);
                    continue;
                }
                try (out) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        extracted += n;
                        if (extracted > limits.maxExtractedBytes()) {
                            throw new UploadException(
                                    "the archive unpacks to more than " + (limits.maxExtractedBytes() >> 20) + " MB");
                        }
                        if (extracted > RATIO_FLOOR && extracted > (long) limits.maxRatio() * Math.max(1, raw.count)) {
                            throw new UploadException("the archive is compressed suspiciously well (a zip bomb?)");
                        }
                        out.write(buffer, 0, n);
                    }
                }
                files.add(name);
            }
        } catch (ZipException | IllegalArgumentException ex) {
            // IllegalArgumentException: an entry name that is not valid UTF-8
            throw new UploadException("not a valid zip archive: " + ex.getMessage());
        } catch (Counting.TooLarge ex) {
            throw new UploadException("the upload is larger than " + (limits.maxUploadBytes() >> 20) + " MB");
        }
        if (entries == 0) {
            throw new UploadException("not a zip archive, or an empty one");
        }
        files.sort(null);
        return new Result(files, skipped, extracted);
    }

    private static void drain(ZipInputStream zip, byte[] buffer) throws IOException {
        while (zip.read(buffer) > 0) {
            // skip the entry's bytes; they still count against the upload limit
        }
    }

    /**
     * The entry name as a relative path with {@code /}, or null when it is not one we write: absolute, a drive letter,
     * backslashes, {@code .}/{@code ..} segments, control characters, too long or too deep, or a macOS resource fork.
     */
    static String safeName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME) {
            return null;
        }
        if (name.startsWith("/") || name.contains("\\") || name.length() > 1 && name.charAt(1) == ':') {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20 || name.charAt(i) == 0x7f) {
                return null;
            }
        }
        String[] segments = name.split("/", -1);
        if (segments.length > MAX_DEPTH) {
            return null;
        }
        for (String s : segments) {
            if (s.isEmpty() || s.equals(".") || s.equals("..")) {
                return null;
            }
        }
        if (segments[0].equals("__MACOSX") || segments[segments.length - 1].startsWith("._")) {
            return null;
        }
        return name;
    }

    /** Counts the bytes read from the upload and stops at the limit. */
    private static final class Counting extends FilterInputStream {
        static final class TooLarge extends IOException {
            private static final long serialVersionUID = 1L;
        }

        private final long max;
        long count;

        Counting(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long k = super.skip(n);
            add(k);
            return k;
        }

        private void add(long n) throws TooLarge {
            count += n;
            if (count > max) {
                throw new TooLarge();
            }
        }
    }
}
