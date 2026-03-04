package com.github.alvarosanchez.ocp.command.interactive;

import com.github.alvarosanchez.ocp.model.Profile;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.KeyEventHandler;
import dev.tamboui.widgets.input.TextAreaState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.richTextArea;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textArea;

final class DetailPaneRenderer {

    private DetailPaneRenderer() {
    }

    static Element renderDetailPane(
        NodeRef selectedNode,
        boolean editMode,
        Map<String, Profile> profilesByName,
        Map<String, String> profileParentByName,
        Text selectedFilePreview,
        int previewScrollOffset,
        TextAreaState editorState,
        String detailId,
        String editorId,
        KeyEventHandler handleKeyEvent,
        KeyEventHandler handlePreviewKeyEvent
    ) {
        if (selectedNode == null) {
            return column(
                text("Select a repository, profile, or file on the left.").dim(),
                text("Files open here with syntax-colored preview.").dim()
            )
                .id(detailId)
                .focusable()
                .onKeyEvent(handleKeyEvent);
        }

        if (selectedNode.kind() == NodeKind.REPOSITORY) {
            return column(
                text("Repository").bold().fg(Color.CYAN),
                detailField("Name", selectedNode.repositoryName()),
                detailField("Path", String.valueOf(selectedNode.path())),
                text("Enter to refresh this repository.").dim()
            )
                .id(detailId)
                .focusable()
                .onKeyEvent(handleKeyEvent);
        }

        if (selectedNode.kind() == NodeKind.PROFILE) {
            Profile profile = profilesByName.get(selectedNode.profileName());
            String parentProfileName = profileParentByName.get(selectedNode.profileName());
            return column(
                text("Profile").bold().fg(Color.CYAN),
                detailField("Name", selectedNode.profileName()),
                detailField("Repository", selectedNode.repositoryName()),
                detailField("Path", String.valueOf(selectedNode.path())),
                detailField("Inherits from", parentProfileName == null ? "none" : parentProfileName),
                detailField("Status", profile != null && profile.active() ? "active" : "inactive"),
                detailField("Updates", profile != null && profile.updateAvailable() ? "available" : "up to date"),
                text("Enter or u to activate this profile.").dim()
            )
                .id(detailId)
                .focusable()
                .onKeyEvent(handleKeyEvent);
        }

        if (selectedNode.kind() == NodeKind.DIRECTORY) {
            return column(
                text("Directory").bold().fg(Color.CYAN),
                detailField("Path", String.valueOf(selectedNode.path())),
                text("Space/Enter to expand or collapse in tree.").dim()
            )
                .id(detailId)
                .focusable()
                .onKeyEvent(handleKeyEvent);
        }

        if (editMode) {
            return textArea(editorState)
                .title("Editing: " + selectedNode.path().getFileName())
                .rounded()
                .borderColor(Color.GREEN)
                .focusedBorderColor(Color.GREEN)
                .showLineNumbers()
                .id(editorId)
                .focusable()
                .onKeyEvent(handleKeyEvent)
                .fill();
        }

        return richTextArea(selectedFilePreview)
            .text(scrolledPreviewText(selectedFilePreview, previewScrollOffset))
            .title("Preview: " + selectedNode.path().getFileName())
            .rounded()
            .borderColor(Color.CYAN)
            .focusedBorderColor(Color.GREEN)
            .showLineNumbers()
            .scrollbar()
            .id(detailId)
            .focusable()
            .onKeyEvent(handlePreviewKeyEvent)
            .fill();
    }

    static String detailHint(NodeRef selectedNode, boolean editMode) {
        if (selectedNode == null) {
            return "Detail pane";
        }
        if (selectedNode.kind() == NodeKind.FILE) {
            if (editMode) {
                return "Editing mode: Ctrl+S save, Esc exit";
            }
            return "Press e or Enter to edit selected file | Up/Down/PgUp/PgDn/Home/End scroll preview";
        }
        return "Detail pane";
    }

    static Text plainText(String content) {
        String normalized = content == null ? "" : content;
        String[] splitLines = normalized.split("\\R", -1);
        List<Line> lines = new ArrayList<>(splitLines.length);
        for (String line : splitLines) {
            lines.add(Line.from(Span.raw(line)));
        }
        return Text.from(lines);
    }

    private static Text scrolledPreviewText(Text selectedFilePreview, int previewScrollOffset) {
        List<Line> lines = selectedFilePreview.lines();
        if (lines.isEmpty()) {
            return Text.empty();
        }
        int safeOffset = Math.clamp(lines.size() - 1L, 0, previewScrollOffset);
        if (safeOffset == 0) {
            return selectedFilePreview;
        }
        return Text.from(lines.subList(safeOffset, lines.size()));
    }

    private static Element detailField(String label, String value) {
        return richText(
            Text.from(
                Line.from(
                    Span.styled(label + ": ", Style.EMPTY.bold().fg(Color.LIGHT_YELLOW)),
                    Span.styled(value == null ? "" : value, Style.EMPTY.fg(Color.BRIGHT_WHITE))
                )
            )
        );
    }
}
