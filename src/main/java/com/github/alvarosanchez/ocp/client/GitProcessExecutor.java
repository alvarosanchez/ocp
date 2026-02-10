package com.github.alvarosanchez.ocp.client;

import java.io.IOException;
import java.util.List;
import jakarta.inject.Singleton;

@Singleton
class GitProcessExecutor {

    Process start(List<String> command) throws IOException {
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }
}
