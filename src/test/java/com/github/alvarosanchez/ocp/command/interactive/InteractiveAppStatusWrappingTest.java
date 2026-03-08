package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class InteractiveAppStatusWrappingTest {

    @Test
    void wrapStatusLinesReturnsEmptyListForBlankString() {
        assertEquals(List.of(""), InteractiveApp.wrapStatusLines(null, 20));
        assertEquals(List.of(""), InteractiveApp.wrapStatusLines("", 20));
        assertEquals(List.of(""), InteractiveApp.wrapStatusLines("   ", 20));
    }

    @Test
    void wrapStatusLinesDoesNotWrapShortText() {
        assertEquals(List.of("Hello world"), InteractiveApp.wrapStatusLines("Hello world", 20));
    }

    @Test
    void wrapStatusLinesWrapsLongTextAtWordBoundaries() {
        List<String> result = InteractiveApp.wrapStatusLines("This is a very long text that should be wrapped across multiple lines", 15);
        assertEquals(List.of("This is a very", "long text that", "should be", "wrapped across", "multiple lines"), result);
    }

    @Test
    void wrapStatusLinesForcesWrapOnExtraLongWords() {
        List<String> result = InteractiveApp.wrapStatusLines("Short start12345678901234567890 end", 10);
        assertEquals(List.of("Short", "start12345", "6789012345", "67890 end"), result);
    }
}
