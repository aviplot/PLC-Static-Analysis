package dev.plca.devtools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The corpus files left out of the rates and the triage ({@code corpus-noise.txt}): generated code, fuzzer corpora and
 * deliberately invalid test inputs. They are still parsed, so a frontend crash on them is still reported.
 */
final class CorpusNoise {

    static final CorpusNoise NONE = new CorpusNoise(Set.of(), List.of());

    private final Set<String> repos;
    private final List<Pattern> paths;

    private CorpusNoise(Set<String> repos, List<Pattern> paths) {
        this.repos = repos;
        this.paths = paths;
    }

    static CorpusNoise load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return NONE;
        }
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    static CorpusNoise parse(List<String> lines) {
        Set<String> repos = new HashSet<>();
        List<Pattern> paths = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("repo ")) {
                repos.add(repoKey(line.substring(5).strip()));
            } else if (line.startsWith("path ")) {
                paths.add(Pattern.compile(line.substring(5).strip(), Pattern.CASE_INSENSITIVE));
            } else {
                throw new IllegalArgumentException("corpus-noise.txt: expected 'repo …' or 'path …': " + line);
            }
        }
        return new CorpusNoise(Set.copyOf(repos), List.copyOf(paths));
    }

    /** {@code https://github.com/a/b}, {@code github/a/b} and {@code a/b} all name the GitHub repository a/b. */
    static String repoKey(String repo) {
        String r = repo.replaceFirst("^https://", "")
                .replaceFirst("^github\\.com/", "")
                .replaceFirst("^github/", "")
                .replaceFirst("^gitlab\\.com/", "gitlab/")
                .toLowerCase(java.util.Locale.ROOT);
        return r.endsWith("/") ? r.substring(0, r.length() - 1) : r;
    }

    boolean isNoiseRepo(String repoUrl) {
        return repos.contains(repoKey(repoUrl));
    }

    boolean isNoise(String repoUrl, String path) {
        return isNoiseRepo(repoUrl)
                || paths.stream().anyMatch(p -> p.matcher(path).find());
    }
}
