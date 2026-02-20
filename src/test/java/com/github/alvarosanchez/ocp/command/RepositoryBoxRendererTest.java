package com.github.alvarosanchez.ocp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.service.RepositoryService.ConfiguredRepository;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RepositoryBoxRendererTest {

    @Test
    void resolvedMaxBoxWidthUsesColumnsEnvironmentVariableWhenPresent() {
        assertEquals(90, RepositoryBoxRenderer.resolvedMaxBoxWidth(Map.of("COLUMNS", "90"), OptionalInt.of(160)));
        assertEquals(120, RepositoryBoxRenderer.resolvedMaxBoxWidth(Map.of(), OptionalInt.empty()));
        assertEquals(120, RepositoryBoxRenderer.resolvedMaxBoxWidth(Map.of("COLUMNS", "invalid"), OptionalInt.empty()));
        assertEquals(120, RepositoryBoxRenderer.resolvedMaxBoxWidth(Map.of("COLUMNS", "0"), OptionalInt.empty()));
    }

    @Test
    void printWrapsRepositoryDetailsToColumnsLimit() {
        ConfiguredRepository repository = new ConfiguredRepository(
            "repo-with-very-long-name",
            "ssh://git@very.long.company.internal:7999/teams/devops/profiles-and-configurations-with-super-long-identifier.git",
            "/Users/alvaro/.config/ocp/repositories/repo-with-very-long-name/branch-with-very-long-name-and-no-breaks",
            List.of("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta")
        );

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(stdout));
            RepositoryBoxRenderer.print(List.of(repository), Map.of("COLUMNS", "80"));
        } finally {
            System.setOut(originalOut);
        }

        String rendered = removeAnsiCodes(stdout.toString());
        assertTrue(rendered.contains("Name:"));
        assertTrue(rendered.contains("-> repo-with-very-long-name"));
        assertTrue(rendered.contains("URI:"));
        assertTrue(rendered.contains("-> ssh://git@very.long.company.internal:7999/"));
        assertTrue(rendered.contains("Local path:"));
        assertTrue(rendered.contains("Resolved profiles:"));
        assertTrue(hasSpacerRowBetween(rendered, "Name:", "URI:"));
        assertTrue(hasSpacerRowBetween(rendered, "URI:", "Local path:"));
        assertTrue(hasSpacerRowBetween(rendered, "Local path:", "Resolved profiles:"));

        for (String line : rendered.split("\\R")) {
            if (!line.isEmpty()) {
                assertTrue(line.length() <= 80, "Line exceeds width: " + line);
            }
        }
    }

    private static String removeAnsiCodes(String output) {
        return output.replaceAll("\\u001B\\[[;\\d]*m", "");
    }

    private static boolean hasSpacerRowBetween(String rendered, String firstLabel, String secondLabel) {
        String[] lines = rendered.split("\\R");
        int firstIndex = firstLineIndexContaining(lines, firstLabel);
        int secondIndex = firstLineIndexContaining(lines, secondLabel);
        if (firstIndex < 0 || secondIndex <= firstIndex) {
            return false;
        }

        for (int index = firstIndex + 1; index < secondIndex; index++) {
            if (lines[index].matches(".*│\\s+│.*")) {
                return true;
            }
        }
        return false;
    }

    private static int firstLineIndexContaining(String[] lines, String token) {
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains(token)) {
                return index;
            }
        }
        return -1;
    }
}
