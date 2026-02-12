package com.github.alvarosanchez.ocp.model;

public record Profile(
    String name,
    String repositoryName,
    String repository,
    String version,
    String lastUpdated,
    String message,
    boolean updateAvailable,
    boolean active,
    boolean versionCheckFailed
) {
}
