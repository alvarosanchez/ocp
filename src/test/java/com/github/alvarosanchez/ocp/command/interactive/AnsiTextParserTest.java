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
}
