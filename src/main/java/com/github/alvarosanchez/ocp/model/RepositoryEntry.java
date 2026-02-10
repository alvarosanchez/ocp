package com.github.alvarosanchez.ocp.model;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Repository registration entry from OCP configuration.
 *
 * @param name repository display name
 * @param uri remote repository URI
 * @param localPath local filesystem path where the repository is stored
 */
@Serdeable
public record RepositoryEntry(String name, String uri, String localPath) {
}
