package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.command.Cli;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.service.OnboardingService;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveAppCreateProfileTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private ObjectMapper objectMapper;
    private String previousConfigDir;
    private String previousCacheDir;
    private String previousOpenCodeConfigDir;
    private String previousWorkingDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        objectMapper = applicationContext.getBean(ObjectMapper.class);
        Cli.init();
        previousConfigDir = System.getProperty("ocp.config.dir");
        previousCacheDir = System.getProperty("ocp.cache.dir");
        previousOpenCodeConfigDir = System.getProperty("ocp.opencode.config.dir");
        previousWorkingDir = System.getProperty("ocp.working.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("cache").toString());
        System.setProperty("ocp.opencode.config.dir", tempDir.resolve("opencode").toString());
        System.setProperty("ocp.working.dir", tempDir.resolve("workspace").toString());
    }

    @AfterEach
    void tearDown() {
        applicationContext.close();
        restoreProperty("ocp.config.dir", previousConfigDir);
        restoreProperty("ocp.cache.dir", previousCacheDir);
        restoreProperty("ocp.opencode.config.dir", previousOpenCodeConfigDir);
        restoreProperty("ocp.working.dir", previousWorkingDir);
    }

    @Test
    void applyPromptCreatesProfileInSelectedRepositoryWithoutParent() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Files.createDirectories(repositoryPath.resolve("default"));
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.repository("repo-a", repositoryPath));

        PromptState prompt = PromptState.multiWithOptions(
            PromptAction.CREATE_PROFILE,
            "Create profile",
            List.of("Profile name", "Extends from profile (optional)"),
            List.of(List.of(), List.of("", "default"))
        );
        prompt.contextRepositoryName = "repo-a";
        prompt.values.set(0, "child");
        prompt.values.set(1, "");
        setPrompt(app, prompt);

        invokeApplyPrompt(app);

        assertTrue(Files.isDirectory(repositoryPath.resolve("child")));
        RepositoryConfigFile repositoryConfig = readRepositoryConfig(repositoryPath);
        assertEquals(List.of("default", "child"), repositoryConfig.profiles().stream().map(RepositoryConfigFile.ProfileEntry::name).toList());

        invokeReloadState(app);
        assertTrue(
            readRepositoryConfig(repositoryPath).profiles().stream().anyMatch(profile -> "child".equals(profile.name()))
        );
        assertEquals("Created profile child in repository repo-a.", readStatus(app));
    }

    @Test
    void applyPromptCreatesProfileWithExtendsFromSelectedOption() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Files.createDirectories(repositoryPath.resolve("default"));
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.repository("repo-a", repositoryPath));

        PromptState prompt = PromptState.multiWithOptions(
            PromptAction.CREATE_PROFILE,
            "Create profile",
            List.of("Profile name", "Extends from profile (optional)"),
            List.of(List.of(), List.of("", "default"))
        );
        prompt.contextRepositoryName = "repo-a";
        prompt.values.set(0, "child");
        prompt.values.set(1, "default");
        setPrompt(app, prompt);

        invokeApplyPrompt(app);

        RepositoryConfigFile repositoryConfig = readRepositoryConfig(repositoryPath);
        RepositoryConfigFile.ProfileEntry created = repositoryConfig.profiles().stream()
            .filter(profile -> "child".equals(profile.name()))
            .findFirst()
            .orElseThrow();
        assertEquals("default", created.extendsFrom());
        assertEquals("Created profile child in repository repo-a extending from default.", readStatus(app));
    }

    @Test
    void createProfilePromptIncludesOwningRepositoryAndResolvableParents() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path filePath = profilePath.resolve("opencode.json");
        Path otherRepositoryPath = tempDir.resolve("repo-b");
        Files.createDirectories(profilePath);
        Files.createDirectories(otherRepositoryPath.resolve("base"));
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(otherRepositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"base\"}]}");
        Files.writeString(filePath, "{}\n");
        writeConfig(
            new RepositoryEntry("repo-a", null, repositoryPath.toString()),
            new RepositoryEntry("repo-b", null, otherRepositoryPath.toString())
        );

        InteractiveApp app = createApp();
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.file("repo-a", "default", filePath));
        app.openCreateProfilePromptForSelectedNode();

        PromptState storedPrompt = readPrompt(app);

        assertNotNull(storedPrompt);
        assertEquals(PromptAction.CREATE_PROFILE, storedPrompt.action);
        assertEquals("repo-a", storedPrompt.contextRepositoryName);
        assertEquals(List.of("", "base", "default"), storedPrompt.options.get(1));
    }

    private InteractiveApp createApp() {
        return new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            objectMapper
        );
    }

    private void writeConfig(RepositoryEntry... repositoryEntries) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(
            configDir.resolve("config.json"),
            objectMapper.writeValueAsString(new OcpConfigFile(new OcpConfigOptions(), List.of(repositoryEntries)))
        );
    }

    private RepositoryConfigFile readRepositoryConfig(Path repositoryPath) throws IOException {
        return objectMapper.readValue(Files.readString(repositoryPath.resolve("repository.json")), RepositoryConfigFile.class);
    }

    private static void invokeReloadState(InteractiveApp app) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("reloadState");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static void invokeApplyPrompt(InteractiveApp app) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("applyPrompt");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static void setPrompt(InteractiveApp app, PromptState prompt) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("prompt");
        field.setAccessible(true);
        field.set(app, prompt);
    }

    private static PromptState readPrompt(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("prompt");
        field.setAccessible(true);
        return (PromptState) field.get(app);
    }

    private static void setSelectedNode(InteractiveApp app, NodeRef selectedNode) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("selectedNode");
        field.setAccessible(true);
        field.set(app, selectedNode);
    }

    private static NodeRef readSelectedNode(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("selectedNode");
        field.setAccessible(true);
        return (NodeRef) field.get(app);
    }

    private static String readStatus(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("status");
        field.setAccessible(true);
        return (String) field.get(app);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
