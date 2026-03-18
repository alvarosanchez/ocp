package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.ContainerElement;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.RichTextElement;
import dev.tamboui.widgets.input.TextAreaState;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DetailPaneRendererTest {

    @Test
    void renderDetailPaneWrapsNullSelectionInDetailsPanel() {
        Element element = DetailPaneRenderer.renderDetailPane(
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Map.of(),
            Map.of(),
            DetailPaneRenderer.plainText(""),
            0,
            new TextAreaState(),
            "detail",
            "editor",
            event -> null,
            event -> null
        );

        Panel panel = assertInstanceOf(Panel.class, element);
        assertEquals("Details", panel.styleAttributes().get("title"));
    }

    @Test
    void renderDetailPaneWrapsRepositorySelectionInDetailsPanel() {
        Element element = DetailPaneRenderer.renderDetailPane(
            NodeRef.repository("repo", Path.of("/tmp/repo")),
            true,
            false,
            false,
            false,
            false,
            false,
            false,
            Map.of(),
            Map.of(),
            DetailPaneRenderer.plainText(""),
            0,
            new TextAreaState(),
            "detail",
            "editor",
            event -> null,
            event -> null
        );

        Panel panel = assertInstanceOf(Panel.class, element);
        assertEquals("Details", panel.styleAttributes().get("title"));
    }


    @Test
    void renderDetailPaneShowsCommaSeparatedParentsInOrder() {
        Element element = DetailPaneRenderer.renderDetailPane(
            NodeRef.profile("repo", "child", Path.of("/tmp/repo/child")),
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            Map.of(),
            Map.of("repo/child", List.of("parent-a", "parent-b")),
            DetailPaneRenderer.plainText(""),
            0,
            new TextAreaState(),
            "detail",
            "editor",
            event -> null,
            event -> null
        );

        Panel panel = assertInstanceOf(Panel.class, element);
        List<Element> panelChildren = childrenOf(panel);
        assertFalse(panelChildren.isEmpty());
        Column column = assertInstanceOf(Column.class, panelChildren.get(0));
        RichTextElement inheritsField = detailField(childrenOf(column), "Inherits from");
        Line line = inheritsField.text().lines().getFirst();
        List<Span> spans = line.spans();
        assertEquals("Inherits from: ", spans.getFirst().content());
        assertEquals("parent-a, parent-b", spans.get(1).content());
    }

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
            "Press e to edit selected file | f creates a new file | d deletes this file | y copies the absolute path | Up/Down/PgUp/PgDn/Home/End scroll preview",
            DetailPaneRenderer.detailHint(file, false)
        );
        assertEquals(
            "Preview shows resolved deep-merged contents. Press e to edit the profile file | f creates a new file | d deletes this file | y copies the absolute path | Up/Down/PgUp/PgDn/Home/End scroll preview",
            DetailPaneRenderer.detailHint(deepMergedFile, false)
        );
        assertEquals(
            "Inherited file (read-only). Press p to open the parent file | f creates a new file in this profile | y copies the absolute path | Up/Down/PgUp/PgDn/Home/End scroll preview",
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
    void detailHintStillUsesFileSpecificMessagingAfterMetadataExpansion() {
        NodeRef file = NodeRef.file("repo", "profile", Path.of("config.json"));
        assertTrue(DetailPaneRenderer.detailHint(file, false).contains("y copies the absolute path"));
    }

    @Test
    void statusDescriptionExplainsRepositoryDirtyMarker() {
        assertEquals(
            "repository has local uncommitted changes (✏)",
            DetailPaneRenderer.statusDescription(NodeRef.repository("repo", Path.of("/tmp/repo")), true, false)
        );
    }

    @Test
    void statusDescriptionCombinesInheritedAndMergedFileStates() {
        assertEquals(
            "inherited from parent profile (read-only)",
            DetailPaneRenderer.statusDescription(NodeRef.inheritedFile("repo", "profile", Path.of("config.json"), "base"), false, false)
        );
        assertEquals(
            "preview shows deep-merged content",
            DetailPaneRenderer.statusDescription(NodeRef.deepMergedFile("repo", "profile", Path.of("config.json")), false, false)
        );
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

        assertInstanceOf(dev.tamboui.toolkit.elements.RichTextAreaElement.class, element);
        Text rendered = DetailPaneRenderer.previewText(styledPreview, 0);

        assertEquals("json", rendered.lines().getFirst().spans().getFirst().content());
        assertEquals(Color.CYAN, rendered.lines().getFirst().spans().getFirst().style().fg().orElseThrow());
        assertTrue(rendered.lines().getFirst().spans().getFirst().style().effectiveModifiers().contains(dev.tamboui.style.Modifier.BOLD));
    }


    @Test
    void renderDetailPaneShowsEditorForFileNodesWithoutRepositoryContext() {
        Element element = DetailPaneRenderer.renderDetailPane(
            NodeRef.file(null, null, Path.of("config.json")),
            false,
            false,
            false,
            false,
            true,
            false,
            false,
            Map.of(),
            Map.of(),
            DetailPaneRenderer.plainText("{}"),
            0,
            new TextAreaState(),
            "detail",
            "editor",
            event -> null,
            event -> null
        );

        assertEquals("TextAreaElement", element.getClass().getSimpleName());
    }

    @Test
    void renderDetailPaneShowsDeepMergedTitleSuffix() {
        Element element = DetailPaneRenderer.renderDetailPane(
            NodeRef.deepMergedFile("repo", "profile", Path.of("opencode.json")),
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            Map.of(),
            Map.of(),
            DetailPaneRenderer.plainText("{}"),
            0,
            new TextAreaState(),
            "detail",
            "editor",
            event -> null,
            event -> null
        );

        assertInstanceOf(dev.tamboui.toolkit.elements.RichTextAreaElement.class, element);
        assertEquals("Preview: opencode.json (deep-merged)", DetailPaneRenderer.previewTitleFor(NodeRef.deepMergedFile("repo", "profile", Path.of("opencode.json"))));
    }

    private static final Field CONTAINER_CHILDREN_FIELD;

    static {
        try {
            Field field = ContainerElement.class.getDeclaredField("children");
            field.setAccessible(true);
            CONTAINER_CHILDREN_FIELD = field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Element> childrenOf(ContainerElement<?> container) {
        try {
            return (List<Element>) CONTAINER_CHILDREN_FIELD.get(container);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Unable to access container children", e);
        }
    }

    private static RichTextElement detailField(List<Element> candidates, String label) {
        for (Element candidate : candidates) {
            if (candidate instanceof RichTextElement richText) {
                Line line = richText.text().lines().getFirst();
                if (line != null && !line.spans().isEmpty()) {
                    if (line.spans().getFirst().content().equals(label + ": ")) {
                        return richText;
                    }
                }
            }
        }
        throw new AssertionError("Detail field '" + label + "' not found");
    }

    @Test
    void detailHintDisablesEditForParentOnlyMergedNode() {
        NodeRef mergedReadOnlyFile = NodeRef.mergedReadOnlyFile(
            "repo",
            "profile",
            Path.of("config.jsonc"),
            "base",
            List.of("base"),
            List.of(Path.of("config.jsonc")),
            true
        );

        assertEquals(
            "Merged parent file preview (read-only). Press p to open the parent file | f creates a new file in this profile | y copies the absolute path | Up/Down/PgUp/PgDn/Home/End scroll preview",
            DetailPaneRenderer.detailHint(mergedReadOnlyFile, false)
        );
        assertEquals(
            "merged from parent profile only (read-only); preview shows deep-merged content",
            DetailPaneRenderer.statusDescription(mergedReadOnlyFile, false, false)
        );
    }

}
