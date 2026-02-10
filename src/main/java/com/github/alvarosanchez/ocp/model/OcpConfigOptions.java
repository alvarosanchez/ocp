package com.github.alvarosanchez.ocp.model;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Global OCP configuration options.
 *
 * @param profileVersionCheck enables repository profile version checks
 */
@Serdeable
public record OcpConfigOptions(boolean profileVersionCheck) {
}
