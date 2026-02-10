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
}
