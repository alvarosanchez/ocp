package com.github.alvarosanchez.ocp.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Command group for repository-related operations.
 */
@Command(
    name = "repository",
    description = "Manage profile repositories.",
    mixinStandardHelpOptions = true,
    subcommands = {
        RepositoryCommand.AddCommand.class,
        RepositoryCommand.DeleteCommand.class
    }
)
public class RepositoryCommand implements Runnable {

    /**
     * Prints repository command usage when no subcommand is provided.
     */
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "add", description = "Add a configuration repository.")
    static class AddCommand implements Runnable {

        @Parameters(index = "0", description = "Repository URI.")
        private String repositoryUri;

        @Override
        public void run() {
            System.out.println("Repository add placeholder for `" + repositoryUri + "`.");
        }
    }

    @Command(name = "delete", description = "Delete an added repository.")
    static class DeleteCommand implements Runnable {

        @Parameters(index = "0", description = "Repository name.")
        private String repositoryName;

        @Override
        public void run() {
            System.out.println("Repository delete placeholder for `" + repositoryName + "`.");
        }
    }
}
