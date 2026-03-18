package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.style.Color;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.Test;

class AnsiTextParserTest {

    private final AnsiTextParser parser = new AnsiTextParser();

    @Test
    void parseReturnsSingleEmptyLineForNullInput() {
        Text parsed = parser.parse(null);

        assertEquals(1, parsed.lines().size());
        assertEquals(1, parsed.lines().getFirst().spans().size());
        assertEquals("", parsed.lines().getFirst().spans().getFirst().content());
    }

    @Test
    void parseKeepsTextAndAppliesAndResetsStyleAcrossLines() {
        Text parsed = parser.parse("\u001B[31mred\u001B[0m\nplain");

        assertEquals(2, parsed.lines().size());
        assertEquals("red", parsed.lines().get(0).spans().getFirst().content());
        assertEquals(Color.indexed(1), parsed.lines().get(0).spans().getFirst().style().fg().orElseThrow());
        assertEquals("plain", parsed.lines().get(1).spans().getFirst().content());
        assertTrue(parsed.lines().get(1).spans().getFirst().style().fg().isEmpty());
    }

    @Test
    void stripAnsiRemovesEscapeSequencesAndNormalizesBlankInput() {
        assertEquals("", AnsiTextParser.stripAnsi(null));
        assertEquals("", AnsiTextParser.stripAnsi("   \t"));
        assertEquals("hello", AnsiTextParser.stripAnsi("\u001B[32mhello\u001B[0m"));
        assertFalse(AnsiTextParser.stripAnsi("\u001B[33mwarning\u001B[0m").contains("\u001B"));
    }
    @Test
    void parseSupportsExtendedColorsAndStyleToggles() {
        Text parsed = parser.parse("\u001B[1;3;4;38;5;196;48;2;12;34;56mstyled\u001B[22;23;24;39;49mplain");

        var styledSpan = parsed.lines().getFirst().spans().get(0);
        var plainSpan = parsed.lines().getFirst().spans().get(1);
        assertEquals("styled", styledSpan.content());
        assertEquals(Color.indexed(196), styledSpan.style().fg().orElseThrow());
        assertTrue(styledSpan.style().bg().isPresent());
        assertTrue(styledSpan.style().effectiveModifiers().toString().contains("BOLD"));
        assertTrue(styledSpan.style().effectiveModifiers().toString().contains("ITALIC"));
        assertTrue(styledSpan.style().effectiveModifiers().toString().contains("UNDERLINED"));
        assertEquals("plain", plainSpan.content());
        assertTrue(plainSpan.style().fg().isEmpty());
        assertTrue(plainSpan.style().bg().isEmpty());
    }

    @Test
    void parseTreatsInvalidOrIncompleteEscapeSequencesAsLiteralText() {
        Text parsed = parser.parse("prefix\u001B[38;2;1;2broken\u001Bnot-sgr");

        assertEquals(1, parsed.lines().size());
        assertEquals("prefix\u001B[38;2;1;2broken\u001Bnot-sgr", parsed.lines().getFirst().spans().getFirst().content());
    }

    @Test
    void parseSupportsBrightAndResettableModifiers() {
        Text parsed = parser.parse("\u001B[90;100;5;7;8;9mbright\u001B[25;27;28;29mclear");

        var brightSpan = parsed.lines().getFirst().spans().get(0);
        var clearSpan = parsed.lines().getFirst().spans().get(1);
        assertEquals("bright", brightSpan.content());
        assertEquals(Color.indexed(8), brightSpan.style().fg().orElseThrow());
        assertEquals(Color.indexed(8), brightSpan.style().bg().orElseThrow());
        assertTrue(brightSpan.style().effectiveModifiers().toString().contains("SLOW_BLINK"));
        assertTrue(brightSpan.style().effectiveModifiers().toString().contains("REVERSED"));
        assertTrue(brightSpan.style().effectiveModifiers().toString().contains("HIDDEN"));
        assertTrue(brightSpan.style().effectiveModifiers().toString().contains("CROSSED_OUT"));
        assertEquals("clear", clearSpan.content());
        assertFalse(clearSpan.style().effectiveModifiers().toString().contains("SLOW_BLINK"));
        assertFalse(clearSpan.style().effectiveModifiers().toString().contains("REVERSED"));
        assertFalse(clearSpan.style().effectiveModifiers().toString().contains("HIDDEN"));
        assertFalse(clearSpan.style().effectiveModifiers().toString().contains("CROSSED_OUT"));
    }

}
