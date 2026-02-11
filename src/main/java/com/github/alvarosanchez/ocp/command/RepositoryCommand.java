package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.model.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
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
        RepositoryCommand.DeleteCommand.class,
        RepositoryCommand.CreateCommand.class
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
    static class AddCommand implements Callable<Integer> {

        private final RepositoryService repositoryService;

        @Inject
        AddCommand(RepositoryService repositoryService) {
            this.repositoryService = repositoryService;
        }

        @Parameters(index = "0", description = "Repository URI.")
        private String repositoryUri;

        /**
         * Adds a repository to the local registry.
         *
         * @return command exit code
         */
        @Override
        public Integer call() {
            try {
                RepositoryEntry added = repositoryService.add(repositoryUri);
                System.out.println("Added repository `" + added.name() + "`.");
                return 0;
            } catch (RuntimeException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "delete", description = "Delete an added repository.")
    static class DeleteCommand implements Callable<Integer> {

        private final RepositoryService repositoryService;

        @Inject
        DeleteCommand(RepositoryService repositoryService) {
            this.repositoryService = repositoryService;
        }

        @Parameters(index = "0", description = "Repository name.")
        private String repositoryName;

        /**
         * Deletes a repository from the local registry.
         *
         * @return command exit code
         */
        @Override
        public Integer call() {
            try {
                RepositoryEntry deleted = repositoryService.delete(repositoryName);
                System.out.println("Deleted repository `" + deleted.name() + "`.");
                return 0;
            } catch (RuntimeException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "create", description = "Create a profile repository in the current directory.")
    static class CreateCommand implements Callable<Integer> {

        private final RepositoryService repositoryService;

        @Inject
        CreateCommand(RepositoryService repositoryService) {
            this.repositoryService = repositoryService;
        }

        @Parameters(index = "0", description = "Repository directory name.")
        private String repositoryName;

        @Option(names = "--profile-name", description = "Optional initial profile name.")
        private String profileName;

        /**
         * Creates a new repository scaffold.
         *
         * @return command exit code
         */
        @Override
        public Integer call() {
            try {
                Path createdRepository = repositoryService.create(repositoryName, profileName);
                System.out.println("Created repository at " + createdRepository);
                return 0;
            } catch (RuntimeException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }
}
