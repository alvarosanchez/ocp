package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.command.interactive.InteractiveApp;
import com.github.alvarosanchez.ocp.service.OnboardingService;
import com.github.alvarosanchez.ocp.service.ProfileService;
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

    private static final class ApplicationContextProvider {
        private static final ThreadLocal<ApplicationContext> CURRENT = new ThreadLocal<>();

        private ApplicationContextProvider() {
        }

        static void set(ApplicationContext context) {
            CURRENT.set(context);
        }

        static void clear() {
            CURRENT.remove();
        }

        static ApplicationContext context() {
            ApplicationContext context = CURRENT.get();
            if (context == null) {
                throw new IllegalStateException("Interactive application context is unavailable.");
            }
            return context;
        }
    }


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
        Cli.init();
        try {
            SystemDependencies.verifyAll();
        } catch (IllegalStateException e) {
            Cli.error(e.getMessage());
            System.exit(1);
            return;
        }

        ApplicationContextBuilder builder = ApplicationContext.builder(OcpCommand.class, Environment.CLI)
            .propertySources(new CommandLinePropertySource(io.micronaut.core.cli.CommandLine.parse(args)));
        int exitCode;
        try (ApplicationContext context = builder.start()) {
            ApplicationContextProvider.set(context);
            runStartupVersionCheck(context, args);
            exitCode = new CommandLine(OcpCommand.class, new MicronautFactory(context)).execute(args);
        } finally {
            ApplicationContextProvider.clear();
        }
        System.exit(exitCode);
    }

    public static void runStartupVersionCheck(ApplicationContext context, String[] args) {
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

    public static String startupVersionCheckFailureMessage(RuntimeException exception) {
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

    ApplicationContext applicationContext() {
        return ApplicationContextProvider.context();
    }

    InteractiveApp createInteractiveApp() {
        return new InteractiveApp(profileService, repositoryService, onboardingService, repositoryPostCreationService, objectMapper, applicationContext());
    }

}
