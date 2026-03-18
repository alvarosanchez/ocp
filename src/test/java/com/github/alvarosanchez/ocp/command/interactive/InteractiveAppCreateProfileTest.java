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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("repo-a", repositoryPath));

        PromptState prompt = createProfilePrompt("repo-a", List.of("default"));
        prompt.values.set(0, "child");
        prompt.values.set(1, "");
        app.testSetPrompt(prompt);

        app.testApplyPrompt();

        assertTrue(Files.isDirectory(repositoryPath.resolve("child")));
        RepositoryConfigFile repositoryConfig = readRepositoryConfig(repositoryPath);
        assertEquals(List.of("default", "child"), repositoryConfig.profiles().stream().map(RepositoryConfigFile.ProfileEntry::name).toList());

        app.testReloadState();
        assertTrue(
            readRepositoryConfig(repositoryPath).profiles().stream().anyMatch(profile -> "child".equals(profile.name()))
        );
        assertEquals("Created profile child in repository repo-a.", app.testStatus());
    }

    @Test
    void applyPromptCreatesProfileWithExtendsFromSelectedOption() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Files.createDirectories(repositoryPath.resolve("default"));
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("repo-a", repositoryPath));

        PromptState prompt = createProfilePrompt("repo-a", List.of("default"));
        prompt.values.set(0, "child");
        prompt.values.set(1, "default");
        app.testSetPrompt(prompt);

        app.testApplyPrompt();

        RepositoryConfigFile repositoryConfig = readRepositoryConfig(repositoryPath);
        RepositoryConfigFile.ProfileEntry created = repositoryConfig.profiles().stream()
            .filter(profile -> "child".equals(profile.name()))
            .findFirst()
            .orElseThrow();
        assertEquals(List.of("default"), created.extendsFromProfiles());
        assertEquals("Created profile child in repository repo-a extending from default.", app.testStatus());
    }

    @Test
    void applyPromptCreatesProfileWithOrderedParents() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Files.createDirectories(repositoryPath.resolve("base"));
        Files.createDirectories(repositoryPath.resolve("shared"));
        Files.createDirectories(repositoryPath.resolve("default"));
        Files.writeString(
            repositoryPath.resolve("repository.json"),
            "{\"profiles\":[{\"name\":\"base\"},{\"name\":\"shared\"},{\"name\":\"default\"}]}"
        );
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("repo-a", repositoryPath));

        app.testSetPrompt(createProfilePrompt("repo-a", List.of("base", "default", "shared")));
        PromptState prompt = app.testPrompt();
        prompt.values.set(0, "child");
        prompt.currentField = 1;
        prompt.values.set(1, "shared");
        assertTrue(app.testAdvanceCreateProfilePromptIfNeeded());
        prompt.values.set(2, "base");
        assertTrue(app.testAdvanceCreateProfilePromptIfNeeded());
        prompt.values.set(3, "");

        app.testApplyPrompt();

        RepositoryConfigFile repositoryConfig = readRepositoryConfig(repositoryPath);
        RepositoryConfigFile.ProfileEntry created = repositoryConfig.profiles().stream()
            .filter(profile -> "child".equals(profile.name()))
            .findFirst()
            .orElseThrow();
        assertEquals(List.of("shared", "base"), created.extendsFromProfiles());
        assertEquals(
            "Created profile child in repository repo-a extending from shared, base.",
            app.testStatus()
        );
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
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.file("repo-a", "default", filePath));
        invokeOpenCreateProfilePromptForSelectedNode(app);

        PromptState storedPrompt = app.testPrompt();

        assertNotNull(storedPrompt);
        assertEquals(PromptAction.CREATE_PROFILE, storedPrompt.action);
        assertEquals("repo-a", storedPrompt.contextRepositoryName);
        assertEquals(List.of("", "base", "default"), storedPrompt.options.get(1));
    }

    @Test
    void createProfilePromptRepeatsParentSelectionUntilBlank() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Files.createDirectories(repositoryPath.resolve("base"));
        Files.createDirectories(repositoryPath.resolve("default"));
        Files.createDirectories(repositoryPath.resolve("shared"));
        Files.writeString(
            repositoryPath.resolve("repository.json"),
            "{\"profiles\":[{\"name\":\"base\"},{\"name\":\"default\"},{\"name\":\"shared\"}]}"
        );
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("repo-a", repositoryPath));
        invokeOpenCreateProfilePromptForSelectedNode(app);

        PromptState prompt = app.testPrompt();
        prompt.values.set(0, "child");
        assertTrue(prompt.nextField());
        prompt.values.set(1, "shared");

        assertTrue(app.testAdvanceCreateProfilePromptIfNeeded());
        assertEquals(2, prompt.currentField);
        assertEquals(3, prompt.labels.size());
        assertEquals(List.of("shared"), prompt.values.subList(1, 2));

        prompt.values.set(2, "");
        assertTrue(!app.testAdvanceCreateProfilePromptIfNeeded());
    }

    @Test
    void createProfilePromptExcludesAlreadySelectedParents() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Files.createDirectories(repositoryPath.resolve("base"));
        Files.createDirectories(repositoryPath.resolve("default"));
        Files.createDirectories(repositoryPath.resolve("shared"));
        Files.writeString(
            repositoryPath.resolve("repository.json"),
            "{\"profiles\":[{\"name\":\"base\"},{\"name\":\"default\"},{\"name\":\"shared\"}]}"
        );
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("repo-a", repositoryPath));
        invokeOpenCreateProfilePromptForSelectedNode(app);

        PromptState prompt = app.testPrompt();
        prompt.values.set(0, "child");
        assertTrue(prompt.nextField());
        prompt.values.set(1, "base");

        assertTrue(app.testAdvanceCreateProfilePromptIfNeeded());
        assertEquals(List.of("", "default", "shared"), prompt.options.get(2));

        prompt.values.set(2, "shared");
        assertTrue(app.testAdvanceCreateProfilePromptIfNeeded());
        assertEquals(List.of("", "default"), prompt.options.get(3));
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

    private static PromptState createProfilePrompt(String repositoryName, List<String> resolvableProfileNames) {
        List<String> parentOptions = new ArrayList<>();
        parentOptions.add("");
        parentOptions.addAll(resolvableProfileNames);
        PromptState prompt = PromptState.multiWithOptions(
            PromptAction.CREATE_PROFILE,
            "Create profile",
            new ArrayList<>(List.of("Profile name", "Extends from profile (optional)")),
            new ArrayList<>(List.of(List.of(), parentOptions))
        );
        prompt.contextRepositoryName = repositoryName;
        prompt.baseParentOptions = List.copyOf(resolvableProfileNames);
        return prompt;
    }

    private static void invokeOpenCreateProfilePromptForSelectedNode(InteractiveApp app) throws Exception {
        app.testOpenCreateProfilePromptForSelectedNode();
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
