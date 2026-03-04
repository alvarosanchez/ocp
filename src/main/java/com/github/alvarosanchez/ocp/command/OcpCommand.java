package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.command.interactive.InteractiveApp;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import io.micronaut.configuration.picocli.PicocliRunner;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Root Picocli command for the OCP CLI.
 */
@Command(
    name = "ocp",
    description = "OpenCode configuration profiles manager.",
    mixinStandardHelpOptions = true,
    versionProvider = OcpVersionProvider.class,
    subcommands = {
        CommandLine.HelpCommand.class,
        ProfileCommand.class,
        RepositoryCommand.class
    }
)
public class OcpCommand implements Runnable {

    private final ProfileService profileService;
    private final RepositoryService repositoryService;
    private final ObjectMapper objectMapper;

    @Inject
    OcpCommand(ProfileService profileService, RepositoryService repositoryService, ObjectMapper objectMapper) {
        this.profileService = profileService;
        this.repositoryService = repositoryService;
        this.objectMapper = objectMapper;
    }

    /**
     * Entry point for the OCP command line application.
     *
     * @param args command-line arguments
     */
    static void main(String[] args) {
        Cli.init();
        try {
            SystemDependencies.verifyAll();
        } catch (IllegalStateException e) {
            Cli.error(e.getMessage());
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
        if (shouldStartInteractiveMode()) {
            try {
                new InteractiveApp(profileService, repositoryService, objectMapper).run();
            } catch (Exception e) {
                Cli.error("Interactive mode is unavailable: " + e.getMessage());
                Cli.error("Falling back to standard usage output");
            }
            return;
        }
        CommandLine.usage(this, System.out);
    }

    private boolean shouldStartInteractiveMode() {
        String terminal = System.getenv("TERM");
        if (terminal == null || terminal.isBlank() || "dumb".equalsIgnoreCase(terminal.trim())) {
            return false;
        }
        return System.console() != null;
    }
}
