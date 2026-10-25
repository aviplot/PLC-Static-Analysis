package dev.plca.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.plca.devtools.CorpusCheck.FileResult;
import dev.plca.devtools.CorpusCheck.Parts;
import dev.plca.frontend.st.kit.Frontend;
import java.util.List;
import org.junit.jupiter.api.Test;

class CorpusTriageTest {

    private static FileResult failing(String repo, String path, String format, String... errors) {
        return new FileResult(
                repo,
                path,
                format,
                10,
                false,
                Frontend.Stats.NONE,
                List.of(errors),
                "3:7 " + errors[0],
                List.of(),
                Parts.NONE);
    }

    @Test
    void noiseMatchesReposInAnySpellingAndPathPatterns() {
        CorpusNoise noise = CorpusNoise.parse(List.of(
                "# comment", "", "repo Owner/Generated", "repo gitlab/g/x", "path (^|/)(invalid|negative)(/|$)"));
        assertThat(noise.isNoiseRepo("https://github.com/owner/generated")).isTrue();
        assertThat(noise.isNoiseRepo("https://gitlab.com/g/x")).isTrue();
        assertThat(noise.isNoiseRepo("https://github.com/g/x")).isFalse();
        assertThat(noise.isNoise("https://github.com/a/b", "tests/Invalid/x.st"))
                .isTrue();
        assertThat(noise.isNoise("https://github.com/a/b", "src/FB_InvalidInput.st"))
                .isFalse();
        assertThatThrownBy(() -> CorpusNoise.parse(List.of("owner/name"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clustersByFamilyAndFirstErrorRankedByRepos() {
        List<FileResult> results = List.of(
                failing("r1", "a.TcPOU", "TcPOU", "E1 unexpected 'INTERNAL'", "E2 cascade"),
                failing("r2", "b.TcDUT", "TcDUT", "E1 unexpected 'INTERNAL'"),
                failing("r3", "c.st", "st", "E3 unexpected ','"),
                failing("r3", "d.st", "st", "E3 unexpected ','"),
                failing("r3", "e.st", "st", "E3 unexpected ','"));
        List<CorpusTriage.Cluster> cs = CorpusTriage.clusters(results);
        assertThat(cs).extracting(CorpusTriage.Cluster::family).containsExactly("TwinCAT", "st");
        assertThat(cs.getFirst().repos()).containsExactly("r1", "r2");
        assertThat(cs.get(1).files()).hasSize(3);
        assertThat(CorpusTriage.examples(cs.get(1))).containsExactly("r3/c.st:3:7");
    }
}
