package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InteractiveClipboardTest {

    private final String originalOsName = System.getProperty("os.name");

    @AfterEach
    void restoreOsName() {
        if (originalOsName == null) {
            System.clearProperty("os.name");
        } else {
            System.setProperty("os.name", originalOsName);
        }
    }

    @Test
    void copyFallsBackToLinuxClipboardGuidanceForUnknownOperatingSystems() {
        System.setProperty("os.name", "Plan9");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> InteractiveClipboard.copy("value"));

        assertTrue(thrown.getMessage().contains("wl-copy"));
        assertTrue(thrown.getMessage().contains("xclip"));
        assertTrue(thrown.getMessage().contains("xsel"));
    }

    @Test
    void copyReportsWindowsSpecificClipboardGuidanceWhenNoCommandIsAvailable() {
        System.setProperty("os.name", "Windows 11");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> InteractiveClipboard.copy("value"));

        assertTrue(thrown.getMessage().contains("clip"));
        assertTrue(thrown.getMessage().contains("Windows session"));
    }

    @Test
    void copyReportsLinuxSpecificClipboardGuidanceWhenNoCommandIsAvailable() {
        System.setProperty("os.name", "Linux");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> InteractiveClipboard.copy("value"));

        assertTrue(thrown.getMessage().contains("wl-copy"));
        assertTrue(thrown.getMessage().contains("xclip"));
        assertTrue(thrown.getMessage().contains("xsel"));
    }
}
