package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InteractiveAppPromptDialogWidthTest {

    @Test
    void promptDialogWidthUsesMinimumWidthForShortContent() {
        int width = InteractiveApp.promptDialogWidthForContent(
            "Create repository",
            "Repository name",
            "",
            "Enter next/apply | Backspace delete | Esc cancel",
            120
        );

        assertEquals(56, width);
    }

    @Test
    void promptDialogWidthExpandsForLongContent() {
        int shortWidth = InteractiveApp.promptDialogWidthForContent(
            "Delete",
            "Name",
            "",
            "Enter apply",
            120
        );
        int longWidth = InteractiveApp.promptDialogWidthForContent(
            "Delete repository",
            "Type repository name to confirm: very-long-repository-name",
            "",
            "Enter next/apply | Backspace delete | Esc cancel",
            120
        );

        assertTrue(longWidth > shortWidth);
    }

    @Test
    void promptDialogWidthIsClampedByMaximumWidth() {
        int width = InteractiveApp.promptDialogWidthForContent(
            "Delete repository",
            "Type repository name to confirm: very-long-repository-name",
            "extremely-long-user-input-value-that-would-exceed-terminal-width",
            "Enter next/apply | Backspace delete | Esc cancel",
            64
        );

        assertEquals(64, width);
    }
}
