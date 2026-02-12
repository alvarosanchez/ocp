package com.github.alvarosanchez.ocp.command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine.IVersionProvider;

/**
 * Provides the OCP CLI version from the generated version resource.
 */
public final class OcpVersionProvider implements IVersionProvider {

    private static final String VERSION_RESOURCE_PATH = "/META-INF/ocp/version.txt";

    /**
     * Reads and returns the CLI version for Picocli's {@code --version} option.
     *
     * @return a single-item array containing the current project version
     */
    @Override
    public String[] getVersion() {
        try (var inputStream = OcpVersionProvider.class.getResourceAsStream(VERSION_RESOURCE_PATH)) {
            if (inputStream == null) {
                throw new IllegalStateException("Version resource not found: " + VERSION_RESOURCE_PATH);
            }

            String version = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (version.isEmpty()) {
                throw new IllegalStateException("Version resource is empty: " + VERSION_RESOURCE_PATH);
            }

            return new String[] {version};
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read version resource: " + VERSION_RESOURCE_PATH, e);
        }
    }
}
