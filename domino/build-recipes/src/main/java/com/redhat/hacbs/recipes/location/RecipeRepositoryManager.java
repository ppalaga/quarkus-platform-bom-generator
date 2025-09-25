package com.redhat.hacbs.recipes.location;

import com.redhat.hacbs.recipes.util.GitCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.merge.ContentMergeStrategy;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.transport.FetchResult;
import org.jboss.logging.Logger;

/**
 * A recipe database stored in git.
 */
public class RecipeRepositoryManager implements RecipeDirectory {
    private static final Logger log = Logger.getLogger(RecipeRepositoryManager.class);

    public static final String SCM_INFO = "scm-info";
    public static final String BUILD_INFO = "build-info";
    public static final String BUILD_TOOL_INFO = "build-tool-info";
    public static final String REPOSITORY_INFO = "repository-info";
    public static final String DISABLED_PLUGINS = "disabled-plugins";
    private static final String DOMINO_WORKING_BRANCH = "domino-working-branch";

    private final Git git;
    private final String remote;
    private final Path local;
    private final String branch;
    private final Optional<Duration> updateInterval;
    private final RecipeLayoutManager recipeLayoutManager;
    private volatile long lastUpdate = -1;

    public RecipeRepositoryManager(Git git, String remote, Path local, String branch, Optional<Duration> updateInterval) {
        this.git = git;
        this.remote = remote;
        this.local = local;
        this.branch = branch;
        this.updateInterval = updateInterval;
        this.lastUpdate = System.currentTimeMillis();
        this.recipeLayoutManager = new RecipeLayoutManager(local);
    }

    public static RecipeDirectory create(String remote)
            throws GitAPIException, IOException {
        return create(remote, Optional.empty(), Files.createTempDirectory("recipe"));
    }

    public static RecipeDirectory create(String remote, Optional<Duration> updateInterval,
            Path directory) throws GitAPIException, IOException {
        // Allow cloning of another branch via <url>#<branch> format.
        String branch = "main";
        int b = remote.indexOf('#');
        if (b > 0) {
            branch = remote.substring(b + 1);
            remote = remote.substring(0, b);
        }
        return create(remote, branch, updateInterval, directory);
    }

    private static AtomicInteger threadIndex = new AtomicInteger();

    public static RecipeDirectory create(
            String remote,
            String branch,
            Optional<Duration> updateInterval,
            Path directory) throws GitAPIException {

        final CompletableFuture<RecipeDirectory> delegate = new CompletableFuture<>();
        new Thread(() -> {
            try {
                final Git git;
                if (Files.exists(directory.resolve(".git"))) {
                    /* fetch and reset */
                    git = openGit(directory);
                    fetchAndReset(remote, branch, git);
                } else {
                    /* Shallow clone */
                    log.infof("Cloning recipe repo %s to %s", remote, directory);
                    git = Git.cloneRepository()
                            .setBranch(branch)
                            .setDirectory(directory.toFile())
                            .setCredentialsProvider(new GitCredentials())
                            .setDepth(1)
                            .setURI(remote)
                            .call();
                }
                delegate.complete(new RecipeRepositoryManager(git, remote, directory, branch, updateInterval));
            } catch (Exception e) {
                delegate.completeExceptionally(e);
            }
        }, "git-fetch-" + threadIndex.incrementAndGet()).start();
        return new LazyRecipeDirectory(delegate);
    }

    public static RecipeDirectory createLocal(Path directory) throws GitAPIException {
        log.infof("Opening local recipe repo %s", directory);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException(directory + " is not a valid directory");
        }
        try {
            var result = Git.open(directory.toFile());
            return new RecipeRepositoryManager(result, null, directory, result.getRepository().getBranch(), Optional.empty());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open repository " + directory, e);
        }
    }

    /**
     * Returns the directories that contain the recipe information for this specific artifact
     *
     * @param groupId The group id
     * @param artifactId The artifact id
     * @param version The version
     * @return The path match result
     */
    public Optional<RecipePathMatch> getArtifactPaths(String groupId, String artifactId, String version) {
        doUpdate();
        return recipeLayoutManager.getArtifactPaths(groupId, artifactId, version);
    }

    @Override
    public Optional<Path> getBuildPaths(String scmUri, String version) {
        doUpdate();
        return recipeLayoutManager.getBuildPaths(scmUri, version);
    }

    @Override
    public Optional<Path> getRepositoryPaths(String name) {
        doUpdate();
        return recipeLayoutManager.getRepositoryPaths(name);
    }

    @Override
    public List<Path> getAllRepositoryPaths() {
        doUpdate();
        return recipeLayoutManager.getAllRepositoryPaths();
    }

    @Override
    public Optional<Path> getBuildToolInfo(String name) {
        doUpdate();
        return recipeLayoutManager.getBuildToolInfo(name);
    }

    @Override
    public Optional<Path> getDisabledPlugins(String tool) {
        doUpdate();
        return recipeLayoutManager.getDisabledPlugins(tool);
    }

    @Override
    public void update() {
        try {
            git.pull().setContentMergeStrategy(ContentMergeStrategy.THEIRS).setStrategy(MergeStrategy.THEIRS)
                    .call();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
        lastUpdate = System.currentTimeMillis();
    }

    private void doUpdate() {
        if (updateInterval.isEmpty()) {
            return;
        }
        if (lastUpdate + updateInterval.get().toMillis() < System.currentTimeMillis()) {
            synchronized (this) {
                if (lastUpdate + updateInterval.get().toMillis() < System.currentTimeMillis()) {
                    update();
                }
            }
        }
    }

    @Override
    public String toString() {
        return "RecipeRepositoryManager{" +
                ", remote='" + remote + '\'' +
                ", local=" + local +
                ", branch='" + branch + '\'' +
                ", updateInterval=" + updateInterval +
                ", recipeLayoutManager=" + recipeLayoutManager +
                ", lastUpdate=" + lastUpdate +
                '}';
    }

    static Git openGit(Path dir) {
        try {
            return Git.open(dir.toFile());
        } catch (IOException e) {
            log.debug("No git repository in %s", dir, e);
        }
        try {
            ensureDirectoryExistsAndEmpty(dir);
            return Git.init().setDirectory(dir.toFile()).call();
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException("Could not open git repository in " + dir, e);
        }
    }

    static String fetchAndReset(String useUrl, String branch, Git git) {
        final Path dir = git.getRepository().getWorkTree().toPath();
        /* Forget local changes */
        try {
            Set<String> removedFiles = git.clean().setCleanDirectories(true).call();
            if (!removedFiles.isEmpty()) {
                log.warnf("Removed unstaged files %s", removedFiles);
            }
            git.reset().setMode(ResetType.HARD).call();
        } catch (Exception e) {
            log.warnf(e, "Could not forget local changes in %s", dir);
        }

        log.infof("Fetching recipe repo from %s to %s", useUrl, git.getRepository().getWorkTree());
        final String remoteAlias = "origin";
        try {
            ensureRemoteAvailable(useUrl, remoteAlias, git);

            final String remoteRef = "refs/heads/" + branch;
            final FetchResult fetchResult = git.fetch().setRemote(remoteAlias).setRefSpecs(remoteRef).call();
            final String remoteHead = fetchResult.getAdvertisedRef(remoteRef).getObjectId().getName();
            log.infof("Reseting the working copy to %s", remoteHead);
            /* Reset the domino-working-branch */
            git.branchCreate().setName(DOMINO_WORKING_BRANCH).setForce(true).setStartPoint(remoteHead).call();
            git.checkout().setName(DOMINO_WORKING_BRANCH).call();
            git.reset().setMode(ResetType.HARD).setRef(remoteHead).call();
            final Ref ref = git.getRepository().exactRef("HEAD");
            return ref.getObjectId().getName();
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException("Could not fetch and reset " + dir + " from " + useUrl, e);
        }
    }

    static void ensureRemoteAvailable(String useUrl, String remoteAlias, Git git) throws IOException {
        final StoredConfig config = git.getRepository().getConfig();
        boolean save = false;
        final String foundUrl = config.getString("remote", remoteAlias, "url");
        if (!useUrl.equals(foundUrl)) {
            config.setString("remote", remoteAlias, "url", useUrl);
            save = true;
        }
        final String foundFetch = config.getString("remote", remoteAlias, "fetch");
        final String expectedFetch = "+refs/heads/*:refs/remotes/" + remoteAlias + "/*";
        if (!expectedFetch.equals(foundFetch)) {
            config.setString("remote", remoteAlias, "fetch", expectedFetch);
            save = true;
        }
        if (save) {
            config.save();
        }
    }

    private static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    private static final long DELETE_RETRY_MILLIS = 5000L;
    private static final int CREATE_RETRY_COUNT = 256;

    /**
     * If the given directory does not exist, creates it using {@link #ensureDirectoryExists(Path)}. Otherwise
     * recursively deletes all subpaths in the given directory.
     *
     * @param dir the directory to check
     * @throws IOException if the directory could not be created, accessed or its children deleted
     */
    public static void ensureDirectoryExistsAndEmpty(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (DirectoryStream<Path> subPaths = Files.newDirectoryStream(dir)) {
                for (Path subPath : subPaths) {
                    if (Files.isDirectory(subPath)) {
                        deleteDirectory(subPath);
                    } else {
                        Files.delete(subPath);
                    }
                }
            }
        } else {
            ensureDirectoryExists(dir);
        }
    }

    /**
     * Makes sure that the given directory exists. Tries creating {@link #CREATE_RETRY_COUNT} times.
     *
     * @param dir the directory {@link Path} to check
     * @throws IOException if the directory could not be created or accessed
     */
    public static void ensureDirectoryExists(Path dir) throws IOException {
        Throwable toThrow = null;
        for (int i = 0; i < CREATE_RETRY_COUNT; i++) {
            try {
                Files.createDirectories(dir);
                if (Files.exists(dir)) {
                    return;
                }
            } catch (AccessDeniedException e) {
                toThrow = e;
                /* Workaround for https://bugs.openjdk.java.net/browse/JDK-8029608 */
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e1) {
                    Thread.currentThread().interrupt();
                    toThrow = e1;
                }
            } catch (IOException e) {
                toThrow = e;
            }
        }
        if (toThrow != null) {
            throw new IOException(String.format("Could not create directory [%s]", dir), toThrow);
        } else {
            throw new IOException(
                    String.format("Could not create directory [%s] attempting [%d] times", dir, CREATE_RETRY_COUNT));
        }

    }

    /**
     * Deletes a file or directory recursively if it exists.
     *
     * @param directory the directory to delete
     * @throws IOException
     */
    static void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc == null) {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    } else {
                        // directory iteration failed; propagate exception
                        throw exc;
                    }
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (isWindows) {
                        final long deadline = System.currentTimeMillis() + DELETE_RETRY_MILLIS;
                        FileSystemException lastException = null;
                        do {
                            try {
                                Files.delete(file);
                                return FileVisitResult.CONTINUE;
                            } catch (FileSystemException e) {
                                lastException = e;
                            }
                        } while (System.currentTimeMillis() < deadline);
                        throw new IOException(String.format("Could not delete file %s after retrying for %d ms", file,
                                DELETE_RETRY_MILLIS), lastException);
                    } else {
                        Files.delete(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    // try to delete the file anyway, even if its attributes
                    // could not be read, since delete-only access is
                    // theoretically possible
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    public static class LazyRecipeDirectory implements RecipeDirectory {

        private final Future<RecipeDirectory> delegate;

        public LazyRecipeDirectory(Future<RecipeDirectory> delegate) {
            super();
            this.delegate = delegate;
        }

        RecipeDirectory awaitRecipeDirectory() {
            try {
                return delegate.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Optional<RecipePathMatch> getArtifactPaths(String groupId, String artifactId, String version) {
            return awaitRecipeDirectory().getArtifactPaths(groupId, artifactId, version);
        }

        @Override
        public Optional<Path> getBuildPaths(String scmUri, String version) {
            return awaitRecipeDirectory().getBuildPaths(scmUri, version);
        }

        @Override
        public Optional<Path> getRepositoryPaths(String name) {
            return awaitRecipeDirectory().getRepositoryPaths(name);
        }

        @Override
        public Optional<Path> getBuildToolInfo(String name) {
            return awaitRecipeDirectory().getBuildToolInfo(name);
        }

        @Override
        public List<Path> getAllRepositoryPaths() {
            return awaitRecipeDirectory().getAllRepositoryPaths();
        }

        @Override
        public Optional<Path> getDisabledPlugins(String tool) {
            return awaitRecipeDirectory().getDisabledPlugins(tool);
        }

        @Override
        public void update() {
            awaitRecipeDirectory().update();
        }

    }

}
