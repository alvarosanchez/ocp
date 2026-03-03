package com.github.alvarosanchez.ocp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.configuration.picocli.PicocliRunner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OcpCommandTest {

    @Test
    void helpSubcommandPrintsUsage() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            int exitCode = PicocliRunner.execute(OcpCommand.class, "help");
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        assertTrue(output.toString().contains("Usage: ocp"));
    }

    @Test
    void versionOptionPrintsProjectVersion() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            int exitCode = PicocliRunner.execute(OcpCommand.class, "--version");
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        String expectedVersion = readExpectedVersion();
        assertTrue(output.toString().contains(expectedVersion));
    }

    @Test
    void noSubcommandFallsBackToUsageInNonInteractiveEnvironment() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        String previousNoUiProperty = System.getProperty("ocp.no.ui");

        try {
            System.setProperty("ocp.no.ui", "1");
            System.setOut(new PrintStream(output));
            int exitCode = PicocliRunner.execute(OcpCommand.class);
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
            if (previousNoUiProperty == null) {
                System.clearProperty("ocp.no.ui");
            } else {
                System.setProperty("ocp.no.ui", previousNoUiProperty);
            }
        }

        assertTrue(output.toString().contains("Usage: ocp"));
    }

    private static String readExpectedVersion() {
        try (var inputStream = OcpCommandTest.class.getResourceAsStream("/META-INF/ocp/version.txt")) {
            assertNotNull(inputStream);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read version resource", e);
        }
    }
}
