package com.github.alvarosanchez.ocp.model;

/**
 * Render-ready profile view model used by CLI commands.
 *
 * @param name profile name
 * @param description profile description from repository metadata
 * @param repositoryName repository identifier in local configuration
 * @param repository repository URI
 * @param version latest known local short commit SHA
 * @param lastUpdated humanized age of latest commit
 * @param message latest commit message
 * @param updateAvailable whether newer remote commits are available
 * @param active whether this profile is currently active
 * @param versionCheckFailed whether remote update checks failed
 */
public record Profile(
    String name,
    String description,
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
