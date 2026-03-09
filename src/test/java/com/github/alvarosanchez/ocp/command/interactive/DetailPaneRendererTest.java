package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.RichTextAreaElement;
import dev.tamboui.widgets.input.TextAreaState;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DetailPaneRendererTest {

    @Test
    void detailHintReturnsDefaultForNullSelection() {
        assertEquals("Detail pane", DetailPaneRenderer.detailHint(null, false));
    }

    @Test
    void detailHintReturnsFileSpecificHints() {
        NodeRef file = NodeRef.file("repo", "profile", Path.of("config.json"));
        NodeRef deepMergedFile = NodeRef.deepMergedFile("repo", "profile", Path.of("config.json"));
        NodeRef inheritedFile = NodeRef.inheritedFile("repo", "profile", Path.of("config.json"), "base");

        assertEquals(
            "Press e to edit selected file | Up/Down/PgUp/PgDn/Home/End scroll preview",
            DetailPaneRenderer.detailHint(file, false)
        );
        assertEquals(
            "Preview shows resolved deep-merged contents. Press e to edit the profile file | Up/Down/PgUp/PgDn/Home/End scroll preview",
            DetailPaneRenderer.detailHint(deepMergedFile, false)
        );
        assertEquals(
            "Inherited file (read-only). Up/Down/PgUp/PgDn/Home/End scroll preview",
            DetailPaneRenderer.detailHint(inheritedFile, false)
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

    @Test
    void renderDetailPanePreservesStyledPreviewText() {
        Text styledPreview = Text.from(
            Line.from(
                Span.styled("json", Style.EMPTY.fg(Color.CYAN).bold())
            )
        );

        Element element = DetailPaneRenderer.renderDetailPane(
            NodeRef.file("repo", "profile", Path.of("config.json")),
            false,
            false,
            false,
            false,
            false,
            Map.of(),
            Map.of(),
            styledPreview,
            0,
            new TextAreaState(),
            "detail",
            "editor",
            event -> null,
            event -> null
        );

        RichTextAreaElement richTextArea = assertInstanceOf(RichTextAreaElement.class, element);
        Text rendered = richTextAreaText(richTextArea);

        assertEquals("json", rendered.lines().getFirst().spans().getFirst().content());
        assertEquals(Color.CYAN, rendered.lines().getFirst().spans().getFirst().style().fg().orElseThrow());
        assertTrue(rendered.lines().getFirst().spans().getFirst().style().effectiveModifiers().contains(dev.tamboui.style.Modifier.BOLD));
    }

    private static Text richTextAreaText(RichTextAreaElement richTextArea) {
        try {
            Field textField = RichTextAreaElement.class.getDeclaredField("text");
            textField.setAccessible(true);
            return (Text) textField.get(richTextArea);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to inspect rich text area text", e);
        }
    }
}
