package com.github.alvarosanchez.ocp.config;

import com.fasterxml.jackson.annotation.JsonProperty;
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
     * @param description optional profile description
     * @param extendsFrom optional parent profile name
     */
    @Serdeable
    public record ProfileEntry(String name, String description, @JsonProperty("extends_from") String extendsFrom) {

        public ProfileEntry(String name) {
            this(name, null, null);
        }

        public ProfileEntry(String name, String description) {
            this(name, description, null);
        }

        public ProfileEntry {
            if (description != null && description.isBlank()) {
                description = null;
            }
            if (extendsFrom != null && extendsFrom.isBlank()) {
                extendsFrom = null;
            }
        }
    }
}
