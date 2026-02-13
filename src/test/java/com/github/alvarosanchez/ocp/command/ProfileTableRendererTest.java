package com.github.alvarosanchez.ocp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.model.Profile;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ProfileTableRendererTest {

    @Test
    void maxTableWidthUsesColumnsEnvironmentVariableWhenPresent() {
        assertEquals(90, ProfileTableRenderer.maxTableWidth(Map.of("COLUMNS", "90")));
        assertEquals(120, ProfileTableRenderer.maxTableWidth(Map.of()));
        assertEquals(120, ProfileTableRenderer.maxTableWidth(Map.of("COLUMNS", "invalid")));
        assertEquals(120, ProfileTableRenderer.maxTableWidth(Map.of("COLUMNS", "0")));
    }

    @Test
    void resolvedMaxTableWidthUsesDetectedTerminalWidthWhenColumnsMissing() {
        assertEquals(200, ProfileTableRenderer.resolvedMaxTableWidth(Map.of(), OptionalInt.of(200)));
    }

    @Test
    void resolvedMaxTableWidthPrefersColumnsEnvironmentVariable() {
        assertEquals(140, ProfileTableRenderer.resolvedMaxTableWidth(Map.of("COLUMNS", "140"), OptionalInt.of(200)));
    }

    @Test
    void resolvedMaxTableWidthFallsBackToDefaultWithoutSources() {
        assertEquals(120, ProfileTableRenderer.resolvedMaxTableWidth(Map.of(), OptionalInt.empty()));
    }

    @Test
    void printWrapsDescriptionAndMessageToFitColumnsLimit() {
        Profile profile = new Profile(
            "team",
            "This is a very long profile description that should wrap cleanly in narrow terminals",
            "repo",
            "git@github.com:acme/repo.git",
            "abcdef1",
            "1 day ago",
            "This commit message is intentionally long to verify table rendering never exceeds the configured terminal width",
            false,
            true,
            false
        );

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(stdout));
            ProfileTableRenderer.print(List.of(profile), Map.of("COLUMNS", "120"));
        } finally {
            System.setOut(originalOut);
        }

        String rendered = removeAnsiCodes(stdout.toString());
        assertTrue(rendered.contains("DESCRIPTION"));
        for (String line : rendered.split("\\R")) {
            if (!line.isEmpty()) {
                assertTrue(line.length() <= 120, "Line exceeds width: " + line);
            }
        }
    }

    @Test
    void printKeepsRepositoryColumnWhenWidthBudgetAllows() {
        Profile profile = new Profile(
            "team",
            "",
            "repo",
            "git@github.com:acme/repo.git",
            "abcdef1",
            "1 day ago",
            "short message",
            false,
            true,
            false
        );

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(stdout));
            ProfileTableRenderer.print(List.of(profile), Map.of("COLUMNS", "120"));
        } finally {
            System.setOut(originalOut);
        }

        String rendered = removeAnsiCodes(stdout.toString());
        assertTrue(rendered.contains("REPOSITORY"));
        assertTrue(rendered.contains(profile.repository()));
    }

    @Test
    void printOmitsRepositoryColumnWhenWidthBudgetIsTooSmall() {
        Profile profile = new Profile(
            "team",
            "Compact profile",
            "repo",
            "ssh://git@bitbucket.oci.oraclecorp.com:7999/~alsansan/opencode-configs.git",
            "abcdef1",
            "1 day ago",
            "Long message to make sure compact mode keeps the table readable",
            false,
            true,
            false
        );

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(stdout));
            ProfileTableRenderer.print(List.of(profile), Map.of("COLUMNS", "100"));
        } finally {
            System.setOut(originalOut);
        }

        String rendered = removeAnsiCodes(stdout.toString());
        assertFalse(rendered.contains("REPOSITORY"));
        assertFalse(rendered.contains(profile.repository()));
        for (String line : rendered.split("\\R")) {
            if (!line.isEmpty()) {
                assertTrue(line.length() <= 100, "Line exceeds width: " + line);
            }
        }
    }

    private static String removeAnsiCodes(String output) {
        return output.replaceAll("\\u001B\\[[;\\d]*m", "");
    }
}
