package com.github.alvarosanchez.ocp.model;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

/**
 * Root JSON model for the OCP user configuration file.
 *
 * @param config global OCP options
 * @param repositories user-added repositories
 */
@Serdeable
public record OcpConfigFile(OcpConfigOptions config, List<RepositoryEntry> repositories) {

    /**
     * Creates an OCP configuration instance.
     *
     * @param config global OCP options
     * @param repositories user-added repositories
     */
    public OcpConfigFile {
        config = config == null ? new OcpConfigOptions(true) : config;
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
    }

    /**
     * Global OCP configuration options.
     *
     * @param profileVersionCheck enables repository profile version checks
     * @param activeProfile currently active profile name
     */
    @Serdeable
    public record OcpConfigOptions(boolean profileVersionCheck, String activeProfile) {

        /**
         * Creates configuration options with no active profile selected.
         *
         * @param profileVersionCheck enables repository profile version checks
         */
        public OcpConfigOptions(boolean profileVersionCheck) {
            this(profileVersionCheck, null);
        }

        /**
         * Creates configuration options.
         *
         * @param profileVersionCheck enables repository profile version checks
         * @param activeProfile currently active profile name
         */
        public OcpConfigOptions {
            activeProfile = activeProfile == null || activeProfile.isBlank() ? null : activeProfile;
        }
    }

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
}
