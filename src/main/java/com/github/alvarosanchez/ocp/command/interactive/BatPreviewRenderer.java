package com.github.alvarosanchez.ocp.command.interactive;

import dev.tamboui.text.Text;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class BatPreviewRenderer {

    private static final Duration BAT_HIGHLIGHT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration BAT_HIGHLIGHT_READER_JOIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration BAT_HIGHLIGHT_READER_CANCEL_JOIN_TIMEOUT = Duration.ofMillis(200);
    private static final Duration BAT_AVAILABILITY_TIMEOUT = Duration.ofSeconds(1);

    private final AnsiTextParser ansiTextParser = new AnsiTextParser();

    Text highlight(Path filePath) {
        if (filePath == null) {
            return null;
        }

        String highlighted = highlightWithBat(filePath);
        if (highlighted == null) {
            return null;
        }
        return ansiTextParser.parse(highlighted);
    }

    boolean probeAvailability() {
        Process process;
        try {
            process = new ProcessBuilder("bat", "--version").start();
        } catch (IOException _) {
            return false;
        }
        try {
            boolean completed = process.waitFor(BAT_AVAILABILITY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return completed && process.exitValue() == 0;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String highlightWithBat(Path filePath) {
        List<String> command = new ArrayList<>();
        command.add("bat");
        command.add("--color=always");
        command.add("--style=plain");
        command.add("--paging=never");

        String language = batLanguage(extension(filePath));
        if (language != null) {
            command.add("--language");
            command.add(language);
        }
        command.add(filePath.toString());

        Process process;
        try {
            process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        } catch (IOException _) {
            return null;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                process.getInputStream().transferTo(output);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        try {
            boolean completed = process.waitFor(BAT_HIGHLIGHT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                reader.join(BAT_HIGHLIGHT_READER_CANCEL_JOIN_TIMEOUT.toMillis());
                return null;
            }
            reader.join(BAT_HIGHLIGHT_READER_JOIN_TIMEOUT.toMillis());
            if (process.exitValue() != 0) {
                return null;
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (UncheckedIOException _) {
            return null;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String batLanguage(String extension) {
        return switch (extension) {
            case "jsonc", "json" -> "json";
            case "yaml", "yml" -> "yaml";
            case "toml" -> "toml";
            case "properties" -> "properties";
            case "java" -> "java";
            case "kts", "kt" -> "kotlin";
            case "groovy" -> "groovy";
            case "sh", "bash" -> "bash";
            case "js" -> "javascript";
            case "ts" -> "typescript";
            case "py" -> "python";
            default -> null;
        };
    }

    private String extension(Path path) {
        if (path.getFileName() == null) {
            return "";
        }
        String fileName = path.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1).toLowerCase();
    }
}
