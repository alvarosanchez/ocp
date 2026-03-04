package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tamboui.text.Text;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DetailPaneRendererTest {

    @Test
    void detailHintReturnsDefaultForNullSelection() {
        assertEquals("Detail pane", DetailPaneRenderer.detailHint(null, false));
    }

    @Test
    void detailHintReturnsFileSpecificHints() {
        NodeRef file = NodeRef.file("repo", "profile", Path.of("config.json"));

        assertEquals(
            "Press e or Enter to edit selected file | Up/Down/PgUp/PgDn/Home/End scroll preview",
            DetailPaneRenderer.detailHint(file, false)
        );
        assertEquals("Editing mode: Ctrl+S save, Esc exit", DetailPaneRenderer.detailHint(file, true));
    }

    @Test
    void plainTextCreatesTextLinesAndPreservesTrailingLine() {
        Text parsed = DetailPaneRenderer.plainText("alpha\nbeta\n");

        assertEquals(3, parsed.lines().size());
        assertEquals("alpha", parsed.lines().get(0).spans().getFirst().content());
        assertEquals("beta", parsed.lines().get(1).spans().getFirst().content());
        assertEquals("", parsed.lines().get(2).spans().getFirst().content());
    }

    @Test
    void plainTextTreatsNullAsSingleEmptyLine() {
        Text parsed = DetailPaneRenderer.plainText(null);

        assertEquals(1, parsed.lines().size());
        assertEquals("", parsed.lines().getFirst().spans().getFirst().content());
    }
}
