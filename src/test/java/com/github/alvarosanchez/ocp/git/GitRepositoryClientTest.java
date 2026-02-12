package com.github.alvarosanchez.ocp.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    @Test
    void cloneRestoresInterruptFlagWhenWaitForIsInterrupted() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(new InterruptingProcess());
        Path localPath = tempDir.resolve("repositories/repo-interrupted-clone");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        try {
            IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> client.clone("git@github.com:acme/repo-interrupted.git", localPath)
            );

            assertTrue(thrown.getMessage().contains("Interrupted while cloning git repository"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void pullRestoresInterruptFlagWhenWaitForIsInterrupted() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(new InterruptingProcess());
        Path localPath = tempDir.resolve("repositories/repo-interrupted-pull");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        try {
            IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> client.pull(localPath)
            );

            assertTrue(thrown.getMessage().contains("Interrupted while running git pull"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void differsFromUpstreamRestoresInterruptFlagWhenDiffIsInterrupted() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(
            List.<Process>of(new StubProcess(0, ""), new InterruptingProcess())
        );
        Path localPath = tempDir.resolve("repositories/repo-interrupted-diff");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        try {
            IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> client.differsFromUpstream(localPath)
            );

            assertTrue(thrown.getMessage().contains("Interrupted while running git diff"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void differsFromUpstreamRunsFetchAndDiffQuiet() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(
            List.of(new StubProcess(0, ""), new StubProcess(0, ""))
        );
        Path localPath = tempDir.resolve("repositories/repo-four");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        boolean differsFromUpstream = client.differsFromUpstream(localPath);

        assertEquals(false, differsFromUpstream);
        assertEquals(
            List.of(
                List.of("git", "-C", localPath.toString(), "fetch", "--quiet"),
                List.of("git", "-C", localPath.toString(), "diff", "--quiet", "HEAD", "@{upstream}")
            ),
            processExecutor.commands()
        );
    }

    @Test
    void differsFromUpstreamReturnsTrueWhenDiffExitCodeIsOne() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(
            List.of(new StubProcess(0, ""), new StubProcess(1, ""))
        );
        Path localPath = tempDir.resolve("repositories/repo-five");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        boolean differsFromUpstream = client.differsFromUpstream(localPath);

        assertTrue(differsFromUpstream);
    }

    @Test
    void differsFromUpstreamThrowsWhenDiffReturnsUnexpectedExitCode() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(
            List.of(new StubProcess(0, ""), new StubProcess(2, "fatal: ambiguous argument"))
        );
        Path localPath = tempDir.resolve("repositories/repo-five");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> client.differsFromUpstream(localPath));

        assertTrue(thrown.getMessage().contains("git diff failed"));
    }

    @Test
    void hasLocalChangesReturnsFalseWhenStatusIsEmpty() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(List.of(new StubProcess(0, "")));
        Path localPath = tempDir.resolve("repositories/repo-eight");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        boolean hasLocalChanges = client.hasLocalChanges(localPath);

        assertEquals(false, hasLocalChanges);
        assertEquals(
            List.of(List.of("git", "-C", localPath.toString(), "status", "--porcelain")),
            processExecutor.commands()
        );
    }

    @Test
    void localDiffReturnsCapturedDiffOutput() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(List.of(new StubProcess(0, "diff --git a/opencode.json b/opencode.json\n")));
        Path localPath = tempDir.resolve("repositories/repo-nine");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        String diff = client.localDiff(localPath);

        assertTrue(diff.contains("diff --git"));
    }

    @Test
    void discardLocalChangesRunsResetAndClean() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(
            List.of(new StubProcess(0, ""), new StubProcess(0, ""))
        );
        Path localPath = tempDir.resolve("repositories/repo-ten");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        client.discardLocalChanges(localPath);

        assertEquals(
            List.of(
                List.of("git", "-C", localPath.toString(), "reset", "--hard", "HEAD"),
                List.of("git", "-C", localPath.toString(), "clean", "-fd")
            ),
            processExecutor.commands()
        );
    }

    @Test
    void latestCommitParsesShaTimestampAndMessage() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(List.of(new StubProcess(0, "abc1234\u001f1739382350\u001ffeat: test\n")));
        Path localPath = tempDir.resolve("repositories/repo-six");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        GitRepositoryClient.CommitMetadata metadata = client.latestCommit(localPath);

        assertEquals("abc1234", metadata.shortSha());
        assertEquals(1739382350L, metadata.epochSeconds());
        assertEquals("feat: test", metadata.message());
    }

    @Test
    void latestCommitThrowsWhenOutputIsMalformed() {
        StubProcessExecutor processExecutor = new StubProcessExecutor(List.of(new StubProcess(0, "malformed")));
        Path localPath = tempDir.resolve("repositories/repo-seven");

        GitRepositoryClient client = new GitRepositoryClient(processExecutor);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> client.latestCommit(localPath));

        assertTrue(thrown.getMessage().contains("Failed to parse git log output"));
    }

    private static final class StubProcessExecutor extends GitProcessExecutor {

        private final Deque<Process> processes;
        private final IOException exception;
        private final List<List<String>> commands;

        StubProcessExecutor(Process process) {
            this.processes = new ArrayDeque<>(List.of(process));
            this.exception = null;
            this.commands = new ArrayList<>();
        }

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

        List<String> lastCommand() {
            return commands.getLast();
        }

        List<List<String>> commands() {
            return List.copyOf(commands);
        }
    }

    private static class StubProcess extends Process {

        private final int exitCode;
        private final String output;

        StubProcess(int exitCode) {
            this(exitCode, "");
        }

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
        public int waitFor() throws InterruptedException {
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

    private static final class InterruptingProcess extends StubProcess {

        InterruptingProcess() {
            super(0, "");
        }

        @Override
        public int waitFor() throws InterruptedException {
            throw new InterruptedException("Interrupted for test");
        }
    }
}
