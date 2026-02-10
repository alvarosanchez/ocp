package com.github.alvarosanchez.ocp.model;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Profile definition entry from profile metadata.
 *
 * @param name profile name
 */
@Serdeable
public record ProfileEntry(String name) {
}
