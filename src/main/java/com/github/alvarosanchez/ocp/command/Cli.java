package com.github.alvarosanchez.ocp.command;

import com.github.kusoroadeolu.clique.Clique;
import com.github.kusoroadeolu.clique.ansi.ColorCode;
import com.github.kusoroadeolu.clique.config.BorderStyle;
import com.github.kusoroadeolu.clique.config.CellAlign;
import com.github.kusoroadeolu.clique.config.TableConfiguration;
import picocli.CommandLine;

/**
 * Shared CLI output helpers and table styling utilities.
 */
public final class Cli {

    private static final String CLEAR_LINE = "\r\u001B[2K";

    /**
     * Default table configuration used for profile output rendering.
     */
    public static final TableConfiguration TABLE_CFG = TableConfiguration
            .immutableBuilder()
            .borderStyle(borderStyle())
            .padding(2)
            .alignment(CellAlign.CENTER)
            .build();

    private Cli() {
    }

    /**
     * Prints raw text to standard output.
     *
     * @param message message to print
     */
    public static void print(String message) {
        Clique.parser().print(message);
    }

    /**
     * Prints an informational message.
     *
     * @param message message to print
     */
    public static void info(String message) {
        Clique.parser().print("[tokyo_blue]" + message + "[/]");
    }

    /**
     * Prints a success message.
     *
     * @param message message to print
     */
    public static void success(String message) {
        Clique.parser().print("[green]" + message + "[/]");
    }

    /**
     * Prints a warning message.
     *
     * @param message message to print
     */
    public static void warning(String message) {
        Clique.parser().print("[tokyo_yellow]" + message + "[/]");
    }

    /**
     * Prints an error message to standard error.
     *
     * @param message message to print
     */
    public static void error(String message) {
        System.err.println(Clique.parser().parse("[tokyo_red]Error:[/] ") + message);
    }

    /**
     * Styles text as a table header.
     *
     * @param content header text
     * @return styled header token
     */
    public static String th(String content) {
        return "[*tokyo_blue]" + content + "[/]";
    }

    /**
     * Initializes CLI output theme and ANSI behavior.
     */
    public static void init() {
        Clique.enableCliqueColors(CommandLine.Help.Ansi.AUTO.enabled());
        Clique.registerTheme("tokyo-night");
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
        System.out.print("\r" + Clique.parser().parse("[tokyo_blue]" + message + "[/]"));
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

    private static BorderStyle borderStyle() {
        return BorderStyle.immutableBuilder()
                .horizontalBorderStyles(ColorCode.CYAN)
                .verticalBorderStyles(ColorCode.CYAN)
                .edgeBorderStyles(ColorCode.CYAN)
                .build();
    }
}
