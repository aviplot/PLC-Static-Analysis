package dev.plca.web;

import dev.plca.analysis.ConfigFile;
import dev.plca.analysis.Workspace;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The uploaded projects: one private directory each under the work directory, owned by the browser session that
 * uploaded it. A project is deleted when its session is done with it (no WebSocket for the grace period after one
 * closed, or no activity for the idle time), on request, and when the server stops; leftovers of a server that did not
 * stop cleanly are removed at start. {@code keepUploads} turns all of that off except explicit deletes.
 */
final class Projects {

    private static final Logger LOG = Logger.getLogger(Projects.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Files besides sources that a project keeps: its configuration and baselines. */
    static boolean extra(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals(ConfigFile.NAME) || name.endsWith(".json");
    }

    static final class Project {
        final String id;
        final String name;
        final String owner;
        final Path dir;
        final List<String> files;
        final int skipped;
        final AtomicInteger sockets = new AtomicInteger();
        volatile long lastActive;
        /** When the last WebSocket closed; 0 while one is open or none ever was. */
        volatile long lastClosed;

        Project(String id, String name, String owner, Path dir, List<String> files, int skipped, long now) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.dir = dir;
            this.files = List.copyOf(files);
            this.skipped = skipped;
            this.lastActive = now;
        }

        void touch(long now) {
            lastActive = now;
        }
    }

    private final WebOptions options;
    private final Path root;
    private final Map<String, Project> byId = new ConcurrentHashMap<>();

    Projects(WebOptions options) throws IOException {
        this.options = options;
        this.root = options.workDir().toAbsolutePath().normalize();
        createPrivate(root, true);
        if (!options.keepUploads()) {
            try (var s = Files.list(root)) {
                for (Path p : s.toList()) {
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                            && p.getFileName().toString().startsWith("p-")) {
                        deleteTree(p);
                    }
                }
            }
        }
    }

    static String randomId(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Unpacks an upload into a new project of {@code owner}. */
    Project create(String owner, String name, InputStream zip, long now)
            throws ZipExtractor.UploadException, IOException {
        if (list(owner).size() >= options.limits().projectsPerSession()) {
            throw new ZipExtractor.UploadException("at most " + options.limits().projectsPerSession()
                    + " projects are open per session; close one first");
        }
        String id = randomId(16);
        Path dir = root.resolve("p-" + id);
        createPrivate(dir, false);
        Workspace probe = new Workspace(dir);
        try {
            ZipExtractor.Result r = ZipExtractor.extract(zip, dir, options.limits(), p -> probe.accepts(p) || extra(p));
            if (r.files().stream().noneMatch(probe::accepts)) {
                throw new ZipExtractor.UploadException(
                        "the archive has no PLC sources (.st, TwinCAT, .scl/.db/.udt, .L5X/.L5K or PLCopen .xml)");
            }
            Project p = new Project(id, clean(name), owner, dir, r.files(), r.skipped(), now);
            byId.put(id, p);
            return p;
        } catch (ZipExtractor.UploadException | IOException | RuntimeException e) {
            deleteTree(dir);
            throw e;
        }
    }

    /** A project of {@code owner}; another session's project does not exist for it. */
    Optional<Project> get(String owner, String id) {
        Project p = id == null ? null : byId.get(id);
        return p != null && p.owner.equals(owner) ? Optional.of(p) : Optional.empty();
    }

    List<Project> list(String owner) {
        List<Project> out = new ArrayList<>();
        for (Project p : byId.values()) {
            if (p.owner.equals(owner)) {
                out.add(p);
            }
        }
        out.sort((a, b) -> Long.compare(a.lastActive, b.lastActive));
        return out;
    }

    boolean delete(String owner, String id) {
        Optional<Project> p = get(owner, id);
        p.ifPresent(this::remove);
        return p.isPresent();
    }

    private void remove(Project p) {
        if (byId.remove(p.id, p)) {
            deleteTree(p.dir);
        }
    }

    /** Deletes projects whose session is gone; called periodically. */
    void sweep(long now) {
        if (options.keepUploads()) {
            return;
        }
        for (Project p : List.copyOf(byId.values())) {
            boolean idle =
                    p.sockets.get() == 0 && now - p.lastActive > options.idle().toMillis();
            boolean closed = p.sockets.get() == 0
                    && p.lastClosed > 0
                    && now - p.lastClosed > options.grace().toMillis();
            if (idle || closed) {
                LOG.fine(() -> "deleting project " + p.id + (closed ? " (closed)" : " (idle)"));
                remove(p);
            }
        }
    }

    /** On shutdown. */
    void deleteAll() {
        if (!options.keepUploads()) {
            List.copyOf(byId.values()).forEach(this::remove);
        }
    }

    int size() {
        return byId.size();
    }

    private static String clean(String name) {
        String n = name == null ? "" : name.strip();
        n = n.replaceAll("[\\p{Cntrl}]", "");
        if (n.isEmpty()) {
            n = "project";
        }
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    private static void createPrivate(Path dir, boolean mayExist) throws IOException {
        if (mayExist && Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            restrict(dir);
            return;
        }
        Files.createDirectories(dir.getParent());
        Files.createDirectory(dir);
        restrict(dir);
    }

    private static void restrict(Path dir) throws IOException {
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not delete " + dir, e);
        }
    }
}
