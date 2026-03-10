package com.github.alvarosanchez.ocp.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class SystemDependencies {

    private static final List<DependencyCheck> REQUIRED_DEPENDENCIES = List.of(
        new DependencyCheck("git", List.of("--version"), "Install Git and ensure it is available in PATH.")
    );

    private SystemDependencies() {
    }

    static void verifyAll() {
        for (DependencyCheck dependency : REQUIRED_DEPENDENCIES) {
            verify(dependency);
        }
    }

    private static void verify(DependencyCheck dependency) {
        List<String> command = new ArrayList<>();
        command.add(dependency.executable());
        command.addAll(dependency.versionArgs());
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw missingDependency(dependency);
            }
        } catch (IOException e) {
            throw missingDependency(dependency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking system dependencies.", e);
        }
    }

    private static IllegalStateException missingDependency(DependencyCheck dependency) {
        return new IllegalStateException(
            "Missing required dependency `"
                + dependency.executable()
                + "`. "
                + dependency.installHint()
        );
    }

    private record DependencyCheck(String executable, List<String> versionArgs, String installHint) {
    }
}
