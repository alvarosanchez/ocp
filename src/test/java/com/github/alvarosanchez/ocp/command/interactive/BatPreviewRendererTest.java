package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatPreviewRendererTest {

    @Test
    void highlightReturnsNullWhenPathIsNull() {
        BatPreviewRenderer renderer = new BatPreviewRenderer();

        assertNull(renderer.highlight(null));
    }

    @Test
    void highlightWithContentUsesConfiguredBatExecutable(@TempDir Path tempDir) throws IOException {
        Assumptions.assumeTrue(System.getenv("OCP_BAT_PATH") != null);

        Path file = tempDir.resolve("opencode.json");
        Files.writeString(file, "{}\n");
        BatPreviewRenderer renderer = new BatPreviewRenderer();

        Text highlightedFromFile = renderer.highlight(file);
        assertNotNull(highlightedFromFile);
        assertTrue(flattenText(highlightedFromFile).contains("BAT_FIXTURE_HIT"));

        Text highlightedFromContent = renderer.highlight(file, "{\"x\":1}\n");
        assertNotNull(highlightedFromContent);
        assertTrue(flattenText(highlightedFromContent).contains("BAT_FIXTURE_HIT"));
    }

    @Test
    void highlightWithContentPassesAbsoluteFilePathAsBatFileName(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("opencode.jsonc");
        Files.writeString(file, "{\"theme\":\"dark\"}\n");
        Path batFixture = createBatFixtureThatRequiresFullPath(tempDir.resolve("bat-fixture"), file);
        BatPreviewRenderer renderer = new BatPreviewRenderer(batFixture.toString());

        Text highlighted = renderer.highlight(file, "{\"theme\":\"light\"}\n");

        assertNotNull(highlighted);
        assertTrue(flattenText(highlighted).contains("jsonc-styled"));
    }

    @Test
    void highlightWithContentForJsoncPassesJsonLanguageOverride(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("merged-config.jsonc");
        Files.writeString(file, "{\"theme\":\"dark\"}\n");
        Path batFixture = createBatFixtureThatRequiresLanguageOverride(tempDir.resolve("bat-fixture-language"), file, "json");
        BatPreviewRenderer renderer = new BatPreviewRenderer(batFixture.toString());

        Text highlighted = renderer.highlight(file, "{\"theme\":\"light\"}\n");

        assertNotNull(highlighted);
        assertTrue(flattenText(highlighted).contains("json-language-styled"));
    }

    private static Path createBatFixtureThatRequiresFullPath(Path scriptPath, Path expectedFilePath) throws IOException {
        String escapedPath = expectedFilePath.toAbsolutePath().normalize().toString().replace("\\", "\\\\").replace("\"", "\\\"");
        String script = "#!/bin/sh\n"
            + "expected=\"" + escapedPath + "\"\n"
            + "file_name=\"\"\n"
            + "while [ $# -gt 0 ]; do\n"
            + "  if [ \"$1\" = \"--file-name\" ]; then\n"
            + "    shift\n"
            + "    file_name=\"$1\"\n"
            + "  fi\n"
            + "  shift\n"
            + "done\n"
            + "if [ \"$file_name\" != \"$expected\" ]; then\n"
            + "  exit 64\n"
            + "fi\n"
            + "cat >/dev/null\n"
            + "printf '\\033[31mjsonc-styled\\033[0m\\n'\n";
        Files.writeString(scriptPath, script);
        scriptPath.toFile().setExecutable(true);
        return scriptPath;
    }

    private static Path createBatFixtureThatRequiresLanguageOverride(Path scriptPath, Path expectedFilePath, String expectedLanguage)
        throws IOException {
        String escapedPath = expectedFilePath.toAbsolutePath().normalize().toString().replace("\\", "\\\\").replace("\"", "\\\"");
        String script = "#!/bin/sh\n"
            + "expected_path=\"" + escapedPath + "\"\n"
            + "expected_language=\"" + expectedLanguage + "\"\n"
            + "language=\"\"\n"
            + "file_name=\"\"\n"
            + "while [ $# -gt 0 ]; do\n"
            + "  if [ \"$1\" = \"--language\" ]; then\n"
            + "    shift\n"
            + "    language=\"$1\"\n"
            + "  elif [ \"$1\" = \"--file-name\" ]; then\n"
            + "    shift\n"
            + "    file_name=\"$1\"\n"
            + "  fi\n"
            + "  shift\n"
            + "done\n"
            + "if [ \"$file_name\" != \"$expected_path\" ]; then\n"
            + "  exit 64\n"
            + "fi\n"
            + "if [ \"$language\" != \"$expected_language\" ]; then\n"
            + "  exit 65\n"
            + "fi\n"
            + "cat >/dev/null\n"
            + "printf '\\033[31mjson-language-styled\\033[0m\\n'\n";
        Files.writeString(scriptPath, script);
        scriptPath.toFile().setExecutable(true);
        return scriptPath;
    }

    private static String flattenText(Text text) {
        StringBuilder builder = new StringBuilder();
        for (var line : text.lines()) {
            for (var span : line.spans()) {
                builder.append(span.content());
            }
        }
        return builder.toString();
    }
}
