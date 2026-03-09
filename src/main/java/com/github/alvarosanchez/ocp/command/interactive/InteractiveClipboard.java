package com.github.alvarosanchez.ocp.command.interactive;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class InteractiveClipboard {

    private static final List<List<String>> MAC_COMMANDS = List.of(
        List.of("pbcopy")
    );
    private static final List<List<String>> WINDOWS_COMMANDS = List.of(
        List.of("cmd", "/c", "clip")
    );
    private static final List<List<String>> LINUX_COMMANDS = List.of(
        List.of("wl-copy"),
        List.of("xclip", "-selection", "clipboard"),
        List.of("xsel", "--clipboard", "--input")
    );

    private InteractiveClipboard() {
    }

    static void copy(String value) {
        String operatingSystem = System.getProperty("os.name", "").toLowerCase();
        List<List<String>> commands = operatingSystem.contains("mac")
            ? MAC_COMMANDS
            : operatingSystem.contains("windows")
                ? WINDOWS_COMMANDS
                : LINUX_COMMANDS;
        for (List<String> command : commands) {
            if (tryCopy(command, value)) {
                return;
            }
        }
        throw new IllegalStateException(clipboardUnavailableMessage(operatingSystem));
    }


    private static String clipboardUnavailableMessage(String operatingSystem) {
        if (operatingSystem.contains("mac")) {
            return "Clipboard is unavailable. Install or enable pbcopy, or use a desktop session with clipboard support.";
        }
        if (operatingSystem.contains("windows")) {
            return "Clipboard is unavailable. Ensure clip is available, or use a Windows session with clipboard support.";
        }
        return "Clipboard is unavailable. Install wl-copy, xclip, or xsel, or use a desktop session with clipboard support.";
    }

    private static boolean tryCopy(List<String> command, String value) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            return false;
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(value);
        } catch (IOException e) {
            process.destroyForcibly();
            return false;
        }
        try {
            return process.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
