package com.github.alvarosanchez.ocp.git;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.List;

@Singleton
public class GhProcessExecutor {

    public Process start(List<String> command) throws IOException {
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }
}
