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
