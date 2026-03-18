package com.github.alvarosanchez.ocp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tamboui.text.Text;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CliTest {

    @AfterEach
    void resetCliState() {
        Cli.init();
        Cli.consumeStartupNotice();
    }

    @Test
    void highlightedNoticeTextPreservesLinesAndHighlightsVersionsAndUpgradeCommand() {
        Text text = Cli.highlightedNoticeText("Upgrade to 1.2.3\nbrew upgrade ocp");

        assertEquals(2, text.lines().size());
        assertEquals("Upgrade to 1.2.3", flattenLine(text, 0));
        assertEquals("brew upgrade ocp", flattenLine(text, 1));
        assertTrue(text.lines().get(0).spans().size() >= 2);
        assertTrue(text.lines().get(1).spans().size() >= 1);
    }

    @Test
    void highlightedNoticeTextTreatsNullAsSingleEmptyLine() {
        Text text = Cli.highlightedNoticeText(null);

        assertEquals(1, text.lines().size());
        assertEquals("", flattenLine(text, 0));
    }

    @Test
    void setStartupNoticeNormalizesBlankMessagesToNull() {
        Cli.setStartupNotice("   ");

        assertNull(Cli.consumeStartupNotice());
    }

    @Test
    void infoWithCodeHighlightsPrintsPlainMessageWhenAnsiIsDisabled() {
        Cli.init();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            Cli.infoWithCodeHighlights("Version 1.2.3 available");
        } finally {
            System.setOut(originalOut);
        }

        assertTrue(output.toString().contains("Version 1.2.3 available"));
    }

    @Test
    void errorPrintsLabelAndMessageToStandardError() {
        Cli.init();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;

        try {
            System.setErr(new PrintStream(error));
            Cli.error("boom");
        } finally {
            System.setErr(originalErr);
        }

        assertTrue(error.toString().contains("Error:"));
        assertTrue(error.toString().contains("boom"));
    }

    @Test
    void beginTransientInfoReturnsFalseWithoutConsole() {
        assumeTrue(System.console() == null);
        assertFalse(Cli.beginTransientInfo("Checking latest versions..."));
    }

    @Test
    void endTransientInfoDoesNothingWhenLineWasNotShown() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            Cli.endTransientInfo(false);
        } finally {
            System.setOut(originalOut);
        }

        assertEquals("", output.toString());
    }

    @Test
    void highlightedNoticeLineKeepsPlainTextWhenNoMatchesExist() {
        String flattened = flattenLine(Cli.highlightedNoticeText("nothing special here"), 0);

        assertEquals("nothing special here", flattened);
    }

    private static String flattenLine(Text text, int index) {
        StringBuilder builder = new StringBuilder();
        for (var span : text.lines().get(index).spans()) {
            builder.append(span.content());
        }
        return builder.toString();
    }
}
