package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.alvarosanchez.ocp.service.ProfileService;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.ContainerElement;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.DialogElement;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.toolkit.event.KeyEventHandler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefreshConflictDialogRendererTest {

    @Test
    void renderRepositoryConflictDialogShowsColorizedDiffPreviewAndActions() {
        String diff = buildDiffWithTwentyThreeLines();
        ProfileService.ProfileRefreshConflictException conflict = repositoryConflict(
            "repo-a",
            "/tmp/repo-a",
            diff
        );
        KeyEventHandler keyHandler = event -> EventResult.HANDLED;

        Element element = RefreshConflictDialogRenderer.render(
            RefreshConflictState.forRepository(conflict),
            keyHandler
        );

        DialogElement dialog = assertInstanceOf(DialogElement.class, element);
        assertTrue(dialog.isFocusable());
        assertSame(keyHandler, dialog.keyEventHandler());
        assertEquals(132, dialog.preferredSize(-1, -1, null).widthOr(-1));

        List<TextElement> textChildren = textChildren(dialog);
        List<String> contents = textChildren.stream().map(TextElement::content).toList();

        assertTrue(contents.contains("Local uncommitted changes detected in repository repo-a."));
        assertTrue(contents.contains("Diff:"));
        assertTrue(contents.contains("--- a/file.txt"));
        assertTrue(contents.contains("+++ b/file.txt"));
        assertTrue(contents.contains("@@ -1 +1 @@"));
        assertTrue(contents.contains("-old"));
        assertTrue(contents.contains("+new"));
        assertTrue(contents.contains("... diff truncated (3 more lines)"));
        assertTrue(contents.contains("Press 1/2/3 to choose"));

        assertEquals(Color.CYAN, textByContent(textChildren, "--- a/file.txt").getStyle().fg().orElseThrow());
        assertEquals(Color.CYAN, textByContent(textChildren, "+++ b/file.txt").getStyle().fg().orElseThrow());
        assertEquals(Color.CYAN, textByContent(textChildren, "@@ -1 +1 @@").getStyle().fg().orElseThrow());
        assertEquals(Color.RED, textByContent(textChildren, "-old").getStyle().fg().orElseThrow());
        assertEquals(Color.GREEN, textByContent(textChildren, "+new").getStyle().fg().orElseThrow());
    }

    @Test
    void renderMergedFilesConflictDialogShowsFilePreviewLimitAndRemainder() {
        List<Path> driftedFiles = new ArrayList<>();
        for (int index = 0; index < 15; index++) {
            driftedFiles.add(Path.of("config-" + index + ".json"));
        }

        ProfileService.ProfileRefreshUserConfigConflictException conflict = mergedFilesConflict(
            "work",
            Path.of("/tmp/opencode"),
            driftedFiles
        );
        KeyEventHandler keyHandler = event -> EventResult.HANDLED;

        Element element = RefreshConflictDialogRenderer.render(
            RefreshConflictState.forMergedFiles(conflict),
            keyHandler
        );

        DialogElement dialog = assertInstanceOf(DialogElement.class, element);
        assertTrue(dialog.isFocusable());
        assertSame(keyHandler, dialog.keyEventHandler());

        List<String> contents = textChildren(dialog).stream().map(TextElement::content).toList();
        assertTrue(contents.contains("Local changes detected in merged active profile files for profile work."));
        assertTrue(contents.contains("Modified files in /tmp/opencode: "));
        assertTrue(contents.contains("- config-0.json"));
        assertTrue(contents.contains("- config-11.json"));
        assertTrue(contents.contains("... and 3 more files"));
        assertTrue(contents.contains("Press 1/2 to choose"));
    }

    private static ProfileService.ProfileRefreshConflictException repositoryConflict(
        String repositoryName,
        String repositoryPath,
        String diff
    ) {
        try {
            Constructor<ProfileService.ProfileRefreshConflictException> constructor =
                ProfileService.ProfileRefreshConflictException.class.getDeclaredConstructor(
                    String.class,
                    String.class,
                    String.class
                );
            constructor.setAccessible(true);
            return constructor.newInstance(repositoryName, repositoryPath, diff);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to instantiate repository refresh conflict", e);
        }
    }

    private static ProfileService.ProfileRefreshUserConfigConflictException mergedFilesConflict(
        String profileName,
        Path targetDirectory,
        List<Path> driftedFiles
    ) {
        try {
            Constructor<ProfileService.ProfileRefreshUserConfigConflictException> constructor =
                ProfileService.ProfileRefreshUserConfigConflictException.class.getDeclaredConstructor(
                    String.class,
                    Path.class,
                    List.class
                );
            constructor.setAccessible(true);
            return constructor.newInstance(profileName, targetDirectory, driftedFiles);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to instantiate merged-files refresh conflict", e);
        }
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
