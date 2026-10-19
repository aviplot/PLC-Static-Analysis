package dev.plca.report;

import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.rules.Finding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Line-independent identities of findings, for baselines (M7) and SARIF {@code partialFingerprints}. A fingerprint
 * hashes the rule id, the POU (or file, outside POUs), the whitespace-normalised source text of the finding's lines,
 * and an occurrence counter for otherwise identical findings. Moving code within a file or editing other POUs keeps
 * fingerprints; editing the flagged line changes them.
 */
public final class Fingerprints {

    /** SARIF key; bump the version if the recipe changes. */
    public static final String KEY = "plcaFingerprint/v1";

    private Fingerprints() {}

    /** One fingerprint per reported finding, in the same order. */
    public static List<String> of(AnalysisResult r) {
        return r.findings().stream().map(r::fingerprint).toList();
    }

    /**
     * One fingerprint per finding, in the same order. The occurrence counter runs over {@code findings} in order, so
     * pass all findings of a run in report order to get the fingerprints a baseline records.
     */
    public static List<String> of(List<Finding> findings, SourceRegistry sources) {
        Map<String, Integer> seen = new HashMap<>();
        return findings.stream()
                .map(f -> {
                    SourceText t = sources.text(f.span().textId());
                    String scope = f.pou() != null ? f.pou() : t.file().path();
                    String base = f.ruleId() + "\n" + scope + "\n" + normalisedLines(t, f.span());
                    int n = seen.merge(base, 1, Integer::sum);
                    return sha256(base + "\n" + n);
                })
                .toList();
    }

    private static String normalisedLines(SourceText t, Span span) {
        String text = t.text();
        int start = text.lastIndexOf('\n', span.start() - 1) + 1;
        int end = text.indexOf('\n', span.end());
        String lines = text.substring(start, end < 0 ? text.length() : end);
        return lines.strip().replaceAll("\\s+", " ") + "\n" + text.substring(span.start(), span.end());
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
