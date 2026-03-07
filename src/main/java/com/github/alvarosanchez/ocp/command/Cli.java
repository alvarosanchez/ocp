package com.github.alvarosanchez.ocp.command;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine;

/**
 * Shared CLI output helpers and table styling utilities.
 */
public final class Cli {

    private static final String CLEAR_LINE = "\r\u001B[2K";
    private static final int MIN_LINE_WIDTH = 80;
    private static final int MAX_RENDER_LINE_WIDTH = 240;
    private static final Pattern NOTICE_HIGHLIGHT_PATTERN = Pattern.compile("\\b\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.]+)?\\b|brew upgrade ocp");
    private static volatile boolean ansiEnabled = true;
    private static volatile String startupNotice;

    private Cli() {
    }

    /**
     * Prints raw text to standard output.
     *
     * @param message message to print
     */
    public static void print(String message) {
        System.out.println(message);
    }

    /**
     * Prints an informational message.
     *
     * @param message message to print
     */
    public static void info(String message) {
        printStyled("", Color.CYAN, message, false);
    }

    public static void infoWithCodeHighlights(String message) {
        printWithCodeHighlights(message, false);
    }

    public static Line highlightedNoticeLine(String message) {
        return Line.from(codeHighlightSpans(message).toArray(Span[]::new));
    }

    public static Text highlightedNoticeText(String message) {
        String normalizedMessage = message == null ? "" : message;
        String[] lines = normalizedMessage.split("\\R", -1);
        ArrayList<Line> styledLines = new ArrayList<>();
        for (String line : lines) {
            styledLines.add(highlightedNoticeLine(line));
        }
        return Text.from(styledLines.toArray(Line[]::new));
    }

    /**
     * Prints a success message.
     *
     * @param message message to print
     */
    public static void success(String message) {
        printStyled("", Color.GREEN, message, false);
    }

    /**
     * Prints a warning message.
     *
     * @param message message to print
     */
    public static void warning(String message) {
        printStyled("", Color.YELLOW, message, false);
    }

    /**
     * Prints an error message to standard error.
     *
     * @param message message to print
     */
    public static void error(String message) {
        printStyled("Error:", Color.RED, message, true);
    }

    /**
     * Initializes CLI output theme and ANSI behavior.
     */
    public static void init() {
        ansiEnabled = CommandLine.Help.Ansi.AUTO.enabled();
        startupNotice = null;
    }

    public static void setStartupNotice(String message) {
        startupNotice = message == null || message.isBlank() ? null : message;
    }

    public static String consumeStartupNotice() {
        String message = startupNotice;
        startupNotice = null;
        return message;
    }

    /**
     * Shows a transient informational line when running interactively.
     *
     * @param message message to display
     * @return {@code true} when transient output was shown
     */
    public static boolean beginTransientInfo(String message) {
        if (System.console() == null) {
            return false;
        }
        System.out.print("\r" + styledLine("", Color.CYAN, message));
        System.out.flush();
        return true;
    }

    /**
     * Clears a previously shown transient informational line.
     *
     * @param shown whether transient output was previously shown
     */
    public static void endTransientInfo(boolean shown) {
        if (!shown) {
            return;
        }
        System.out.print(CLEAR_LINE);
        System.out.flush();
    }

    private static void printStyled(String label, Color color, String message, boolean stderr) {
        String output = styledLine(label, color, message);
        if (stderr) {
            System.err.println(output);
            return;
        }
        System.out.println(output);
    }

    private static void printWithCodeHighlights(String message, boolean stderr) {
        String normalizedMessage = message == null ? "" : message;
        if (!ansiEnabled) {
            if (stderr) {
                System.err.println(normalizedMessage);
            } else {
                System.out.println(normalizedMessage);
            }
            return;
        }

        String[] lines = normalizedMessage.split("\\R", -1);
        for (String line : lines) {
            String output = styledLineWithCodeHighlights(line);
            if (stderr) {
                System.err.println(output);
            } else {
                System.out.println(output);
            }
        }
    }

    private static String styledLine(String label, Color color, String message) {
        String normalizedLabel = label == null || label.isBlank() ? "" : label + " ";
        String normalizedMessage = message == null ? "" : message;
        if (!ansiEnabled) {
            return normalizedLabel + normalizedMessage;
        }

        String renderedMessage = truncateToRenderWidth(normalizedMessage, MAX_RENDER_LINE_WIDTH - normalizedLabel.length());

        int width = Math.min(
            MAX_RENDER_LINE_WIDTH,
            Math.max(MIN_LINE_WIDTH, normalizedLabel.length() + renderedMessage.length() + 2)
        );
        Buffer buffer = Buffer.empty(Rect.of(width, 1));
        buffer.setLine(
            0,
            0,
            Line.from(
                Span.styled(normalizedLabel, Style.EMPTY.bold().fg(color)),
                Span.raw(renderedMessage)
            )
        );
        return buffer.toAnsiStringTrimmed();
    }

    private static String styledLineWithCodeHighlights(String message) {
        String normalizedMessage = message == null ? "" : message;
        int width = Math.min(MAX_RENDER_LINE_WIDTH, Math.max(MIN_LINE_WIDTH, normalizedMessage.length() + 2));
        Buffer buffer = Buffer.empty(Rect.of(width, 1));
        buffer.setLine(0, 0, highlightedNoticeLine(normalizedMessage));
        return buffer.toAnsiStringTrimmed();
    }

    private static List<Span> codeHighlightSpans(String message) {
        List<Span> spans = new ArrayList<>();
        if (message == null || message.isEmpty()) {
            spans.add(Span.raw(""));
            return spans;
        }

        Matcher matcher = NOTICE_HIGHLIGHT_PATTERN.matcher(message);
        int index = 0;
        while (matcher.find()) {
            if (matcher.start() > index) {
                spans.add(Span.raw(message.substring(index, matcher.start())));
            }
            spans.add(Span.styled(matcher.group(), Style.EMPTY.bold().fg(Color.CYAN)));
            index = matcher.end();
        }
        if (index < message.length()) {
            spans.add(Span.raw(message.substring(index)));
        }

        if (spans.isEmpty()) {
            spans.add(Span.raw(""));
        }
        return spans;
    }

    private static String truncateToRenderWidth(String value, int maxWidth) {
        if (maxWidth <= 1) {
            return "";
        }
        if (value.length() <= maxWidth) {
            return value;
        }
        if (maxWidth <= 3) {
            return value.substring(0, maxWidth);
        }
        return value.substring(0, maxWidth - 3) + "...";
    }
}
