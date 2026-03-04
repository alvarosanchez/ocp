package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.service.RepositoryService.ConfiguredRepository;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

final class RepositoryBoxRenderer {

    private static final int DEFAULT_MAX_BOX_WIDTH = 120;
    private static final int MIN_BOX_WIDTH = 28;
    private static final int BOX_HORIZONTAL_OVERHEAD = 4;
    private static final String VALUE_PREFIX = "-> ";

    private RepositoryBoxRenderer() {
    }

    static void print(List<ConfiguredRepository> repositories) {
        print(repositories, System.getenv());
    }

    static void print(List<ConfiguredRepository> repositories, Map<String, String> environment) {
        int maxBoxWidth = Math.max(MIN_BOX_WIDTH, resolvedMaxBoxWidth(environment));
        int maxContentWidth = Math.max(1, maxBoxWidth - BOX_HORIZONTAL_OVERHEAD);

        for (int index = 0; index < repositories.size(); index++) {
            ConfiguredRepository repository = repositories.get(index);
            List<String> contentLines = contentLines(repository, maxContentWidth);
            int contentWidth = Math.min(maxContentWidth, Math.max(1, maxDisplayWidth(contentLines)));
            int boxWidth = contentWidth + BOX_HORIZONTAL_OVERHEAD;
            int boxHeight = contentLines.size() + 2;

            Buffer buffer = Buffer.empty(Rect.of(boxWidth, boxHeight));
            Paragraph paragraph = Paragraph.builder()
                .text(styledContent(contentLines))
                .block(
                    Block.builder()
                        .borders(Borders.ALL)
                        .borderType(BorderType.ROUNDED)
                        .borderColor(Color.CYAN)
                        .build()
                )
                .build();
            paragraph.render(buffer.area(), buffer);
            Cli.print(buffer.toAnsiStringTrimmed());

            if (index < repositories.size() - 1) {
                Cli.print("");
            }
        }
    }

    private static List<String> contentLines(ConfiguredRepository repository, int maxContentWidth) {
        List<List<String>> fieldBlocks = List.of(
            wrapField("Name", repository.name(), maxContentWidth),
            wrapField("URI", repository.uri(), maxContentWidth),
            wrapField("Local path", repository.localPath(), maxContentWidth),
            wrapField("Resolved profiles", renderedResolvedProfiles(repository.resolvedProfiles()), maxContentWidth)
        );

        List<String> lines = new ArrayList<>();
        for (int index = 0; index < fieldBlocks.size(); index++) {
            if (index > 0) {
                lines.add("");
            }
            lines.addAll(fieldBlocks.get(index));
        }
        return lines;
    }

    private static List<String> wrapField(String label, String value, int width) {
        String normalizedValue = value == null || value.isBlank() ? "-" : value;
        int valueWidth = Math.max(1, width - VALUE_PREFIX.length());
        List<String> wrappedValueLines = wrapLines(normalizedValue, valueWidth);

        List<String> lines = new ArrayList<>(wrappedValueLines.size() + 1);
        lines.add(label + ":");
        for (String wrappedValueLine : wrappedValueLines) {
            lines.add(VALUE_PREFIX + wrappedValueLine);
        }
        return lines;
    }

    private static String renderedResolvedProfiles(List<String> profiles) {
        if (profiles.isEmpty()) {
            return "-";
        }
        return String.join(", ", profiles);
    }

    static int resolvedMaxBoxWidth(Map<String, String> environment, OptionalInt terminalColumns) {
        OptionalInt fromEnvironment = parseColumns(environment.get("COLUMNS"));
        if (fromEnvironment.isPresent()) {
            return fromEnvironment.getAsInt();
        }
        if (terminalColumns.isPresent()) {
            return terminalColumns.getAsInt();
        }
        return DEFAULT_MAX_BOX_WIDTH;
    }

    private static int resolvedMaxBoxWidth(Map<String, String> environment) {
        return resolvedMaxBoxWidth(environment, terminalColumns());
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

    private static int maxDisplayWidth(List<String> values) {
        int width = 0;
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

    private static Text styledContent(List<String> contentLines) {
        List<Line> lines = new ArrayList<>(contentLines.size());
        for (String line : contentLines) {
            if (line.endsWith(":") && !line.startsWith(VALUE_PREFIX)) {
                lines.add(Line.from(Span.styled(line, Style.EMPTY.bold().fg(Color.CYAN))));
            } else {
                lines.add(Line.from(Span.raw(line)));
            }
        }
        return Text.from(lines);
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
}
