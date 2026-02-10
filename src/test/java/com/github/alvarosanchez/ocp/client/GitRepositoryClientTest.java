package com.github.alvarosanchez.ocp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryClientTest {

    @TempDir
    Path tempDir;

    @Test
    void cloneRunsGitCloneAndCreatesParentDirectories() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(new StubProcess(0));
        Path localPath = tempDir.resolve("repositories/repo-one");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        client.clone("git@github.com:acme/repo-one.git", localPath);

        assertTrue(Files.exists(localPath.getParent()));
        assertEquals(
            List.of("git", "clone", "--quiet", "git@github.com:acme/repo-one.git", localPath.toString()),
            processExecutor.lastCommand()
        );
    }

    @Test
    void cloneThrowsWhenGitReturnsNonZeroExitCode() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(new StubProcess(1));
        Path localPath = tempDir.resolve("repositories/repo-two");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> client.clone("git@github.com:acme/repo-two.git", localPath)
        );

        assertTrue(thrown.getMessage().contains("exit code 1"));
    }

    @Test
    void cloneThrowsWhenProcessExecutorFails() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(new IOException("boom"));
        Path localPath = tempDir.resolve("repositories/repo-three");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> client.clone("git@github.com:acme/repo-three.git", localPath)
        );

        assertTrue(thrown.getMessage().contains("Failed to clone git repository"));
    }

    private static final class StubProcessExecutor extends GitProcessExecutor {

        private final Process process;
        private final IOException exception;
        private List<String> lastCommand;

        StubProcessExecutor(Process process) {
            this.process = process;
            this.exception = null;
        }

        StubProcessExecutor(IOException exception) {
            this.process = null;
            this.exception = exception;
        }

        @Override
        Process start(List<String> command) throws IOException {
            lastCommand = List.copyOf(command);
            if (exception != null) {
                throw exception;
            }
            return process;
        }

        List<String> lastCommand() {
            return lastCommand;
        }
    }

    private static final class StubProcess extends Process {

        private final int exitCode;

        StubProcess(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
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
