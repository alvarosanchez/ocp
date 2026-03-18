package com.github.alvarosanchez.ocp.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SystemDependenciesTest {

    @Test
    void verifyAllSucceedsWhenGitIsAvailable() {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");

        assertDoesNotThrow(SystemDependencies::verifyAll);
    }

    @Test
    void verifyAllRestoresInterruptFlagWhenInterrupted() {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        boolean interruptedBefore = Thread.currentThread().isInterrupted();
        Thread.currentThread().interrupt();

        try {
            IllegalStateException thrown = assertThrows(IllegalStateException.class, SystemDependencies::verifyAll);
            assertTrue(thrown.getMessage().contains("Interrupted while checking system dependencies."));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            if (!interruptedBefore) {
                Thread.interrupted();
            }
        }
    }

    private static boolean isGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
