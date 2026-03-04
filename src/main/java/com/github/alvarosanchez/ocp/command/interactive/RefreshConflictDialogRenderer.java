package com.github.alvarosanchez.ocp.command.interactive;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.KeyEventHandler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.text;

final class RefreshConflictDialogRenderer {

    private static final int CONFLICT_DIFF_PREVIEW_LINES = 20;
    private static final int CONFLICT_FILES_PREVIEW_LINES = 12;

    private RefreshConflictDialogRenderer() {
    }

    static Element render(RefreshConflictState refreshConflict, KeyEventHandler keyEventHandler) {
        if (refreshConflict.kind() == RefreshConflictKind.REPOSITORY) {
            var conflict = refreshConflict.repositoryConflict();
            List<Element> content = new ArrayList<>();
            content.add(text("Local uncommitted changes detected in repository `" + conflict.repositoryName() + "`.").fg(Color.YELLOW));
            content.add(text("Diff:").bold().fg(Color.CYAN));
            content.addAll(buildColoredDiffPreview(conflict.diff()));
            content.add(text("Choose how to proceed:").bold());
            content.add(text("1) Discard local changes and refresh from repository.").fg(Color.YELLOW));
            content.add(text("2) Commit local changes and force push to remote.").fg(Color.YELLOW));
            content.add(text("3) Do nothing and fix manually.").fg(Color.YELLOW));
            content.add(text("Press 1/2/3 to choose").dim());

            return dialog("Refresh conflict", content.toArray(Element[]::new))
                .rounded()
                .borderColor(Color.YELLOW)
                .width(132)
                .padding(1)
                .focusable()
                .onKeyEvent(keyEventHandler);
        }

        var conflict = refreshConflict.mergedFilesConflict();
        List<Element> content = new ArrayList<>();
        content.add(
            text("Local changes detected in merged active profile files for profile `" + conflict.profileName() + "`.")
                .fg(Color.YELLOW)
        );
        content.add(text("Modified files in `" + conflict.targetDirectory() + "`: ").fg(Color.CYAN));
        int displayed = 0;
        for (Path driftedFile : conflict.driftedFiles()) {
            if (displayed >= CONFLICT_FILES_PREVIEW_LINES) {
                break;
            }
            content.add(text("- " + driftedFile));
            displayed++;
        }
        if (conflict.driftedFiles().size() > displayed) {
            content.add(text("... and " + (conflict.driftedFiles().size() - displayed) + " more files").dim());
        }
        content.add(text("Choose how to proceed:").bold());
        content.add(text("1) Discard local merged-file changes and refresh.").fg(Color.YELLOW));
        content.add(text("2) Do nothing and fix manually.").fg(Color.YELLOW));
        content.add(text("Press 1/2 to choose").dim());

        return dialog("Refresh conflict", content.toArray(Element[]::new))
            .rounded()
            .borderColor(Color.YELLOW)
            .width(132)
            .padding(1)
            .focusable()
            .onKeyEvent(keyEventHandler);
    }

    private static List<Element> buildColoredDiffPreview(String diff) {
        String normalized = AnsiTextParser.stripAnsi(diff);
        if (normalized.isBlank()) {
            return List.of(text("No diff output available.").dim());
        }

        List<Element> lines = new ArrayList<>();
        String[] splitLines = normalized.split("\\R", -1);
        int maxLines = Math.min(CONFLICT_DIFF_PREVIEW_LINES, splitLines.length);
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
