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
        boolean selectedRepositoryHasLocalChanges,
        boolean selectedRepositoryInspectionFailed,
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
            List<Element> repositoryElements = new ArrayList<>();
            repositoryElements.add(text("Repository").bold().fg(Color.CYAN));
            repositoryElements.add(detailField("Name", selectedNode.repositoryName()));
            repositoryElements.add(detailField("Path", String.valueOf(selectedNode.path())));
            repositoryElements.add(detailField("Indicators", statusDescription(selectedNode, selectedRepositoryHasLocalChanges, selectedRepositoryInspectionFailed)));
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
            profileElements.add(detailField("Indicators", statusDescription(selectedNode, selectedRepositoryHasLocalChanges, selectedRepositoryInspectionFailed)));
            profileElements.add(detailField("Description", profile == null || profile.description() == null || profile.description().isBlank() ? "none" : profile.description()));
            profileElements.add(detailField("Inherits from", parentProfileName == null ? "none" : parentProfileName));
            profileElements.add(detailField("Status", profile != null && profile.active() ? "active" : "inactive"));
            profileElements.add(detailField("Updates", profile != null && profile.updateAvailable() ? "available" : "up to date"));
            if (profile != null && profile.repository() != null && !profile.repository().isBlank()) {
                profileElements.add(detailField("Repository URI", profile.repository()));
            }
            if (profile != null && profile.version() != null && !profile.version().isBlank()) {
                profileElements.add(detailField("Version", profile.version()));
            }
            if (profile != null && profile.lastUpdated() != null && !profile.lastUpdated().isBlank()) {
                profileElements.add(detailField("Last updated", profile.lastUpdated()));
            }
            if (profile != null && profile.message() != null && !profile.message().isBlank()) {
                profileElements.add(detailField("Message", profile.message()));
            }
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
                detailField("Indicators", statusDescription(selectedNode, selectedRepositoryHasLocalChanges, selectedRepositoryInspectionFailed))
            )
                .id(detailId)
                .focusable()
                .onKeyEvent(handleKeyEvent);
        }

        if (selectedNode.kind() == NodeKind.FILE && editMode) {
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

    static String statusDescription(NodeRef selectedNode, boolean selectedRepositoryHasLocalChanges, boolean selectedRepositoryInspectionFailed) {
        if (selectedNode == null) {
            return "none";
        }
        List<String> statuses = new ArrayList<>();
        if (selectedNode.inherited()) {
            statuses.add("inherited from parent profile (read-only)");
        }
        if (selectedNode.deepMerged()) {
            statuses.add("preview shows deep-merged content");
        }
        if (selectedNode.kind() != NodeKind.FILE && selectedRepositoryHasLocalChanges) {
            statuses.add("repository has local uncommitted changes (✏)");
        }
        if (selectedNode.kind() != NodeKind.FILE && selectedRepositoryInspectionFailed) {
            statuses.add("repository status inspection failed (!)");
        }
        return statuses.isEmpty() ? "none" : String.join("; ", statuses);
    }

    static String detailHint(NodeRef selectedNode, boolean editMode) {
        if (selectedNode == null) {
            return "Detail pane";
        }
        if (selectedNode.kind() == NodeKind.FILE) {
            if (editMode) {
                return "Editing mode: Ctrl+S save, Esc exit";
            }
            if (selectedNode.inherited()) {
                return "Inherited file (read-only). Press p to open the parent file | f creates a new file in this profile | y copies the absolute path | Up/Down/PgUp/PgDn/Home/End scroll preview";
            }
            if (selectedNode.deepMerged()) {
                return "Preview shows resolved deep-merged contents. Press e to edit the profile file | f creates a new file | d deletes this file | y copies the absolute path | Up/Down/PgUp/PgDn/Home/End scroll preview";
            }
            return "Press e to edit selected file | f creates a new file | d deletes this file | y copies the absolute path | Up/Down/PgUp/PgDn/Home/End scroll preview";
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

    static Text previewText(Text selectedFilePreview, int previewScrollOffset) {
        return scrolledPreviewText(selectedFilePreview, previewScrollOffset);
    }

    static String previewTitleFor(NodeRef selectedNode) {
        return previewTitle(selectedNode);
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
