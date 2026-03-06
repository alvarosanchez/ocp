package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.command.interactive.InteractiveApp;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import com.github.alvarosanchez.ocp.service.VersionCheckService;
import io.micronaut.configuration.picocli.PicocliRunner;
import io.micronaut.context.ApplicationContext;
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
    public static void main(String[] args) {
        Cli.init();
        try {
            SystemDependencies.verifyAll();
        } catch (IllegalStateException e) {
            Cli.error(e.getMessage());
            System.exit(1);
            return;
        }

        runStartupVersionCheck(args);

        int exitCode = PicocliRunner.execute(OcpCommand.class, args);
        System.exit(exitCode);
    }

    static void runStartupVersionCheck(String[] args) {
        try (ApplicationContext context = ApplicationContext.run()) {
            VersionCheckService.VersionCheckResult result = context.getBean(VersionCheckService.class).check();
            presentStartupVersionNotice(args, result.noticeMessage(), isInteractiveTerminal());
        } catch (RuntimeException e) {
            String message = "Could not check for newer ocp releases: " + e.getMessage();
            if (shouldDeferVersionNoticeToInteractiveUi(args, isInteractiveTerminal())) {
                Cli.setStartupNotice(message);
                return;
            }
            Cli.warning(message);
        }
    }

    static void presentStartupVersionNotice(String[] args, String noticeMessage, boolean interactiveTerminal) {
        if (noticeMessage == null || noticeMessage.isBlank()) {
            return;
        }
        if (shouldDeferVersionNoticeToInteractiveUi(args, interactiveTerminal)) {
            Cli.setStartupNotice(noticeMessage);
            return;
        }
        Cli.infoWithCodeHighlights(noticeMessage);
    }

    static boolean shouldDeferVersionNoticeToInteractiveUi(String[] args, boolean interactiveTerminal) {
        return interactiveTerminal && args.length == 0;
    }

    static boolean isInteractiveTerminal() {
        String terminal = System.getenv("TERM");
        if (terminal == null || terminal.isBlank() || "dumb".equalsIgnoreCase(terminal.trim())) {
            return false;
        }
        return System.console() != null;
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
        return isInteractiveTerminal();
    }
}
