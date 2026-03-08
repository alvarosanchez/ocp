package com.github.alvarosanchez.ocp.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.git.GitHubRepositoryClient.RepositoryVisibility;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubRepositoryClientTest {

    @TempDir
    Path tempDir;

    @Test
    void isAuthenticatedReturnsTrueWhenAuthStatusSucceeds() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(List.of(new StubProcess(0, "ok")));
        GitHubRepositoryClient client = new GitHubRepositoryClient(processExecutor);

        assertTrue(client.isAuthenticated());
        assertEquals(List.of(List.of("gh", "auth", "status")), processExecutor.commands());
    }

    @Test
    void isAuthenticatedReturnsFalseWhenGhIsUnavailable() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(new IOException("missing"));
        GitHubRepositoryClient client = new GitHubRepositoryClient(processExecutor);

        assertEquals(false, client.isAuthenticated());
    }

    @Test
    void createRepositoryFromSourceRunsExpectedGhCommand() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(List.of(new StubProcess(0, "created")));
        GitHubRepositoryClient client = new GitHubRepositoryClient(processExecutor);
        Path repositoryPath = tempDir.resolve("repo");

        client.createRepositoryFromSource("repo", repositoryPath, RepositoryVisibility.PRIVATE);

        assertEquals(
            List.of(
                List.of(
                    "gh",
                    "repo",
                    "create",
                    "repo",
                    "--source",
                    repositoryPath.toString(),
                    "--remote",
                    "origin",
                    "--push",
                    "--private"
                )
            ),
            processExecutor.commands()
        );
    }

    @Test
    void createRepositoryFromSourceThrowsWhenGhFails() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(List.of(new StubProcess(1, "not authenticated")));
        GitHubRepositoryClient client = new GitHubRepositoryClient(processExecutor);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> client.createRepositoryFromSource("repo", tempDir.resolve("repo"), RepositoryVisibility.PUBLIC)
        );

        assertTrue(thrown.getMessage().contains("gh repo create failed"));
    }

    private static final class StubProcessExecutor extends GhProcessExecutor {

        private final Deque<Process> processes;
        private final IOException exception;
        private final List<List<String>> commands;

        StubProcessExecutor(List<Process> processes) {
            this.processes = new ArrayDeque<>(processes);
            this.exception = null;
            this.commands = new ArrayList<>();
        }

        StubProcessExecutor(IOException exception) {
            this.processes = new ArrayDeque<>();
            this.exception = exception;
            this.commands = new ArrayList<>();
        }

        @Override
        public Process start(List<String> command) throws IOException {
            commands.add(List.copyOf(command));
            if (exception != null) {
                throw exception;
            }
            if (processes.isEmpty()) {
                throw new IOException("No process available");
            }
            return processes.removeFirst();
        }

        List<List<String>> commands() {
            return List.copyOf(commands);
        }
    }

    private static class StubProcess extends Process {

        private final int exitCode;
        private final String output;

        StubProcess(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Process> onExit() {
            return java.util.concurrent.CompletableFuture.completedFuture(this);
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }
    }
}
