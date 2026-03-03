package com.github.alvarosanchez.ocp.command;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import picocli.CommandLine;

/**
 * Shared CLI output helpers and table styling utilities.
 */
public final class Cli {

    private static final String CLEAR_LINE = "\r\u001B[2K";
    private static final int MIN_LINE_WIDTH = 80;
    private static volatile boolean ansiEnabled = true;

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

    private static String styledLine(String label, Color color, String message) {
        String normalizedLabel = label == null || label.isBlank() ? "" : label + " ";
        String normalizedMessage = message == null ? "" : message;
        if (!ansiEnabled) {
            return normalizedLabel + normalizedMessage;
        }

        int width = Math.max(MIN_LINE_WIDTH, normalizedLabel.length() + normalizedMessage.length() + 2);
        Buffer buffer = Buffer.empty(Rect.of(width, 1));
        buffer.setLine(
            0,
            0,
            Line.from(
                Span.styled(normalizedLabel, Style.EMPTY.bold().fg(color)),
                Span.raw(normalizedMessage)
            )
        );
        return buffer.toAnsiStringTrimmed();
    }
}
