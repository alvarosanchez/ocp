package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class InteractiveAppRenderContractTest {

    @Test
    void renderSourceUsesOneThirdTwoThirdsMainPaneSplit() throws Exception {
        Method renderMethod = InteractiveApp.class.getDeclaredMethod("render");
        String source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveApp.java")
        );

        assertTrue(source.contains(".percent(33)"));
        assertTrue(source.contains(".percent(66)"));
        assertEquals("render", renderMethod.getName());
    }

    @Test
    void renderSourcePlacesShortcutPanelBeforeStatusPanel() throws Exception {
        String source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveApp.java")
        );

        int shortcutPanelIndex = source.indexOf("renderShortcutPanel(treeShortcutHints)");
        int statusPanelIndex = source.indexOf("renderStatusPanel()");

        assertTrue(shortcutPanelIndex > 0);
        assertTrue(statusPanelIndex > shortcutPanelIndex);
    }
}
