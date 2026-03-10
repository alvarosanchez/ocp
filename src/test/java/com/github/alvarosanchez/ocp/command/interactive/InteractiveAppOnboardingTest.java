package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.alvarosanchez.ocp.command.Cli;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.service.OnboardingService;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveAppOnboardingTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private String previousConfigDir;
    private String previousCacheDir;
    private String previousOpenCodeConfigDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        Cli.init();
        previousConfigDir = System.getProperty("ocp.config.dir");
        previousCacheDir = System.getProperty("ocp.cache.dir");
        previousOpenCodeConfigDir = System.getProperty("ocp.opencode.config.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("cache").toString());
        System.setProperty("ocp.opencode.config.dir", tempDir.resolve("opencode").toString());
    }

    @AfterEach
    void tearDown() {
        applicationContext.close();
        if (previousConfigDir == null) {
            System.clearProperty("ocp.config.dir");
        } else {
            System.setProperty("ocp.config.dir", previousConfigDir);
        }
        if (previousCacheDir == null) {
            System.clearProperty("ocp.cache.dir");
        } else {
            System.setProperty("ocp.cache.dir", previousCacheDir);
        }
        if (previousOpenCodeConfigDir == null) {
            System.clearProperty("ocp.opencode.config.dir");
        } else {
            System.setProperty("ocp.opencode.config.dir", previousOpenCodeConfigDir);
        }
    }

    @Test
    void onStartShowsOnboardingPromptWhenExistingConfigFilesCanBeImported() throws Exception {
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Files.writeString(openCodeDirectory.resolve("opencode.json"), "{\"model\":\"gpt-5\"}");
        Files.writeString(openCodeDirectory.resolve("tui.json"), "{\"theme\":\"matrix\"}");

        InteractiveApp app = new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            applicationContext.getBean(ObjectMapper.class)
        );

        app.onStart();

        Field splashVisibleField = InteractiveApp.class.getDeclaredField("splashVisible");
        splashVisibleField.setAccessible(true);
        assertFalse((boolean) splashVisibleField.get(app));

        Field promptField = InteractiveApp.class.getDeclaredField("prompt");
        promptField.setAccessible(true);
        PromptState prompt = (PromptState) promptField.get(app);
        assertNotNull(prompt);
        assertEquals(PromptAction.ONBOARD_EXISTING_CONFIG_CONFIRM, prompt.action);
        assertEquals("Import existing OpenCode config files into OCP?", prompt.title);
        assertEquals("- opencode.json\n- tui.json", prompt.labels.getFirst());
        assertEquals(java.util.List.of("yes", "no"), prompt.options.getFirst());
        assertEquals("yes", prompt.values.getFirst());
    }

    @Test
    void onboardingProfilePromptStaysOpenWhenImportValidationFails() throws Exception {
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Files.writeString(openCodeDirectory.resolve("opencode.json"), "{\"model\":\"gpt-5\"}");

        InteractiveApp app = new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            applicationContext.getBean(ObjectMapper.class)
        );

        Field promptField = InteractiveApp.class.getDeclaredField("prompt");
        promptField.setAccessible(true);
        PromptState prompt = PromptState.single(
            PromptAction.ONBOARD_EXISTING_CONFIG_PROFILE_NAME,
            "Create onboarding profile",
            "Profile name"
        );
        prompt.contextRepositoryName = "personal-repo";
        prompt.values.set(0, "bad/name");
        promptField.set(app, prompt);

        Method applyPrompt = InteractiveApp.class.getDeclaredMethod("applyPrompt");
        applyPrompt.setAccessible(true);
        applyPrompt.invoke(app);

        PromptState retainedPrompt = (PromptState) promptField.get(app);
        assertNotNull(retainedPrompt);
        assertEquals(PromptAction.ONBOARD_EXISTING_CONFIG_PROFILE_NAME, retainedPrompt.action);

        Field statusField = InteractiveApp.class.getDeclaredField("status");
        statusField.setAccessible(true);
        String status = (String) statusField.get(app);
        assertEquals("Error: Profile name must be a single safe path segment.", status);
    }

    @Test
    void onboardingRepositoryPromptStaysOpenWhenRepositoryNameIsInvalid() throws Exception {
        InteractiveApp app = new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            applicationContext.getBean(ObjectMapper.class)
        );

        Field promptField = InteractiveApp.class.getDeclaredField("prompt");
        promptField.setAccessible(true);
        PromptState prompt = PromptState.single(
            PromptAction.ONBOARD_EXISTING_CONFIG_REPOSITORY_NAME,
            "Create onboarding repository",
            "Repository name"
        );
        prompt.values.set(0, "bad/name");
        promptField.set(app, prompt);

        Method applyPrompt = InteractiveApp.class.getDeclaredMethod("applyPrompt");
        applyPrompt.setAccessible(true);
        applyPrompt.invoke(app);

        PromptState retainedPrompt = (PromptState) promptField.get(app);
        assertNotNull(retainedPrompt);
        assertEquals(PromptAction.ONBOARD_EXISTING_CONFIG_REPOSITORY_NAME, retainedPrompt.action);

        Field statusField = InteractiveApp.class.getDeclaredField("status");
        statusField.setAccessible(true);
        String status = (String) statusField.get(app);
        assertEquals("Error: Repository name must be a single safe path segment.", status);
    }

    @Test
    void onboardingPromptTakesPrecedenceOverDeferredStartupNotice() throws Exception {
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Files.writeString(openCodeDirectory.resolve("opencode.json"), "{\"model\":\"gpt-5\"}");
        Path configDirectory = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDirectory);
        Files.writeString(
            configDirectory.resolve("config.json"),
            applicationContext.getBean(ObjectMapper.class).writeValueAsString(
                new OcpConfigFile(new OcpConfigOptions(null, 123L, "1.2.3"), java.util.List.of())
            )
        );
        Cli.setStartupNotice("Update available");

        InteractiveApp app = new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            applicationContext.getBean(ObjectMapper.class)
        );

        app.onStart();

        Field promptField = InteractiveApp.class.getDeclaredField("prompt");
        promptField.setAccessible(true);
        PromptState prompt = (PromptState) promptField.get(app);
        assertNotNull(prompt);
        assertEquals(PromptAction.ONBOARD_EXISTING_CONFIG_CONFIRM, prompt.action);

        Method activeOverlay = InteractiveApp.class.getDeclaredMethod("activeOverlay");
        activeOverlay.setAccessible(true);
        Object overlay = activeOverlay.invoke(app);
        assertEquals("PROMPT", overlay.toString());
    }

    @Test
    void initialDataLoadShowsStatusWhenOnboardingDetectionFails() throws Exception {
        Path configDirectory = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve("config.json"), "not-json");

        InteractiveApp app = new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            applicationContext.getBean(ObjectMapper.class)
        );

        Method loadInitialDataInBackground = InteractiveApp.class.getDeclaredMethod("loadInitialDataInBackground");
        loadInitialDataInBackground.setAccessible(true);
        loadInitialDataInBackground.invoke(app);

        Field statusField = InteractiveApp.class.getDeclaredField("status");
        statusField.setAccessible(true);
        String status = (String) statusField.get(app);
        assertEquals("Error loading onboarding: Failed to read repository registry", status);

        Field promptField = InteractiveApp.class.getDeclaredField("prompt");
        promptField.setAccessible(true);
        assertEquals(null, promptField.get(app));
    }

    @Test
    void onStartShowsStatusWhenOnboardingDetectionFailsWithoutToolkitRunner() throws Exception {
        Path configDirectory = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve("config.json"), "not-json");

        InteractiveApp app = new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            applicationContext.getBean(ObjectMapper.class)
        );

        app.onStart();

        Field statusField = InteractiveApp.class.getDeclaredField("status");
        statusField.setAccessible(true);
        String status = (String) statusField.get(app);
        assertEquals("Error loading onboarding: Failed to read repository registry", status);

        Field promptField = InteractiveApp.class.getDeclaredField("prompt");
        promptField.setAccessible(true);
        assertEquals(null, promptField.get(app));
    }

    @Test
    void onboardingConfirmationTransitionsToRepositoryThenProfilePrompt() throws Exception {
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Files.writeString(openCodeDirectory.resolve("opencode.json"), "{\"model\":\"gpt-5\"}");

        InteractiveApp app = new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            applicationContext.getBean(ObjectMapper.class)
        );
        app.onStart();

        Field promptField = InteractiveApp.class.getDeclaredField("prompt");
        promptField.setAccessible(true);
        PromptState confirmPrompt = (PromptState) promptField.get(app);
        assertEquals("yes", confirmPrompt.values.getFirst());

        Method applyPrompt = InteractiveApp.class.getDeclaredMethod("applyPrompt");
        applyPrompt.setAccessible(true);
        applyPrompt.invoke(app);

        PromptState repositoryPrompt = (PromptState) promptField.get(app);
        assertEquals(PromptAction.ONBOARD_EXISTING_CONFIG_REPOSITORY_NAME, repositoryPrompt.action);
        repositoryPrompt.values.set(0, "personal-repo");

        applyPrompt.invoke(app);

        PromptState profilePrompt = (PromptState) promptField.get(app);
        assertEquals(PromptAction.ONBOARD_EXISTING_CONFIG_PROFILE_NAME, profilePrompt.action);
        assertEquals("personal-repo", profilePrompt.contextRepositoryName);
    }
}
