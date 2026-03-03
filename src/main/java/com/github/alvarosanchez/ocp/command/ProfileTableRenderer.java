package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.model.Profile;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

final class ProfileTableRenderer {

    static final String ACTIVE_MARKER = "✓";
    private static final String UPDATE_MARKER = "❄";
    private static final int DEFAULT_MAX_TABLE_WIDTH = 120;
    private static final int FULL_TABLE_COLUMN_COUNT = 7;
    private static final int COMPACT_TABLE_COLUMN_COUNT = 6;
    private static final int TABLE_COLUMN_SPACING = 2;
    private static final int BLOCK_HORIZONTAL_BORDERS = 2;
    private static final int TABLE_CELL_HORIZONTAL_PADDING = 2;
    private static final int TABLE_VERTICAL_SEPARATOR_WIDTH = 1;
    private static final String NAME_HEADER = "NAME";
    private static final String DESCRIPTION_HEADER = "DESCRIPTION";
    private static final String ACTIVE_HEADER = "ACTIVE";
    private static final String REPOSITORY_HEADER = "REPOSITORY";
    private static final String VERSION_HEADER = "VERSION";
    private static final String LAST_UPDATED_HEADER = "LAST UPDATED";
    private static final String MESSAGE_HEADER = "MESSAGE";
    private static final int DESCRIPTION_HEADER_WIDTH = DESCRIPTION_HEADER.length();
    private static final int MESSAGE_HEADER_WIDTH = MESSAGE_HEADER.length();

    private ProfileTableRenderer() {
    }

    static void print(List<Profile> profiles) {
        print(profiles, System.getenv());
    }

    static void print(List<Profile> profiles, Map<String, String> environment) {
        TableLayout layout = tableLayout(profiles, resolvedMaxTableWidth(environment));
        List<Constraint> widths = new ArrayList<>();
        widths.add(Constraint.length(maxDisplayWidth(NAME_HEADER, profiles.stream().map(Profile::name).toList())));
        widths.add(Constraint.length(layout.descriptionWidth()));
        widths.add(Constraint.length(maxDisplayWidth(ACTIVE_HEADER, profiles.stream().map(ProfileTableRenderer::activeMarker).toList())));
        if (layout.includeRepository()) {
            widths.add(Constraint.length(maxDisplayWidth(REPOSITORY_HEADER, profiles.stream().map(Profile::repositoryName).toList())));
        }
        widths.add(Constraint.length(maxDisplayWidth(VERSION_HEADER, profiles.stream().map(ProfileTableRenderer::renderedVersion).toList())));
        widths.add(Constraint.length(maxDisplayWidth(LAST_UPDATED_HEADER, profiles.stream().map(Profile::lastUpdated).toList())));
        widths.add(Constraint.length(layout.messageWidth()));

        Row header = layout.includeRepository()
            ? Row.from(
                Cell.from(Span.styled(NAME_HEADER, Style.EMPTY.bold().fg(Color.CYAN))),
                Cell.from(Span.styled(DESCRIPTION_HEADER, Style.EMPTY.bold().fg(Color.CYAN))),
                Cell.from(Span.styled(ACTIVE_HEADER, Style.EMPTY.bold().fg(Color.CYAN))),
                Cell.from(Span.styled(REPOSITORY_HEADER, Style.EMPTY.bold().fg(Color.CYAN))),
                Cell.from(Span.styled(VERSION_HEADER, Style.EMPTY.bold().fg(Color.CYAN))),
                Cell.from(Span.styled(LAST_UPDATED_HEADER, Style.EMPTY.bold().fg(Color.CYAN))),
                Cell.from(Span.styled(MESSAGE_HEADER, Style.EMPTY.bold().fg(Color.CYAN)))
            )
            : Row.from(
                Cell.from(Span.styled(NAME_HEADER, Style.EMPTY.bold().fg(Color.CYAN))),
                Cell.from(Span.styled(DESCRIPTION_HEADER, Style.EMPTY.bold().fg(Color.CYAN))),
                Cell.from(Span.styled(ACTIVE_HEADER, Style.EMPTY.bold().fg(Color.CYAN))),
                Cell.from(Span.styled(VERSION_HEADER, Style.EMPTY.bold().fg(Color.CYAN))),
                Cell.from(Span.styled(LAST_UPDATED_HEADER, Style.EMPTY.bold().fg(Color.CYAN))),
                Cell.from(Span.styled(MESSAGE_HEADER, Style.EMPTY.bold().fg(Color.CYAN)))
            );

        List<Row> rows = new ArrayList<>();

        for (Profile profile : profiles) {
            List<String> descriptionLines = wrapLines(profile.description(), layout.descriptionWidth());
            List<String> messageLines = wrapLines(profile.message(), layout.messageWidth());
            int rowCount = Math.max(descriptionLines.size(), messageLines.size());
            for (int row = 0; row < rowCount; row++) {
                boolean firstRow = row == 0;
                if (layout.includeRepository()) {
                    rows.add(
                        Row.from(
                            Cell.from(firstRow ? profile.name() : ""),
                            Cell.from(lineAt(descriptionLines, row)),
                            activeCellValue(profile, firstRow),
                            Cell.from(firstRow ? profile.repositoryName() : ""),
                            Cell.from(firstRow ? renderedVersion(profile) : ""),
                            Cell.from(firstRow ? profile.lastUpdated() : ""),
                            Cell.from(lineAt(messageLines, row))
                        )
                    );
                } else {
                    rows.add(
                        Row.from(
                            Cell.from(firstRow ? profile.name() : ""),
                            Cell.from(lineAt(descriptionLines, row)),
                            activeCellValue(profile, firstRow),
                            Cell.from(firstRow ? renderedVersion(profile) : ""),
                            Cell.from(firstRow ? profile.lastUpdated() : ""),
                            Cell.from(lineAt(messageLines, row))
                        )
                    );
                }
            }
        }

        Table table = Table.builder()
            .header(header)
            .rows(rows)
            .widths(widths)
            .columnSpacing(TABLE_COLUMN_SPACING)
            .block(
                Block.builder()
                    .title("Profiles")
                    .borders(Borders.ALL)
                    .borderType(BorderType.ROUNDED)
                    .borderColor(Color.CYAN)
                    .build()
            )
            .build();

        int renderedHeight = rows.stream().mapToInt(Row::totalHeight).sum() + header.totalHeight() + 2;
        Buffer buffer = Buffer.empty(Rect.of(resolvedMaxTableWidth(environment), Math.max(3, renderedHeight)));
        table.render(buffer.area(), buffer, new TableState());
        Cli.print(buffer.toAnsiStringTrimmed());
    }

    static void printWithWarnings(List<Profile> profiles) {
        print(profiles);

        if (hasUpdates(profiles)) {
            Cli.warning(UPDATE_MARKER + " Newer commits are available in remote repositories. Run `ocp repository refresh`.");
        }
        List<String> failedVersionChecks = failedVersionChecks(profiles);
        if (!failedVersionChecks.isEmpty()) {
            Cli.warning(
                "! Skipped remote update checks for repositories: "
                    + String.join(", ", failedVersionChecks)
                    + "."
            );
        }
    }

    static boolean hasUpdates(List<Profile> profiles) {
        return profiles.stream().anyMatch(Profile::updateAvailable);
    }

    static List<String> failedVersionChecks(List<Profile> profiles) {
        return profiles
            .stream()
            .filter(Profile::versionCheckFailed)
            .map(Profile::repositoryName)
            .distinct()
            .sorted()
            .toList();
    }

    private static String activeMarker(Profile profile) {
        if (!profile.active()) {
            return "";
        }
        return ACTIVE_MARKER;
    }

    private static String renderedVersion(Profile profile) {
        if (!profile.updateAvailable()) {
            return profile.version();
        }
        return profile.version() + " " + UPDATE_MARKER;
    }

    static int maxTableWidth(Map<String, String> environment) {
        OptionalInt columns = parseColumns(environment.get("COLUMNS"));
        if (columns.isPresent()) {
            return columns.getAsInt();
        }
        return DEFAULT_MAX_TABLE_WIDTH;
    }

    static int resolvedMaxTableWidth(Map<String, String> environment, OptionalInt terminalColumns) {
        OptionalInt fromEnvironment = parseColumns(environment.get("COLUMNS"));
        if (fromEnvironment.isPresent()) {
            return fromEnvironment.getAsInt();
        }
        if (terminalColumns.isPresent()) {
            return terminalColumns.getAsInt();
        }
        return DEFAULT_MAX_TABLE_WIDTH;
    }

    private static int resolvedMaxTableWidth(Map<String, String> environment) {
        return resolvedMaxTableWidth(environment, terminalColumns());
    }

    private static OptionalInt parseColumns(String value) {
        if (value == null || value.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            int parsedColumns = Integer.parseInt(value.trim());
            if (parsedColumns <= 0) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(parsedColumns);
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private static OptionalInt terminalColumns() {
        if (System.console() == null) {
            return OptionalInt.empty();
        }
        try {
            Process process = new ProcessBuilder("sh", "-c", "stty size < /dev/tty")
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return OptionalInt.empty();
            }
            String[] parts = output.trim().split("\\s+");
            if (parts.length != 2) {
                return OptionalInt.empty();
            }
            return parseColumns(parts[1]);
        } catch (IOException e) {
            return OptionalInt.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalInt.empty();
        }
    }

    private static TableLayout tableLayout(List<Profile> profiles, int maxTableWidth) {
        int nameWidth = maxDisplayWidth(NAME_HEADER, profiles.stream().map(Profile::name).toList());
        int activeWidth = maxDisplayWidth(ACTIVE_HEADER, profiles.stream().map(ProfileTableRenderer::activeMarker).toList());
        int repositoryWidth = maxDisplayWidth(REPOSITORY_HEADER, profiles.stream().map(Profile::repositoryName).toList());
        int versionWidth = maxDisplayWidth(VERSION_HEADER, profiles.stream().map(ProfileTableRenderer::renderedVersion).toList());
        int lastUpdatedWidth = maxDisplayWidth(LAST_UPDATED_HEADER, profiles.stream().map(Profile::lastUpdated).toList());
        int descriptionWidth = maxDisplayWidth(DESCRIPTION_HEADER, profiles.stream().map(Profile::description).toList());
        int messageWidth = maxDisplayWidth(MESSAGE_HEADER, profiles.stream().map(Profile::message).toList());

        int minimumWrappableWidth = DESCRIPTION_HEADER_WIDTH + MESSAGE_HEADER_WIDTH;
        int fixedWithoutRepositoryWidth = nameWidth + activeWidth + versionWidth + lastUpdatedWidth;
        int fullTableMinimumWidth =
            columnOverhead(FULL_TABLE_COLUMN_COUNT)
                + fixedWithoutRepositoryWidth
                + repositoryWidth
                + minimumWrappableWidth;

        boolean includeRepository = fullTableMinimumWidth <= maxTableWidth;
        int fixedWidth = fixedWithoutRepositoryWidth + (includeRepository ? repositoryWidth : 0);
        int columnCount = includeRepository ? FULL_TABLE_COLUMN_COUNT : COMPACT_TABLE_COLUMN_COUNT;
        int[] wrapWidths = computeWrapWidths(
            descriptionWidth,
            messageWidth,
            fixedWidth,
            columnCount,
            maxTableWidth
        );

        return new TableLayout(includeRepository, wrapWidths[0], wrapWidths[1]);
    }

    private static int[] computeWrapWidths(
        int descriptionWidth,
        int messageWidth,
        int fixedWidth,
        int columnCount,
        int maxTableWidth
    ) {
        int availableWrappableWidth = maxTableWidth - columnOverhead(columnCount) - fixedWidth;
        if (availableWrappableWidth <= DESCRIPTION_HEADER_WIDTH + MESSAGE_HEADER_WIDTH) {
            return new int[] {DESCRIPTION_HEADER_WIDTH, MESSAGE_HEADER_WIDTH};
        }

        int desiredWrappableWidth = descriptionWidth + messageWidth;
        if (desiredWrappableWidth <= availableWrappableWidth) {
            return new int[] {descriptionWidth, messageWidth};
        }

        int minimumWrappableWidth = DESCRIPTION_HEADER_WIDTH + MESSAGE_HEADER_WIDTH;
        int distributableWidth = availableWrappableWidth - minimumWrappableWidth;
        int descriptionFlex = descriptionWidth - DESCRIPTION_HEADER_WIDTH;
        int messageFlex = messageWidth - MESSAGE_HEADER_WIDTH;
        int totalFlex = descriptionFlex + messageFlex;
        if (totalFlex <= 0) {
            return new int[] {DESCRIPTION_HEADER_WIDTH, MESSAGE_HEADER_WIDTH};
        }

        int descriptionShare = (distributableWidth * descriptionFlex) / totalFlex;
        int messageShare = distributableWidth - descriptionShare;
        return new int[] {
            DESCRIPTION_HEADER_WIDTH + descriptionShare,
            MESSAGE_HEADER_WIDTH + messageShare
        };
    }

    private static int columnOverhead(int columnCount) {
        int spacingOverhead = Math.max(0, columnCount - 1) * TABLE_COLUMN_SPACING;
        int cellPaddingOverhead = columnCount * TABLE_CELL_HORIZONTAL_PADDING;
        int separatorOverhead = (columnCount + 1) * TABLE_VERTICAL_SEPARATOR_WIDTH;
        return BLOCK_HORIZONTAL_BORDERS + spacingOverhead + cellPaddingOverhead + separatorOverhead;
    }

    private static int maxDisplayWidth(String header, List<String> values) {
        int width = displayWidth(header);
        for (String value : values) {
            width = Math.max(width, displayWidth(value));
        }
        return width;
    }

    private static int displayWidth(String value) {
        String normalized = value == null ? "" : value;
        int width = 0;
        String[] lines = normalized.split("\\R", -1);
        for (String line : lines) {
            width = Math.max(width, line.length());
        }
        return width;
    }

    private static Cell activeCellValue(Profile profile, boolean firstRow) {
        if (!firstRow || !profile.active()) {
            return Cell.from("");
        }
        return Cell.from(Text.from(Line.from(Span.styled(ACTIVE_MARKER, Style.EMPTY.bold().fg(Color.GREEN)))));
    }

    private static List<String> wrapLines(String value, int width) {
        String normalized = value == null ? "" : value;
        if (normalized.isBlank() || displayWidth(normalized) <= width) {
            return List.of(normalized);
        }

        String[] paragraphs = normalized.split("\\R", -1);
        List<String> wrapped = new ArrayList<>();
        for (String paragraph : paragraphs) {
            wrapped.addAll(wrapParagraph(paragraph, width));
        }
        return wrapped;
    }

    private static List<String> wrapParagraph(String paragraph, int width) {
        if (paragraph.isBlank()) {
            return List.of("");
        }

        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        for (String word : paragraph.trim().split("\\s+")) {
            appendWord(lines, currentLine, word, width);
        }
        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }
        return lines;
    }

    private static void appendWord(List<String> lines, StringBuilder currentLine, String word, int width) {
        if (word.length() > width) {
            if (!currentLine.isEmpty()) {
                lines.add(currentLine.toString());
                currentLine.setLength(0);
            }
            int index = 0;
            while (word.length() - index > width) {
                lines.add(word.substring(index, index + width));
                index += width;
            }
            if (index < word.length()) {
                currentLine.append(word.substring(index));
            }
            return;
        }

        if (currentLine.isEmpty()) {
            currentLine.append(word);
            return;
        }

        if (currentLine.length() + 1 + word.length() <= width) {
            currentLine.append(' ').append(word);
            return;
        }

        lines.add(currentLine.toString());
        currentLine.setLength(0);
        currentLine.append(word);
    }

    private static String lineAt(List<String> lines, int index) {
        if (index >= lines.size()) {
            return "";
        }
        return lines.get(index);
    }

    private record TableLayout(boolean includeRepository, int descriptionWidth, int messageWidth) {
    }
}
