package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BatPreviewRendererTest {

    @Test
    void highlightReturnsNullWhenPathIsNull() {
        BatPreviewRenderer renderer = new BatPreviewRenderer();

        assertNull(renderer.highlight(null));
    }
}
