package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.model.Profile;
import com.github.kusoroadeolu.clique.Clique;
import com.github.kusoroadeolu.clique.tables.Table;
import com.github.kusoroadeolu.clique.tables.TableType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Pattern;

import static com.github.alvarosanchez.ocp.command.Cli.TABLE_CFG;

final class ProfileTableRenderer {

    static final String ACTIVE_MARKER = "✓";
    private static final String UPDATE_MARKER = "❄";
    private static final int DEFAULT_MAX_TABLE_WIDTH = 120;
    private static final int FULL_TABLE_COLUMN_COUNT = 7;
    private static final int COMPACT_TABLE_COLUMN_COUNT = 6;
    private static final String NAME_HEADER = "NAME";
    private static final String DESCRIPTION_HEADER = "DESCRIPTION";
    private static final String ACTIVE_HEADER = "ACTIVE";
    private static final String REPOSITORY_HEADER = "REPOSITORY";
    private static final String VERSION_HEADER = "VERSION";
    private static final String LAST_UPDATED_HEADER = "LAST UPDATED";
    private static final String MESSAGE_HEADER = "MESSAGE";
    private static final int DESCRIPTION_HEADER_WIDTH = DESCRIPTION_HEADER.length();
    private static final int MESSAGE_HEADER_WIDTH = MESSAGE_HEADER.length();
    private static final Pattern STYLE_TAG_PATTERN = Pattern.compile("\\[(?:/|\\*?[a-zA-Z][a-zA-Z0-9_]*)]");

    private ProfileTableRenderer() {
    }

    static void print(List<Profile> profiles) {
        print(profiles, System.getenv());
    }

    static void print(List<Profile> profiles, Map<String, String> environment) {
        TableLayout layout = tableLayout(profiles, resolvedMaxTableWidth(environment));

        Table table = Clique.table(TableType.ROUNDED_BOX_DRAW, TABLE_CFG);
        if (layout.includeRepository()) {
            table.addHeaders(
                NAME_HEADER,
                DESCRIPTION_HEADER,
                ACTIVE_HEADER,
                REPOSITORY_HEADER,
                VERSION_HEADER,
                LAST_UPDATED_HEADER,
                MESSAGE_HEADER
            );
        } else {
            table.addHeaders(
                NAME_HEADER,
                DESCRIPTION_HEADER,
                ACTIVE_HEADER,
                VERSION_HEADER,
                LAST_UPDATED_HEADER,
                MESSAGE_HEADER
            );
        }

        for (Profile profile : profiles) {
            List<String> descriptionLines = wrapLines(profile.description(), layout.descriptionWidth());
            List<String> messageLines = wrapLines(profile.message(), layout.messageWidth());
            int rowCount = Math.max(descriptionLines.size(), messageLines.size());
            for (int row = 0; row < rowCount; row++) {
                boolean firstRow = row == 0;
                if (layout.includeRepository()) {
                    table.addRows(
                        firstRow ? profile.name() : "",
                        lineAt(descriptionLines, row),
                        firstRow ? activeMarker(profile) : "",
                        firstRow ? profile.repository() : "",
                        firstRow ? renderedVersion(profile) : "",
                        firstRow ? profile.lastUpdated() : "",
                        lineAt(messageLines, row)
                    );
                } else {
                    table.addRows(
                        firstRow ? profile.name() : "",
                        lineAt(descriptionLines, row),
                        firstRow ? activeMarker(profile) : "",
                        firstRow ? renderedVersion(profile) : "",
                        firstRow ? profile.lastUpdated() : "",
                        lineAt(messageLines, row)
                    );
                }
            }
        }
        table.render();
    }

    static void printWithWarnings(List<Profile> profiles) {
        print(profiles);

        if (hasUpdates(profiles)) {
            Cli.warning(UPDATE_MARKER + " Newer commits are available in remote repositories. Run `ocp profile refresh`.");
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
        int repositoryWidth = maxDisplayWidth(REPOSITORY_HEADER, profiles.stream().map(Profile::repository).toList());
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
        return (5 * columnCount) + 1;
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
        String noStyle = STYLE_TAG_PATTERN.matcher(normalized).replaceAll("");
        int width = 0;
        String[] lines = noStyle.split("\\R", -1);
        for (String line : lines) {
            width = Math.max(width, line.length());
        }
        return width;
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
