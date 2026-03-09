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
        boolean repositoryRefreshable,
        boolean repositoryMigratable,
        boolean repositoryCommitPushAvailable,
        boolean selectedProfileHasParent,
        boolean editMode,
        String editTitle,
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
        if (editMode && editTitle != null) {
            return textArea(editorState)
                .title(editTitle)
                .rounded()
                .borderColor(Color.GREEN)
                .focusedBorderColor(Color.GREEN)
                .showLineNumbers()
                .id(editorId)
                .focusable()
                .onKeyEvent(handleKeyEvent)
                .fill();
        }

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
            List<Element> repositoryElements = new ArrayList<>();
            repositoryElements.add(text("Repository").bold().fg(Color.CYAN));
            repositoryElements.add(detailField("Name", selectedNode.repositoryName()));
            repositoryElements.add(detailField("Path", String.valueOf(selectedNode.path())));
            List<TreeShortcutHints.Shortcut> repositoryShortcuts = new ArrayList<>();
            if (repositoryCommitPushAvailable) {
                repositoryShortcuts.add(TreeShortcutHints.Shortcut.COMMIT_AND_PUSH_REPOSITORY);
            }
            if (repositoryMigratable) {
                repositoryShortcuts.add(TreeShortcutHints.Shortcut.MIGRATE_REPOSITORY);
            }
            if (repositoryRefreshable) {
                repositoryShortcuts.add(TreeShortcutHints.Shortcut.REFRESH_REPOSITORY);
            }
            if (!repositoryShortcuts.isEmpty()) {
                repositoryElements.add(ShortcutHintRenderer.line(repositoryShortcuts));
            }
            return column(
                repositoryElements.toArray(Element[]::new)
            )
                .id(detailId)
                .focusable()
                .onKeyEvent(handleKeyEvent);
        }

        if (selectedNode.kind() == NodeKind.PROFILE) {
            String profileKey = profileKey(selectedNode.repositoryName(), selectedNode.profileName());
            Profile profile = profilesByName.get(profileKey);
            String parentProfileName = profileParentByName.get(profileKey);
            List<Element> profileElements = new ArrayList<>();
            profileElements.add(text("Profile").bold().fg(Color.CYAN));
            profileElements.add(detailField("Name", selectedNode.profileName()));
            profileElements.add(detailField("Repository", selectedNode.repositoryName()));
            profileElements.add(detailField("Path", String.valueOf(selectedNode.path())));
            profileElements.add(detailField("Inherits from", parentProfileName == null ? "none" : parentProfileName));
            profileElements.add(detailField("Status", profile != null && profile.active() ? "active" : "inactive"));
            profileElements.add(detailField("Updates", profile != null && profile.updateAvailable() ? "available" : "up to date"));
            List<TreeShortcutHints.Shortcut> profileShortcuts = new ArrayList<>();
            profileShortcuts.add(TreeShortcutHints.Shortcut.USE_PROFILE);
            if (selectedProfileHasParent) {
                profileShortcuts.add(TreeShortcutHints.Shortcut.GO_PARENT);
            }
            if (repositoryRefreshable) {
                profileShortcuts.add(TreeShortcutHints.Shortcut.REFRESH_REPOSITORY);
            }
            profileElements.add(ShortcutHintRenderer.line(profileShortcuts));
            return column(
                profileElements.toArray(Element[]::new)
            )
                .id(detailId)
                .focusable()
                .onKeyEvent(handleKeyEvent);
        }

        if (selectedNode.kind() == NodeKind.DIRECTORY) {
            return column(
                text("Directory").bold().fg(Color.CYAN),
                detailField("Path", String.valueOf(selectedNode.path())),
                ShortcutHintRenderer.line(List.of(
                    TreeShortcutHints.Shortcut.LEFT_RIGHT_COLLAPSE_EXPAND
                ))
            )
                .id(detailId)
                .focusable()
                .onKeyEvent(handleKeyEvent);
        }

        if (editMode) {
            String fallbackTitle = selectedNode != null && selectedNode.path() != null
                ? "Editing: " + selectedNode.path().getFileName()
                : "Editing";
            return textArea(editorState)
                .title(fallbackTitle)
                .rounded()
                .borderColor(Color.GREEN)
                .focusedBorderColor(Color.GREEN)
                .showLineNumbers()
                .id(editorId)
                .focusable()
                .onKeyEvent(handleKeyEvent)
                .fill();
        }

        Text previewText = scrolledPreviewText(selectedFilePreview, previewScrollOffset);
        return richTextArea(previewText)
            .title(previewTitle(selectedNode))
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

    static String detailHint(NodeRef selectedNode, boolean editMode, boolean editingConfigFile) {
        if (editMode && editingConfigFile) {
            return "Editing mode: Ctrl+S save, Esc exit";
        }
        if (selectedNode == null) {
            return "Detail pane";
        }
        if (selectedNode.kind() == NodeKind.FILE) {
            if (editMode) {
                return "Editing mode: Ctrl+S save, Esc exit";
            }
            if (selectedNode.inherited()) {
                return "Inherited file (read-only). Up/Down/PgUp/PgDn/Home/End scroll preview";
            }
            if (selectedNode.deepMerged()) {
                return "Preview shows resolved deep-merged contents. Press e to edit the profile file | Up/Down/PgUp/PgDn/Home/End scroll preview";
            }
            return "Press e to edit selected file | Up/Down/PgUp/PgDn/Home/End scroll preview";
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

    private static String previewTitle(NodeRef selectedNode) {
        String fileName = String.valueOf(selectedNode.path().getFileName());
        if (selectedNode.deepMerged()) {
            return "Preview: " + fileName + " (deep-merged)";
        }
        return "Preview: " + fileName;
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

    private static String profileKey(String repositoryName, String profileName) {
        return repositoryName + "/" + profileName;
    }
}
