package com.github.alvarosanchez.ocp.command.interactive;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.KeyEventHandler;

import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.text;

final class CommitConfirmDialogRenderer {

    private static final int COMMIT_DIFF_PREVIEW_LINES = 20;
    static final String DIALOG_ID = "commit-confirm-dialog";

    private CommitConfirmDialogRenderer() {
    }

    static Element render(CommitConfirmState state, KeyEventHandler keyEventHandler) {
        List<Element> content = dialogContent(state);

        return dialog("Review local changes", content.toArray(Element[]::new))
            .rounded()
            .borderColor(Color.YELLOW)
            .width(132)
            .padding(1)
            .id(DIALOG_ID)
            .focusable()
            .onKeyEvent(keyEventHandler);
    }

    static List<Element> dialogContent(CommitConfirmState state) {
        List<Element> content = new ArrayList<>();
        content.add(text("Review changes for repository " + state.repositoryName() + " before commit and push.").fg(Color.YELLOW));
        content.add(text("Diff:").bold().fg(Color.CYAN));
        content.addAll(buildColoredDiffPreview(state.diff()));
        content.add(text("Press [Y] to continue to the commit message, [Esc] to cancel.").bold().fg(Color.YELLOW));
        return content;
    }

    private static List<Element> buildColoredDiffPreview(String diff) {
        String normalized = AnsiTextParser.stripAnsi(diff);
        if (normalized.isBlank()) {
            return List.of(text("No diff output available.").dim());
        }

        List<Element> lines = new ArrayList<>();
        String[] splitLines = normalized.split("\\R", -1);
        int maxLines = Math.min(COMMIT_DIFF_PREVIEW_LINES, splitLines.length);
        for (int index = 0; index < maxLines; index++) {
            lines.add(colorizeDiffLine(splitLines[index]));
        }
        if (splitLines.length > maxLines) {
            lines.add(text("... diff truncated (" + (splitLines.length - maxLines) + " more lines)").dim());
        }
        return lines;
    }

    private static Element colorizeDiffLine(String line) {
        String safeLine = line.length() > 124 ? line.substring(0, 124) + "..." : line;
        if (safeLine.startsWith("+++ ") || safeLine.startsWith("--- ") || safeLine.startsWith("@@")) {
            return text(safeLine).fg(Color.CYAN);
        }
        if (safeLine.startsWith("+")) {
            return text(safeLine).fg(Color.GREEN);
        }
        if (safeLine.startsWith("-")) {
            return text(safeLine).fg(Color.RED);
        }
        return text(safeLine);
    }
}
