package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.ContainerElement;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.DialogElement;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.toolkit.event.KeyEventHandler;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommitConfirmDialogRendererTest {

    @Test
    void renderShowsColorizedDiffPreviewAndActions() {
        String diff = buildDiffWithTwentyThreeLines();
        CommitConfirmState state = new CommitConfirmState("my-repo", diff);
        KeyEventHandler keyHandler = event -> EventResult.HANDLED;

        Element element = CommitConfirmDialogRenderer.render(state, keyHandler);

        DialogElement dialog = assertInstanceOf(DialogElement.class, element);
        assertTrue(dialog.isFocusable());
        assertSame(keyHandler, dialog.keyEventHandler());
        assertEquals(132, dialog.preferredSize(-1, -1, null).widthOr(-1));

        List<TextElement> textChildren = textChildren(dialog);
        List<String> contents = textChildren.stream().map(TextElement::content).toList();

        assertTrue(contents.contains("Review changes for repository `my-repo` before commit and push."));
        assertTrue(contents.contains("Diff:"));
        assertTrue(contents.contains("--- a/file.txt"));
        assertTrue(contents.contains("+++ b/file.txt"));
        assertTrue(contents.contains("@@ -1 +1 @@"));
        assertTrue(contents.contains("-old"));
        assertTrue(contents.contains("+new"));
        assertTrue(contents.contains("... diff truncated (3 more lines)"));
        assertTrue(contents.contains("Press [Y] to continue to the commit message, [Esc] to cancel."));

        assertEquals(Color.CYAN, textByContent(textChildren, "--- a/file.txt").getStyle().fg().orElseThrow());
        assertEquals(Color.CYAN, textByContent(textChildren, "+++ b/file.txt").getStyle().fg().orElseThrow());
        assertEquals(Color.CYAN, textByContent(textChildren, "@@ -1 +1 @@").getStyle().fg().orElseThrow());
        assertEquals(Color.RED, textByContent(textChildren, "-old").getStyle().fg().orElseThrow());
        assertEquals(Color.GREEN, textByContent(textChildren, "+new").getStyle().fg().orElseThrow());
    }

    private static List<TextElement> textChildren(DialogElement dialog) {
        List<TextElement> textChildren = new ArrayList<>();
        for (Element child : dialogChildren(dialog)) {
            textChildren.add(assertInstanceOf(TextElement.class, child));
        }
        return textChildren;
    }

    @SuppressWarnings("unchecked")
    private static List<Element> dialogChildren(DialogElement dialog) {
        try {
            Field childrenField = ContainerElement.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            return (List<Element>) childrenField.get(dialog);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to inspect dialog children", e);
        }
    }

    private static TextElement textByContent(List<TextElement> elements, String content) {
        for (TextElement element : elements) {
            if (content.equals(element.content())) {
                return element;
            }
        }
        fail("Expected dialog text line not found: " + content);
        throw new IllegalStateException("Unreachable");
    }

    private static String buildDiffWithTwentyThreeLines() {
        List<String> lines = new ArrayList<>();
        lines.add("\u001B[31m--- a/file.txt\u001B[0m");
        lines.add("\u001B[32m+++ b/file.txt\u001B[0m");
        lines.add("@@ -1 +1 @@");
        lines.add("-old");
        lines.add("+new");
        for (int index = 0; index < 18; index++) {
            lines.add("context-line-" + index);
        }
        return String.join("\n", lines);
    }
}
