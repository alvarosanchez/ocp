package com.github.alvarosanchez.ocp.command;

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
                new OcpInteractiveApp(profileService, repositoryService, objectMapper).run();
                return;
            } catch (Exception | LinkageError e) {
                Cli.warning(
                    "Interactive mode is unavailable (" + e.getClass().getSimpleName() + ": " + safeMessage(e)
                        + "); falling back to standard usage output."
                );
                Cli.warning("Interactive mode cause chain: " + causeChain(e));
                Cli.info("Tip: set TAMBOUI_BACKEND=panama or TAMBOUI_BACKEND=jline3 to force a backend.");
            }
        }
        CommandLine.usage(this, System.out);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "no details";
        }
        return message;
    }

    private String causeChain(Throwable throwable) {
        StringBuilder chain = new StringBuilder();
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor != null && depth < 8) {
            if (depth > 0) {
                chain.append(" -> ");
            }
            chain.append(cursor.getClass().getSimpleName()).append(": ").append(safeMessage(cursor));
            Throwable next = cursor.getCause();
            if (next == cursor) {
                break;
            }
            cursor = next;
            depth++;
        }
        return chain.toString();
    }

    private boolean shouldStartInteractiveMode() {
        String disabledByEnvironment = System.getenv("OCP_NO_UI");
        if (disabledByEnvironment != null && !disabledByEnvironment.isBlank()) {
            return false;
        }
        String terminal = System.getenv("TERM");
        if (terminal == null || terminal.isBlank() || "dumb".equalsIgnoreCase(terminal.trim())) {
            return false;
        }
        return System.console() != null;
    }
}
