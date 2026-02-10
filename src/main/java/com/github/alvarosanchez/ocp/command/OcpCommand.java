package com.github.alvarosanchez.ocp.command;

import io.micronaut.configuration.picocli.PicocliRunner;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Root Picocli command for the OCP CLI.
 */
@Command(
    name = "ocp",
    description = "OpenCode configuration profiles manager.",
    mixinStandardHelpOptions = true,
    subcommands = {
        CommandLine.HelpCommand.class,
        ProfileCommand.class,
        RepositoryCommand.class
    }
)
public class OcpCommand implements Runnable {

    /**
     * Entry point for the OCP command line application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        try {
            SystemDependencies.verifyAll();
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        int exitCode = PicocliRunner.execute(OcpCommand.class, args);
        System.exit(exitCode);
    }

    /**
     * Prints root command usage when no subcommand is provided.
     */
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
