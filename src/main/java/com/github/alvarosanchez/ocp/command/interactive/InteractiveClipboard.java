package com.github.alvarosanchez.ocp.command.interactive;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class InteractiveClipboard {

    private static final long CLIPBOARD_COMMAND_TIMEOUT_SECONDS = 2L;

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


    static String clipboardUnavailableMessage(String operatingSystem) {
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
            if (!process.waitFor(CLIPBOARD_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
