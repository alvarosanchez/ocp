package com.github.alvarosanchez.ocp.git;

import java.io.IOException;
import java.util.List;
import jakarta.inject.Singleton;

/**
 * Low-level process launcher used for git command execution.
 */
@Singleton
public class GitProcessExecutor {

    /**
     * Starts a process for the provided command.
     *
     * @param command command and arguments to execute
     * @return started process instance
     * @throws IOException when the process cannot be started
     */
    public Process start(List<String> command) throws IOException {
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }
}
