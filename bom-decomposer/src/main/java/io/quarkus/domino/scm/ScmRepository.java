package io.quarkus.domino.scm;

import io.quarkus.bom.decomposer.ReleaseOrigin;
import java.util.Objects;

public class ScmRepository implements ReleaseOrigin {

    public static ScmRepository ofUrl(String url) {
        return ofUrl(url, null);
    }

    public static ScmRepository ofUrl(String url, String path) {
        return new ScmRepository(url, url, path);
    }

    public static ScmRepository ofId(String id) {
        return new ScmRepository(id, null, null);
    }

    private final String id;
    private final String url;
    private final String path;

    private ScmRepository(String id, String url, String path) {
        this.id = Objects.requireNonNull(id, "ID is null");
        this.url = url;
        this.path = path;
    }

    public String getId() {
        return id;
    }

    public boolean hasUrl() {
        return url != null && !url.isEmpty();
    }

    @Override
    public boolean isUrl() {
        return hasUrl();
    }

    public String getUrl() {
        if (!hasUrl()) {
            throw new RuntimeException(id + " was not initialized with a URL");
        }
        return url;
    }

    public String getPath() {
        return path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ScmRepository that = (ScmRepository) o;
        return Objects.equals(id, that.id) && Objects.equals(url, that.url) && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, url, path);
    }

    @Override
    public String toString() {
        return id;
    }
}
