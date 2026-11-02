package com.legacyaudit.engine.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legacyaudit.model.config.AuditConfig.GitRepo;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Git sources with a local bare repository as the remote, as the Python tests did (test_git_source.py). */
class GitSourceTest {

    @TempDir
    Path tmp;

    static String git(Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com",
                "-c", "init.defaultBranch=main", "-c", "core.autocrlf=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", args) + ": " + out);
        }
        return out.strip();
    }

    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** A work tree with three files and its bare copy under remote/team/orders.git. */
    Path[] bareRepo() throws Exception {
        Path work = tmp.resolve("work");
        write(work.resolve("app/build.xml"), "<project name='a'/>\n");
        write(work.resolve("app/src/A.java"), "class A {}\n");
        write(work.resolve("batch/run.ksh"), "#!/bin/ksh\necho run\n");
        git(work, "init", "-q", "-b", "main");
        git(work, "add", ".");
        git(work, "commit", "-q", "-m", "first");
        Path bare = tmp.resolve("remote/team/orders.git");
        Files.createDirectories(bare.getParent());
        git(tmp, "clone", "-q", "--bare", work.toString(), bare.toString());
        return new Path[] {work, bare};
    }

    LoadedConfig config(Path bare, String extra) throws IOException {
        String yaml = "application:\n  name: git-sample\nsource:\n  type: git\n  repos:\n    - url: '" + bare.toString().replace('\\', '/')
                + "'\n      ref: main\n" + extra + "  cache_dir: '" + tmp.resolve("clone-cache").toString().replace('\\', '/') + "'\n"
                + "output_dir: '" + tmp.resolve("ws/audit-output").toString().replace('\\', '/') + "'\n";
        write(tmp.resolve("ws/audit.yaml"), yaml);
        return ConfigLoader.load(tmp.resolve("ws/audit.yaml"));
    }

    @Test
    void cloneScanRecordsTheCommitAndFetchesNewCommits() throws Exception {
        Path[] repo = bareRepo();
        LoadedConfig loaded = config(repo[1], "");
        Scanner.Result first = Scanner.run(loaded, Scanner.Options.defaults());
        String sha = git(repo[0], "rev-parse", "HEAD");
        assertEquals(sha, first.run().sources.get(0).commit());
        assertEquals("git", first.run().sources.get(0).type());
        assertEquals(List.of("app/build.xml", "app/src/A.java", "batch/run.ksh"),
                first.inventory().stream().map(r -> r.path).sorted().toList());
        Path clone = tmp.resolve("clone-cache/local/team/orders@main");
        assertTrue(Files.isDirectory(clone.resolve(".git")));
        assertTrue(Files.exists(clone.resolve(".git/" + GitClones.USED_FILE)));

        write(repo[0].resolve("batch/two.ksh"), "echo two\n");
        git(repo[0], "add", ".");
        git(repo[0], "commit", "-q", "-m", "second");
        git(repo[0], "push", "-q", repo[1].toString(), "main");
        Scanner.Result second = Scanner.run(loaded, Scanner.Options.defaults());
        assertEquals(git(repo[0], "rev-parse", "HEAD"), second.run().sources.get(0).commit());
        assertEquals(1, second.run().filesAnalyzed);
    }

    @Test
    void subPathsAndAnotherBranchOnce() throws Exception {
        Path[] repo = bareRepo();
        git(repo[0], "checkout", "-q", "-b", "release");
        write(repo[0].resolve("app/src/B.java"), "class B {}\n");
        git(repo[0], "add", ".");
        git(repo[0], "commit", "-q", "-m", "second");
        git(repo[0], "push", "-q", repo[1].toString(), "release");

        LoadedConfig sub = config(repo[1], "      paths: [batch]\n");
        assertEquals(List.of("batch/run.ksh"), Scanner.run(sub, Scanner.Options.defaults()).inventory().stream().map(r -> r.path).toList());

        LoadedConfig loaded = config(repo[1], "");
        LoadedConfig release = new LoadedConfig(loaded.config().withGitRef("release"), loaded.path(), loaded.baseDir(), loaded.hash());
        Scanner.Result r = Scanner.run(release, Scanner.Options.defaults());
        assertEquals("release", r.run().sources.get(0).ref());
        assertTrue(r.inventory().stream().anyMatch(f -> f.path.equals("app/src/B.java")));

        Path cache = tmp.resolve("clone-cache");
        List<GitClones.Clone> clones = GitClones.list(cache, true);
        assertEquals(List.of("main", "release"), clones.stream().map(GitClones.Clone::ref).toList());
        GitClones.Clone rel = clones.get(1);
        assertTrue(rel.size() > 0);
        assertTrue(rel.key().endsWith("orders@release"));
        assertTrue(assertThrows(ConfigException.class, () -> GitClones.remove(cache, "../../etc")).getMessage().contains("No clone"));
        GitClones.remove(cache, rel.key());
        GitClones.remove(cache, clones.get(0).key());
        assertTrue(GitClones.list(cache, false).isEmpty());
        assertFalse(Files.exists(cache.resolve("local")));
    }

    @Test
    void aFailureIsAPlainMessageWithoutTheToken() throws Exception {
        LoadedConfig loaded = config(tmp.resolve("does-not-exist.git"), "");
        assertTrue(assertThrows(ConfigException.class, () -> Scanner.run(loaded, Scanner.Options.defaults()))
                .getMessage().contains("git fetch failed"));
    }

    @Test
    void credentialsAndTokenNeverReachGit() {
        String token = "ghp_SECRET_token_value_123456";
        String url = "https://user:" + token + "@git.example.com/team/orders.git";
        assertEquals("https://git.example.com/team/orders.git", GitClones.stripCredentials(url));
        assertEquals(List.of("git.example.com", "team", "orders"), GitClones.repoSlug(url));
        Map<String, String> env = GitClones.gitEnv(token);
        assertEquals("http.extraHeader", env.get("GIT_CONFIG_KEY_0"));
        assertFalse(env.get("GIT_CONFIG_VALUE_0").contains(token));
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
    }

    @Test
    void cacheFolderNames() {
        assertEquals(List.of("github.com", "acme", "billing"), GitClones.repoSlug("https://github.com/acme/billing.git"));
        assertEquals(List.of("gitlab.example.com", "sub", "app"), GitClones.repoSlug("git@gitlab.example.com:group/sub/app.git"));
        assertEquals(List.of("dev.azure.com", "_git", "repo"), GitClones.repoSlug("https://dev.azure.com/org/proj/_git/repo"));
        assertEquals(List.of("local", "legacy", "orders"), GitClones.repoSlug("C:\\repos\\legacy\\orders.git"));
        assertEquals(List.of("local", "git", "orders"), GitClones.repoSlug("file:///srv/git/orders.git"));
        assertEquals(tmp.resolve("h").resolve("o").resolve("r@feature_x"),
                GitClones.cloneDir(new GitRepo("https://h:8443/o/r.git", "feature/x", List.of(), null, null), tmp));
        assertTrue(assertThrows(ConfigException.class, () -> GitClones.checkRef("-c core.x=y")).getMessage().contains("not a branch"));
        assertEquals("release/2.1", GitClones.checkRef(" release/2.1 "));
    }

    @Test
    void purgeDeletesTheCache() throws Exception {
        Path cache = tmp.resolve("c");
        write(cache.resolve("local/o/r@main/f.txt"), "x");
        GitClones.purge(cache);
        assertFalse(Files.exists(cache));
    }
}
