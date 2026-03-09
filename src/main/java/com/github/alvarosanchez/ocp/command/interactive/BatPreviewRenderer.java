package com.github.alvarosanchez.ocp.command.interactive;

import dev.tamboui.text.Text;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class BatPreviewRenderer {

    private static final String BAT_PATH_ENV = "OCP_BAT_PATH";

    private static final Duration BAT_HIGHLIGHT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration BAT_HIGHLIGHT_READER_JOIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration BAT_HIGHLIGHT_READER_CANCEL_JOIN_TIMEOUT = Duration.ofMillis(200);
    private static final Duration BAT_AVAILABILITY_TIMEOUT = Duration.ofSeconds(1);

    private final AnsiTextParser ansiTextParser = new AnsiTextParser();

    Text highlight(Path filePath) {
        if (filePath == null) {
            return null;
        }

        String highlighted = highlightWithBat(filePath, null);
        if (highlighted == null) {
            return null;
        }
        return ansiTextParser.parse(highlighted);
    }

    Text highlight(Path filePath, String content) {
        if (filePath == null || content == null) {
            return null;
        }

        String highlighted = highlightWithBat(filePath, content);
        if (highlighted == null) {
            return null;
        }
        return ansiTextParser.parse(highlighted);
    }

    boolean probeAvailability() {
        String batExecutable = resolveBatExecutable();
        if (batExecutable == null) {
            return false;
        }
        Process process;
        try {
            process = new ProcessBuilder(batExecutable, "--version")
                .redirectErrorStream(true)
                .start();
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

    private String highlightWithBat(Path filePath, String content) {
        String batExecutable = resolveBatExecutable();
        if (batExecutable == null) {
            return null;
        }
        List<String> commandParts = new java.util.ArrayList<>();
        commandParts.add(batExecutable);
        commandParts.add("--color=always");
        commandParts.add("--style=plain");
        commandParts.add("--paging=never");
        if (content == null) {
            commandParts.add(filePath.toString());
        } else {
            commandParts.add("--file-name");
            commandParts.add(String.valueOf(filePath.getFileName()));
            commandParts.add("-");
        }

        Process process;
        try {
            process = new ProcessBuilder(commandParts)
                .redirectErrorStream(true)
                .start();
        } catch (IOException _) {
            return null;
        }

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread outputReader = Thread.ofPlatform().daemon(true).unstarted(() -> {
            try (var inputStream = process.getInputStream()) {
                inputStream.transferTo(outputBuffer);
            } catch (IOException e) {
                readFailure.set(e);
            }
        });
        outputReader.start();

        if (content != null) {
            try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(content);
                writer.flush();
            } catch (IOException _) {
                process.destroyForcibly();
                waitForOutputReader(outputReader, BAT_HIGHLIGHT_READER_CANCEL_JOIN_TIMEOUT);
                return null;
            }
        }
        try {
            boolean completed = process.waitFor(BAT_HIGHLIGHT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                waitForOutputReader(outputReader, BAT_HIGHLIGHT_READER_CANCEL_JOIN_TIMEOUT);
                return null;
            }
            waitForOutputReader(outputReader, BAT_HIGHLIGHT_READER_JOIN_TIMEOUT);
            if (outputReader.isAlive()) {
                process.destroyForcibly();
                waitForOutputReader(outputReader, BAT_HIGHLIGHT_READER_CANCEL_JOIN_TIMEOUT);
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            if (readFailure.get() != null) {
                return null;
            }
            return outputBuffer.toString(StandardCharsets.UTF_8);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            waitForOutputReader(outputReader, BAT_HIGHLIGHT_READER_CANCEL_JOIN_TIMEOUT);
            return null;
        }
    }

    private void waitForOutputReader(Thread outputReader, Duration timeout) {
        try {
            outputReader.join(timeout.toMillis());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private String resolveBatExecutable() {
        String configuredBatPath = System.getenv(BAT_PATH_ENV);
        if (configuredBatPath != null && !configuredBatPath.isBlank()) {
            Path configuredCandidate = Paths.get(configuredBatPath);
            if (Files.isRegularFile(configuredCandidate) && Files.isExecutable(configuredCandidate)) {
                return configuredCandidate.toString();
            }
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] executableNames = executableNames();
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path directory = Paths.get(entry);
            for (String executableName : executableNames) {
                Path candidate = directory.resolve(executableName);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    private String[] executableNames() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.startsWith("windows")) {
            return new String[] {"bat.exe", "bat.cmd", "bat.bat", "bat"};
        }
        return new String[] {"bat"};
    }
}
