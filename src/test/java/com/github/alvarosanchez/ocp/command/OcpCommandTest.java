package com.github.alvarosanchez.ocp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.command.interactive.InteractiveApp;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.service.OnboardingService;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import io.micronaut.configuration.picocli.PicocliRunner;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.io.TempDir;

class OcpCommandTest {

    @AfterEach
    void resetStartupNotice() {
        Cli.consumeStartupNotice();
    }

    @Test
    void helpSubcommandPrintsUsage() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            int exitCode = PicocliRunner.execute(OcpCommand.class, "help");
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        assertTrue(output.toString().contains("Usage: ocp"));
    }

    @Test
    void versionOptionPrintsProjectVersion() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            int exitCode = PicocliRunner.execute(OcpCommand.class, "--version");
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        String expectedVersion = readExpectedVersion();
        assertTrue(output.toString().contains(expectedVersion));
    }

    @Test
    void noSubcommandFallsBackToUsageInNonInteractiveEnvironment() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            int exitCode = PicocliRunner.execute(OcpCommand.class);
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        assertTrue(output.toString().contains("Usage: ocp"));
    }

    @Test
    void interactiveStartupFailureFallsBackToUsageOutput() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try (ApplicationContext context = ApplicationContext.run()) {
            OcpCommand command = new OcpCommand(
                context.getBean(ProfileService.class),
                context.getBean(RepositoryService.class),
                context.getBean(OnboardingService.class),
                context.getBean(RepositoryPostCreationService.class),
                context.getBean(ObjectMapper.class)
            ) {
                @Override
                InteractiveApp createInteractiveApp() {
                    throw new IllegalStateException("boom");
                }

                @Override
                boolean shouldStartInteractiveMode() {
                    return true;
                }
            };

            try {
                System.setOut(new PrintStream(output));
                command.run();
            } finally {
                System.setOut(originalOut);
            }
        }

        assertTrue(output.toString().contains("Usage: ocp"));
    }

    @Test
    void startupNoticePrintsImmediatelyForNonInteractiveFlow() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            Cli.init();
            OcpCommand.presentStartupVersionNotice(new String[] {"profile", "list"}, "Update available", true);
        } finally {
            System.setOut(originalOut);
        }

        assertTrue(output.toString().contains("Update available"));
        assertNull(Cli.consumeStartupNotice());
    }

    @Test
    void startupNoticeIsDeferredForInteractiveRootMode() {
        Cli.init();

        OcpCommand.presentStartupVersionNotice(new String[0], "Update available", true);

        assertEquals("Update available", Cli.consumeStartupNotice());
        assertNull(Cli.consumeStartupNotice());
    }

    @Test
    void deferredStartupNoticeIsConsumedByInteractiveApp() throws Exception {
        Cli.init();
        Cli.setStartupNotice("Update available");

        try (ApplicationContext context = ApplicationContext.run()) {
            InteractiveApp app = new InteractiveApp(
                context.getBean(ProfileService.class),
                context.getBean(RepositoryService.class),
                context.getBean(OnboardingService.class),
                context.getBean(RepositoryPostCreationService.class),
                context.getBean(ObjectMapper.class)
            );

            assertEquals("Update available", app.startupUpdateNotice());
            assertEquals("Ready. Select a node in the hierarchy.", app.status());
            assertNull(Cli.consumeStartupNotice());
        }
    }

    @Test
    void mainMethodIsPublicStatic() {
        assertEquals(1, java.util.Arrays.stream(OcpCommand.class.getMethods())
            .filter(method -> method.getName().equals("main"))
            .filter(method -> method.getReturnType().equals(void.class))
            .filter(method -> java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[] {String[].class}))
            .count());
    }

    @Test
    void interactiveDeferralOnlyAppliesToRootInvocation() {
        assertTrue(OcpCommand.shouldDeferVersionNoticeToInteractiveUi(new String[0], true));
        assertFalse(OcpCommand.shouldDeferVersionNoticeToInteractiveUi(new String[] {"help"}, true));
        assertFalse(OcpCommand.shouldDeferVersionNoticeToInteractiveUi(new String[0], false));
    }

    @Test
    void startupFailureMessageIsStableWhenExceptionMessageIsMissing() {
        String previousConfigDir = System.getProperty("ocp.config.dir");
        System.setProperty("ocp.config.dir", "/tmp/ocp-config-test");
        try {
            String message = OcpCommand.startupVersionCheckFailureMessage(new RuntimeException());

            assertTrue(message.contains("Could not check for newer ocp releases."));
            assertTrue(message.contains(Path.of("/tmp/ocp-config-test").resolve("config.json").toString()));
            assertFalse(message.endsWith("null"));
        } finally {
            if (previousConfigDir == null) {
                System.clearProperty("ocp.config.dir");
            } else {
                System.setProperty("ocp.config.dir", previousConfigDir);
            }
        }
    }

    @Test
    void startupMetadataMigrationFailureMessageFallsBackToBaseMessageWhenDetailIsMissing() {
        assertEquals(
            "Could not migrate legacy repository metadata before startup.",
            OcpCommand.startupMetadataMigrationFailureMessage(new RuntimeException())
        );
        assertEquals(
            "Could not migrate legacy repository metadata before startup.",
            OcpCommand.startupMetadataMigrationFailureMessage(null)
        );
    }

    @Test
    void startupMetadataMigrationFailureMessageAppendsExceptionDetailWhenPresent() {
        assertEquals(
            "Could not migrate legacy repository metadata before startup. Details: broken metadata",
            OcpCommand.startupMetadataMigrationFailureMessage(new RuntimeException("broken metadata"))
        );
    }

    @Test
    void presentStartupVersionNoticeIgnoresBlankMessages() {
        Cli.init();

        OcpCommand.presentStartupVersionNotice(new String[] {"profile", "list"}, "   ", false);

        assertNull(Cli.consumeStartupNotice());
    }

    @Test
    void startupFailureMessageAppendsExceptionDetailWhenPresent() {
        String message = OcpCommand.startupVersionCheckFailureMessage(new RuntimeException("simulated failure"));

        assertTrue(message.contains("Details: simulated failure"));
    }

    @Test
    void startupFailureMessageSanitizesRepositoryRegistryDetail() {
        String previousConfigDir = System.getProperty("ocp.config.dir");
        System.setProperty("ocp.config.dir", "/tmp/ocp-config-test");
        try {
            String message = OcpCommand.startupVersionCheckFailureMessage(
                new RuntimeException("Failed to read repository registry")
            );

            assertFalse(message.contains("repository registry"));
            assertTrue(
                message.contains(
                    "Details: Unable to read or write OCP config file at "
                        + Path.of("/tmp/ocp-config-test").resolve("config.json")
                )
            );
        } finally {
            if (previousConfigDir == null) {
                System.clearProperty("ocp.config.dir");
            } else {
                System.setProperty("ocp.config.dir", previousConfigDir);
            }
        }
    }

    @Test
    @DisabledInNativeImage
    void helpInvocationMigratesLegacyExtendsFromBeforeExecution(@TempDir Path tempDir) throws Exception {
        Path repositoryMetadata = prepareLegacyStartupMigrationScenario(tempDir, "{\"profiles\":[{\"name\":\"child\",\"extends_from\":\"base\"}]}");

        CommandResult result = executeMainWithStartup(tempDir, "help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Usage: ocp"));
        assertTrue(metadataContainsCanonicalArray(repositoryMetadata));
    }

    @Test
    @DisabledInNativeImage
    void versionInvocationMigratesLegacyExtendsFromBeforeExecution(@TempDir Path tempDir) throws Exception {
        Path repositoryMetadata = prepareLegacyStartupMigrationScenario(tempDir, "{\"profiles\":[{\"name\":\"child\",\"extends_from\":\"base\"}]}");

        CommandResult result = executeMainWithStartup(tempDir, "--version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(readExpectedVersion()));
        assertTrue(metadataContainsCanonicalArray(repositoryMetadata));
    }

    @Test
    @DisabledInNativeImage
    void startupMigrationFailsFastBeforeCommandExecutionWhenRepositoryMetadataRewriteFails(@TempDir Path tempDir) throws Exception {
        prepareLegacyStartupMigrationScenario(tempDir, "{\"profiles\":[{\"name\":\"child\",\"extends_from\":\"base\"}");

        CommandResult result = executeMainWithStartup(tempDir, "help");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Could not migrate legacy repository metadata before startup."));
        assertFalse(result.stdout().contains("Usage: ocp"));
    }

    private static String readExpectedVersion() {
        try (var inputStream = OcpCommandTest.class.getResourceAsStream("/META-INF/ocp/version.txt")) {
            assertNotNull(inputStream);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read version resource", e);
        }
    }

    private static Path prepareLegacyStartupMigrationScenario(Path tempDir, String repositoryJsonContent) throws IOException {
        Path configDir = tempDir.resolve("ocp-config");
        Path repositoryPath = tempDir.resolve("repository");
        Files.createDirectories(configDir);
        Files.createDirectories(repositoryPath);
        Path repositoryMetadata = repositoryPath.resolve("repository.json");
        Files.writeString(repositoryMetadata, repositoryJsonContent);

        OcpConfigFile configFile = new OcpConfigFile(
            new OcpConfigOptions(),
            List.of(new RepositoryEntry("demo", null, repositoryPath.toString()))
        );
        Files.writeString(configDir.resolve("config.json"), serializeAsJson(configFile));
        return repositoryMetadata;
    }

    private static CommandResult executeMainWithStartup(Path tempDir, String... args) throws IOException, InterruptedException {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            throw new UnsupportedOperationException("Subprocess startup migration assertions are not supported inside native test images");
        }

        List<String> command = new java.util.ArrayList<>();
        command.add(javaBinaryPath());
        command.add("-Docp.config.dir=" + tempDir.resolve("ocp-config"));
        command.add("-Docp.cache.dir=" + tempDir.resolve("ocp-cache"));
        command.add("-Duser.home=" + tempDir.resolve("home"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(OcpCommand.class.getName());
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new CommandResult(exitCode, stdout, stderr);
    }

    private static String javaBinaryPath() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            return Path.of(javaHome, "bin", "java").toString();
        }

        String javaCommand = ProcessHandle.current()
            .info()
            .command()
            .orElseThrow(() -> new IllegalStateException("Cannot resolve current Java executable path"));
        return Path.of(javaCommand).toString();
    }

    private static String serializeAsJson(Object value) throws IOException {
        try (ApplicationContext context = ApplicationContext.run()) {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            return objectMapper.writeValueAsString(value);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean metadataContainsCanonicalArray(Path repositoryMetadata) throws IOException {
        try (ApplicationContext context = ApplicationContext.run()) {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            Map<String, Object> parsed = objectMapper.readValue(Files.readString(repositoryMetadata), Map.class);
            Object profilesObject = parsed.get("profiles");
            if (!(profilesObject instanceof List<?> profiles) || profiles.isEmpty()) {
                return false;
            }
            Object profileObject = profiles.getFirst();
            if (!(profileObject instanceof Map<?, ?> profileMap)) {
                return false;
            }
            Object extendsFrom = profileMap.get("extends_from");
            return extendsFrom instanceof List<?> extendsFromArray
                && extendsFromArray.size() == 1
                && "base".equals(extendsFromArray.getFirst());
        }
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }

}
