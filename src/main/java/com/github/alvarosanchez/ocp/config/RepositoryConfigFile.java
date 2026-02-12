package com.github.alvarosanchez.ocp.config;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

/**
 * Root JSON model for repository metadata.
 *
 * @param profiles profiles available in a repository
 */
@Serdeable
public record RepositoryConfigFile(List<ProfileEntry> profiles) {

    /**
     * Creates a repository configuration instance.
     *
     * @param profiles profiles available in a repository
     */
    public RepositoryConfigFile {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    /**
     * Profile definition entry from profile metadata.
     *
     * @param name profile name
     */
    @Serdeable
    public record ProfileEntry(String name) {
    }
}
