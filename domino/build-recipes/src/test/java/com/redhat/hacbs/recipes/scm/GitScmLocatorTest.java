package com.redhat.hacbs.recipes.scm;

import static org.assertj.core.api.Assertions.assertThat;

import com.redhat.hacbs.recipes.BuildRecipe;
import com.redhat.hacbs.recipes.GAV;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GitScmLocatorTest {
    private static final Logger log = Logger.getLogger(GitScmLocatorTest.class);

    @Test
    void lookupScmInfoRelaxNG() {
        TagInfo tag = GitScmLocator.builder().build().resolveTagInfo(GAV.parse("relaxngDatatype:relaxngDatatype:20020414"));
        Assertions.assertNotNull(tag);
        Assertions.assertEquals(tag.getTag(), tag.getHash());
        Assertions.assertEquals(tag.getRepoInfo().getUri(),
                "https://github.com/java-schema-utilities/relaxng-datatype-java.git");
    }

    @Test
    void lookupScmInfoCommonsLang() {
        assertCommonsTag(GitScmLocator.builder().build());
    }

    private void assertCommonsTag(GitScmLocator locator) {
        TagInfo tag = locator.resolveTagInfo(GAV.parse("commons-lang:commons-lang:2.5"));
        Assertions.assertNotNull(tag);
        Assertions.assertEquals(tag.getTag(), "LANG_2_5");
        Assertions.assertNotEquals(tag.getTag(), tag.getHash());
        Assertions.assertEquals(tag.getRepoInfo().getUri(), "https://github.com/apache/commons-lang.git");
    }

    @Test
    void reuseWorkingCopy() {
        final Path gitCloneDir = Path.of("target/GitScmLocatorTest/lookupScmInfoCommonsLang-" + UUID.randomUUID());
        assertThat(gitCloneDir).doesNotExist();

        final String repoUrl = BuildRecipe.DEFAULT_RECIPE_REPO_URL;
        {
            long t1 = System.currentTimeMillis();
            final GitScmLocator locator = GitScmLocator.builder()
                    .setRecipeRepos(List.of(repoUrl))
                    .setGitCloneBaseDir(gitCloneDir)
                    .build();
            assertCommonsTag(locator);

            log.infof("Lookup time with clonig: %d ms", (System.currentTimeMillis() - t1));

            assertThat(gitCloneDir).exists();
            assertThat(gitCloneDir.resolve(
                    GitScmLocator.uriToFileName(repoUrl) + "/.git")).exists();
        }

        // reuse the existing repo
        {
            long t1 = System.currentTimeMillis();
            final GitScmLocator locator = GitScmLocator.builder()
                    .setRecipeRepos(List.of(repoUrl))
                    .setGitCloneBaseDir(gitCloneDir)
                    .build();
            assertCommonsTag(locator);
            log.infof("Lookup time with fetch & reset: %d ms", (System.currentTimeMillis() - t1));
        }
    }

    //test tag mapping heuristics
    @Test
    void runTagHeuristic() {
        runPassingTest("1.0", "1.0", "1.0", "1.0.Alpha1", "1.0.1");
        runPassingTest("1.0", "v1.0", "v1.0", "1.0.Alpha1", "1.0.1");
        runPassingTest("1.0", "v1.0", "v1.0", "1.0.0", "1.0.1");
        runPassingTest("1.0", "v1.0", "v1.0", "1.0.0", "1.0.1");
        runPassingTest("4.9.3", "4.9.3", "antlr4-master-4.9.3", "4.9.3-rc1", "4.9.3");
        runPassingTest("1.0.Final", "1.0", "1.0", "1.1");
        runPassingTest("1.0.Final", "1.0", "1.0", "1.0.a1");
        runFailingTest("1.0", "1.0.Beta1", "1.0.Alpha1");
        runFailingTest("1.0", "1.0.Final", "1.0.Alpha1");
    }

    @Test
    void uriToFileName() {
        assertThat(GitScmLocator.uriToFileName("https://github.com/path/to/report.pdf?download=1#section"))
                .isEqualTo("github.com-path-to-report.pdf-download-1-section");
        assertThat(GitScmLocator.uriToFileName("https://github.com/org/repo.git")).isEqualTo("github.com-org-repo");
        assertThat(GitScmLocator.uriToFileName("file:///C:/Program Files/Some App/app.exe"))
                .isEqualTo("C-Program-Files-Some-App-app.exe");
        assertThat(GitScmLocator.uriToFileName("C:\\Program Files\\Some App\\app.exe"))
                .isEqualTo("C-Program-Files-Some-App-app.exe");
        assertThat(GitScmLocator.uriToFileName("git+ssh://git@github.com:owner/repo.git")).isEqualTo("github.com-owner-repo");
        assertThat(GitScmLocator.uriToFileName("https://example.com/trailing-dot.")).isEqualTo("example.com-trailing-dot");
        assertThat(GitScmLocator.uriToFileName("git@github.com:quarkusio/quarkus.git"))
                .isEqualTo("github.com-quarkusio-quarkus");
    }

    void runPassingTest(String version, String expected, String... tags) {
        Map<String, String> tagMap = new HashMap<>();
        Arrays.stream(tags).forEach(a -> tagMap.put(a, ""));
        Assertions.assertEquals(expected, GitScmLocator.runTagHeuristic(version, tagMap));
    }

    void runFailingTest(String version, String... tags) {
        Map<String, String> tagMap = new HashMap<>();
        Arrays.stream(tags).forEach(a -> tagMap.put(a, ""));
        Assertions.assertThrows(RuntimeException.class, () -> {
            GitScmLocator.runTagHeuristic(version, tagMap);
        });
    }
}
