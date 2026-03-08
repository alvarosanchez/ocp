package com.github.alvarosanchez.ocp.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jakarta.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client for running git operations against profile repositories.
 */
@Singleton
public final class GitRepositoryClient {

    private static final String AUTOMATED_GIT_USER_EMAIL = "ocp@local";
    private static final String AUTOMATED_GIT_USER_NAME = "ocp";

    private final GitProcessExecutor processExecutor;

    /**
     * Creates a git repository client.
     *
     * @param processExecutor process launcher used to run git commands
     */
    public GitRepositoryClient(GitProcessExecutor processExecutor) {
        this.processExecutor = processExecutor;
    }

    /**
     * Clones a git repository into a local path.
     *
     * @param uri repository URI to clone
     * @param localPath local destination path for the clone
     */
    public void clone(String uri, Path localPath) {
        try {
            Path parent = localPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Process process = processExecutor.start(List.of("git", "clone", "--quiet", uri, localPath.toString()));
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("git clone failed for " + uri + " (exit code " + exitCode + ")");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to clone git repository " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while cloning git repository " + uri, e);
        }
    }

    /**
     * Pulls latest changes for a local repository using fast-forward mode.
     *
     * @param localPath local repository path
     */
    public void pull(Path localPath) {
        runInRepository(localPath, "pull", List.of("git", "-C", localPath.toString(), "pull", "--ff-only", "--quiet"));
    }

    /**
     * Returns whether a local repository has uncommitted changes.
     *
     * @param localPath local repository path
     * @return {@code true} when there are local uncommitted changes
     */
    public boolean hasLocalChanges(Path localPath) {
        String output = runAndCapture(localPath, "status", List.of("git", "-C", localPath.toString(), "status", "--porcelain"));
        return !output.trim().isEmpty();
    }

    /**
     * Returns the colored local diff for a repository.
     *
     * @param localPath local repository path
     * @return ANSI-colored diff output
     */
    public String localDiff(Path localPath) {
        return runAndCapture(
            localPath,
            "diff",
            List.of("git", "-c", "color.ui=always", "-C", localPath.toString(), "diff", "--color=always")
        );
    }

    /**
     * Discards local modifications and untracked files in a repository.
     *
     * @param localPath local repository path
     */
    public void discardLocalChanges(Path localPath) {
        runInRepository(localPath, "reset", List.of("git", "-C", localPath.toString(), "reset", "--hard", "HEAD"));
        runInRepository(localPath, "clean", List.of("git", "-C", localPath.toString(), "clean", "-fd"));
    }

    public void commitLocalChangesAndPush(Path localPath, String message) {
        stageAllChanges(localPath);
        commitChanges(localPath, message);
        runInRepository(localPath, "push", List.of("git", "-C", localPath.toString(), "push"));
    }

    /**
     * Commits all local changes and force-pushes them to the tracked branch.
     *
     * @param localPath local repository path
     */
    public void commitLocalChangesAndForcePush(Path localPath) {
        stageAllChanges(localPath);
        commitChanges(localPath, "chore: persist local opencode configuration changes");
        runInRepository(localPath, "push", List.of("git", "-C", localPath.toString(), "push", "--force-with-lease"));
    }

    /**
     * Returns whether local HEAD differs from the upstream tracked branch tip.
     *
     * @param localPath local repository path
     * @return {@code true} when local and upstream tips differ, {@code false} otherwise
     */
    public boolean differsFromUpstream(Path localPath) {
        runInRepository(localPath, "fetch", List.of("git", "-C", localPath.toString(), "fetch", "--quiet"));
        return runDiffQuiet(localPath, List.of("git", "-C", localPath.toString(), "diff", "--quiet", "HEAD", "@{upstream}"));
    }

    /**
     * Returns metadata for the latest local commit in a repository.
     *
     * @param localPath local repository path
     * @return metadata for the latest local commit
     */
    public CommitMetadata latestCommit(Path localPath) {
        String output = runAndCapture(
            localPath,
            "log",
            List.of("git", "-C", localPath.toString(), "log", "-1", "--format=%h%x1f%ct%x1f%s")
        ).trim();

        String[] tokens = output.split("\\u001f", 3);
        if (tokens.length != 3) {
            throw new IllegalStateException("Failed to parse git log output for " + localPath + ": " + output);
        }

        try {
            return new CommitMetadata(tokens[0], Long.parseLong(tokens[1]), tokens[2]);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Failed to parse git log timestamp for " + localPath + ": " + output, e);
        }
    }

    /**
     * Initializes a git repository in the provided local path.
     *
     * @param localPath local repository path
     */
    public void init(Path localPath) {
        try {
            Files.createDirectories(localPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create repository directory " + localPath, e);
        }
        runInRepository(localPath, "init", List.of("git", "-C", localPath.toString(), "init", "--quiet"));
    }

    public boolean hasRemote(Path localPath, String remoteName) {
        GitCommandResult result = runGitAndCaptureExitCode(
            localPath,
            "remote get-url",
            List.of("git", "-C", localPath.toString(), "remote", "get-url", remoteName)
        );
        return switch (result.exitCode()) {
            case 0 -> true;
            case 2 -> false;
            default -> {
                String trimmedOutput = result.output().trim();
                String detail = trimmedOutput.isEmpty() ? "" : ": " + trimmedOutput;
                throw new IllegalStateException(
                    "git remote get-url failed for " + localPath + " (exit code " + result.exitCode() + ")" + detail
                );
            }
        };
    }

    public String remoteUri(Path localPath, String remoteName) {
        return runAndCapture(
            localPath,
            "remote get-url",
            List.of("git", "-C", localPath.toString(), "remote", "get-url", remoteName)
        ).trim();
    }

    public boolean createInitialCommit(Path localPath, String message) {
        stageAllChanges(localPath);
        if (hasStagedChanges(localPath)) {
            commitChanges(localPath, message);
            return true;
        }
        return false;
    }

    private void stageAllChanges(Path localPath) {
        runInRepository(localPath, "add", List.of("git", "-C", localPath.toString(), "add", "-A"));
    }

    private boolean hasStagedChanges(Path localPath) {
        return !runAndCapture(
            localPath,
            "status",
            List.of("git", "-C", localPath.toString(), "status", "--porcelain")
        ).trim().isEmpty();
    }

    private void commitChanges(Path localPath, String message) {
        runInRepository(
            localPath,
            "commit",
            List.of(
                "git",
                "-C",
                localPath.toString(),
                "-c",
                "user.email=" + AUTOMATED_GIT_USER_EMAIL,
                "-c",
                "user.name=" + AUTOMATED_GIT_USER_NAME,
                "commit",
                "-m",
                message
            )
        );
    }

    private void runInRepository(Path localPath, String operation, List<String> command) {
        runAndCapture(localPath, operation, command);
    }

    private boolean runDiffQuiet(Path localPath, List<String> command) {
        try {
            Process process = processExecutor.start(command);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return false;
            }
            if (exitCode == 1) {
                return true;
            }
            throw new IllegalStateException("git diff failed for " + localPath + " (exit code " + exitCode + "): " + output);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to diff git repository " + localPath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git diff for " + localPath, e);
        }
    }

    private String runAndCapture(Path localPath, String operation, List<String> command) {
        return runAndCaptureAllowingExitCode(localPath, operation, command, 0);
    }

    private String runAndCaptureAllowingExitCode(Path localPath, String operation, List<String> command, int... allowedExitCodes) {
        try {
            Process process = processExecutor.start(command);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (!contains(allowedExitCodes, exitCode)) {
                String trimmedOutput = output.trim();
                String detail = trimmedOutput.isEmpty() ? "" : ": " + trimmedOutput;
                throw new IllegalStateException(
                    "git " + operation + " failed for " + localPath + " (exit code " + exitCode + ")" + detail
                );
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run git " + operation + " in repository " + localPath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git " + operation + " in repository " + localPath, e);
        }
    }

    private boolean contains(int[] values, int value) {
        for (int candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    private GitCommandResult runGitAndCaptureExitCode(Path localPath, String operation, List<String> command) {
        try {
            Process process = processExecutor.start(command);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new GitCommandResult(exitCode, output);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run git " + operation + " in repository " + localPath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git " + operation + " in repository " + localPath, e);
        }
    }

    private int runExitCodeOnly(Path localPath, String operation, List<String> command) {
        try {
            Process process = processExecutor.start(command);
            process.getInputStream().readAllBytes();
            return process.waitFor();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run git " + operation + " in repository " + localPath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git " + operation + " in repository " + localPath, e);
        }
    }

    /**
     * Latest commit metadata for a repository.
     *
     * @param shortSha short commit SHA
     * @param epochSeconds commit timestamp in epoch seconds
     * @param message commit message subject
     */
    public record CommitMetadata(String shortSha, long epochSeconds, String message) {
    }

    private record GitCommandResult(int exitCode, String output) {
    }
}
