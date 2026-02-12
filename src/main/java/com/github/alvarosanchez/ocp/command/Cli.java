package com.github.alvarosanchez.ocp.command;

import com.github.kusoroadeolu.clique.Clique;
import com.github.kusoroadeolu.clique.ansi.ColorCode;
import com.github.kusoroadeolu.clique.config.BorderStyle;
import com.github.kusoroadeolu.clique.config.CellAlign;
import com.github.kusoroadeolu.clique.config.TableConfiguration;
import picocli.CommandLine;

public final class Cli {

    private static final String CLEAR_LINE = "\r\u001B[2K";

    public static final TableConfiguration TABLE_CFG = TableConfiguration
            .immutableBuilder()
            .borderStyle(borderStyle())
            .padding(2)
            .alignment(CellAlign.CENTER)
            .build();

    private Cli() {
    }

    public static void print(String message) {
        Clique.parser().print(message);
    }

    public static void info(String message) {
        Clique.parser().print("[tokyo_blue]" + message + "[/]");
    }

    public static void success(String message) {
        Clique.parser().print("[green]" + message + "[/]");
    }

    public static void warning(String message) {
        Clique.parser().print("[tokyo_yellow]" + message + "[/]");
    }

    public static void error(String message) {
        System.err.println(Clique.parser().parse("[tokyo_red]Error:[/] ") + message);
    }

    public static String th(String content) {
        return "[*tokyo_blue]" + content + "[/]";
    }

    public static void init() {
        Clique.enableCliqueColors(CommandLine.Help.Ansi.AUTO.enabled());
        Clique.registerTheme("tokyo-night");
    }

    public static boolean beginTransientInfo(String message) {
        if (System.console() == null) {
            return false;
        }
        System.out.print("\r" + Clique.parser().parse("[tokyo_blue]" + message + "[/]"));
        System.out.flush();
        return true;
    }

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
