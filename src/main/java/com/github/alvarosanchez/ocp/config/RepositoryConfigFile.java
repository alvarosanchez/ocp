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
     * @param extendsFrom optional parent profile name
     */
    @Serdeable
    public record ProfileEntry(String name, @JsonProperty("extends_from") String extendsFrom) {

        /**
         * Creates a profile entry with no parent profile.
         *
         * @param name profile name
         */
        public ProfileEntry(String name) {
            this(name, null);
        }

        /**
         * Creates a profile entry.
         *
         * @param name profile name
         * @param extendsFrom optional parent profile name
         */
        public ProfileEntry {
            extendsFrom = extendsFrom == null || extendsFrom.isBlank() ? null : extendsFrom;
        }
    }
}
