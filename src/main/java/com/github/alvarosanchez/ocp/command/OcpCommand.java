package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.command.interactive.InteractiveApp;
import com.github.alvarosanchez.ocp.service.OnboardingService;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryMetadataMigrationService;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import com.github.alvarosanchez.ocp.service.VersionCheckService;
import io.micronaut.configuration.picocli.MicronautFactory;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.env.CommandLinePropertySource;
import io.micronaut.context.env.Environment;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;
import java.nio.file.Path;
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
    private final OnboardingService onboardingService;
    private final RepositoryPostCreationService repositoryPostCreationService;
    private final ObjectMapper objectMapper;

    @Inject
    OcpCommand(
        ProfileService profileService,
        RepositoryService repositoryService,
        OnboardingService onboardingService,
        RepositoryPostCreationService repositoryPostCreationService,
        ObjectMapper objectMapper
    ) {
        this.profileService = profileService;
        this.repositoryService = repositoryService;
        this.onboardingService = onboardingService;
        this.repositoryPostCreationService = repositoryPostCreationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Entry point for the OCP command line application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        int exitCode = execute(args);
        System.exit(exitCode);
    }

    static int execute(String[] args) {
        Cli.init();
        try {
            SystemDependencies.verifyAll();
        } catch (IllegalStateException e) {
            Cli.error(e.getMessage());
            return 1;
        }

        ApplicationContextBuilder builder = ApplicationContext.builder(OcpCommand.class, Environment.CLI)
            .propertySources(new CommandLinePropertySource(io.micronaut.core.cli.CommandLine.parse(args)));
        try (ApplicationContext context = builder.start()) {
            try {
                runStartupMetadataMigration(context);
            } catch (RuntimeException e) {
                Cli.error(e.getMessage());
                return 1;
            }
            runStartupVersionCheck(context, args);
            return new CommandLine(OcpCommand.class, new MicronautFactory(context)).execute(args);
        }
    }

    static void runStartupMetadataMigration(ApplicationContext context) {
        try {
            context.getBean(RepositoryMetadataMigrationService.class).migrateLegacyExtendsFromScalars();
        } catch (RuntimeException e) {
            throw new IllegalStateException(startupMetadataMigrationFailureMessage(e), e);
        }
    }

    static String startupMetadataMigrationFailureMessage(RuntimeException exception) {
        String baseMessage = "Could not migrate legacy repository metadata before startup.";
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return baseMessage;
        }
        return baseMessage + " Details: " + exception.getMessage();
    }

    static void runStartupVersionCheck(ApplicationContext context, String[] args) {
        try {
            VersionCheckService.VersionCheckResult result = context.getBean(VersionCheckService.class).check();
            presentStartupVersionNotice(args, result.noticeMessage(), isInteractiveTerminal());
        } catch (RuntimeException e) {
            String message = startupVersionCheckFailureMessage(e);
            if (shouldDeferVersionNoticeToInteractiveUi(args, isInteractiveTerminal())) {
                Cli.setStartupNotice(message);
                return;
            }
            Cli.warning(message);
        }
    }

    static String startupVersionCheckFailureMessage(RuntimeException exception) {
        String baseMessage = "Could not check for newer ocp releases."
            + " Verify your OCP config file: "
            + resolvedConfigFilePath();
        String detail = sanitizedStartupFailureDetail(exception);
        if (detail == null || detail.isBlank()) {
            return baseMessage;
        }
        return baseMessage + " Details: " + detail;
    }

    private static String sanitizedStartupFailureDetail(RuntimeException exception) {
        if (exception == null) {
            return null;
        }
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            return null;
        }
        if (detail.contains("repository registry")) {
            return "Unable to read or write OCP config file at " + resolvedConfigFilePath();
        }
        return detail;
    }

    private static Path resolvedConfigFilePath() {
        String configuredPath = System.getProperty("ocp.config.dir");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath).resolve("config.json");
        }
        return Path.of(System.getProperty("user.home"), ".config", "ocp", "config.json");
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
                createInteractiveApp().run();
            } catch (Exception e) {
                Cli.error("Interactive mode is unavailable: " + e.getMessage());
                Cli.error("Falling back to standard usage output");
                CommandLine.usage(this, System.out);
            }
            return;
        }
        CommandLine.usage(this, System.out);
    }

    boolean shouldStartInteractiveMode() {
        return isInteractiveTerminal();
    }

    InteractiveApp createInteractiveApp() {
        return new InteractiveApp(profileService, repositoryService, onboardingService, repositoryPostCreationService, objectMapper);
    }

}
