package com.github.alvarosanchez.ocp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.git.GitProcessExecutor;
import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnboardingServiceTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private ObjectMapper objectMapper;
    private RepositoryService repositoryService;
    private ProfileService profileService;
    private OnboardingService onboardingService;
    private String previousConfigDir;
    private String previousCacheDir;
    private String previousOpenCodeConfigDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        objectMapper = applicationContext.getBean(ObjectMapper.class);
        GitRepositoryClient gitRepositoryClient = new GitRepositoryClient(new GitProcessExecutor());
        repositoryService = new RepositoryService(objectMapper, gitRepositoryClient);
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);
        onboardingService = new OnboardingService(repositoryService, profileService);
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
    void detectReturnsCandidateWhenConfigFileIsMissingAndRegularJsonExists() throws IOException {
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Path importedFile = openCodeDirectory.resolve("opencode.json");
        Files.writeString(importedFile, "{\"model\":\"gpt-5\"}");
        Path symlinkTarget = tempDir.resolve("linked.json");
        Files.writeString(symlinkTarget, "{}");
        Files.createSymbolicLink(openCodeDirectory.resolve("linked.json"), symlinkTarget);

        OnboardingService.OnboardingCandidate candidate = onboardingService.detect().orElseThrow();

        assertEquals(openCodeDirectory, candidate.openCodeDirectory());
        assertEquals(List.of(importedFile), candidate.configFiles());
    }

    @Test
    void detectIgnoresNonWhitelistedFiles() throws IOException {
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Path opencodeFile = openCodeDirectory.resolve("opencode.json");
        Path tuiFile = openCodeDirectory.resolve("tui.jsonc");
        Files.writeString(opencodeFile, "{\"model\":\"gpt-5\"}");
        Files.writeString(tuiFile, "{\"theme\":\"matrix\"}");
        Files.writeString(openCodeDirectory.resolve("package.json"), "{\"dependencies\":{}}");
        Files.writeString(openCodeDirectory.resolve("tsconfig.json"), "{}");

        OnboardingService.OnboardingCandidate candidate = onboardingService.detect().orElseThrow();

        assertEquals(List.of(opencodeFile, tuiFile), candidate.configFiles());
    }

    @Test
    void detectReturnsEmptyWhenOcpConfigAlreadyHasConfiguredRepository() throws IOException {
        Path configDirectory = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDirectory);
        Files.writeString(
            configDirectory.resolve("config.json"),
            objectMapper.writeValueAsString(
                new OcpConfigFile(
                    new OcpConfigOptions(),
                    List.of(new OcpConfigFile.RepositoryEntry("personal", null, tempDir.resolve("repo").toString()))
                )
            )
        );
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Files.writeString(openCodeDirectory.resolve("opencode.json"), "{}");

        assertTrue(onboardingService.detect().isEmpty());
    }

    @Test
    void detectStillReturnsCandidateWhenConfigFileContainsOnlyVersionCheckMetadata() throws IOException {
        Path configDirectory = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDirectory);
        Files.writeString(
            configDirectory.resolve("config.json"),
            objectMapper.writeValueAsString(
                new OcpConfigFile(new OcpConfigOptions(null, 123L, "1.2.3"), List.of())
            )
        );
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Path importedFile = openCodeDirectory.resolve("opencode.jsonc");
        Files.writeString(importedFile, "{\"model\":\"gpt-5\"}");

        OnboardingService.OnboardingCandidate candidate = onboardingService.detect().orElseThrow();

        assertEquals(List.of(importedFile), candidate.configFiles());
    }

    @Test
    void onboardCreatesRepositoryCopiesFilesAndActivatesImportedProfile() throws IOException {
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Files.writeString(openCodeDirectory.resolve("opencode.json"), "{\"model\":\"gpt-5\"}");
        Files.writeString(openCodeDirectory.resolve("oh-my-opencode.jsonc"), "{\"plugin\":[\"demo\"]}");

        OnboardingService.OnboardingResult result = onboardingService.onboard("personal-repo", "personal");

        assertEquals("personal-repo", result.repositoryName());
        assertEquals("personal", result.profileName());
        assertTrue(Files.notExists(result.repositoryPath().resolve(".git")));
        assertEquals(
            "{\"model\":\"gpt-5\"}",
            Files.readString(result.repositoryPath().resolve("personal").resolve("opencode.json"))
        );
        assertEquals(
            "{\"plugin\":[\"demo\"]}",
            Files.readString(result.repositoryPath().resolve("personal").resolve("oh-my-opencode.jsonc"))
        );

        List<com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry> repositories = repositoryService.load();
        assertEquals(1, repositories.size());
        assertEquals("personal-repo", repositories.getFirst().name());
        assertEquals(null, repositories.getFirst().uri());
        assertEquals(result.repositoryPath().toString(), repositories.getFirst().localPath());

        OcpConfigFile configFile = repositoryService.loadConfigFile();
        assertEquals("personal", configFile.config().activeProfile());

        Path importedOpenCodeFile = openCodeDirectory.resolve("opencode.json");
        Path importedJsoncFile = openCodeDirectory.resolve("oh-my-opencode.jsonc");
        assertTrue(Files.isSymbolicLink(importedOpenCodeFile));
        assertTrue(Files.isSymbolicLink(importedJsoncFile));
        assertEquals(
            result.repositoryPath().resolve("personal").resolve("opencode.json").toAbsolutePath(),
            Files.readSymbolicLink(importedOpenCodeFile)
        );
        assertEquals(
            result.repositoryPath().resolve("personal").resolve("oh-my-opencode.jsonc").toAbsolutePath(),
            Files.readSymbolicLink(importedJsoncFile)
        );

        assertTrue(result.switchResult().hasBackups());
        assertEquals(2, result.switchResult().backedUpFiles());
        Path backupDirectory = result.switchResult().backupDirectory();
        assertEquals("{\"model\":\"gpt-5\"}", Files.readString(backupDirectory.resolve("opencode.json")));
        assertEquals("{\"plugin\":[\"demo\"]}", Files.readString(backupDirectory.resolve("oh-my-opencode.jsonc")));
    }

    @Test
    void onboardImportsOnlyWhitelistedFiles() throws IOException {
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Files.writeString(openCodeDirectory.resolve("opencode.json"), "{\"model\":\"gpt-5\"}");
        Files.writeString(openCodeDirectory.resolve("tui.json"), "{\"theme\":\"matrix\"}");
        Files.writeString(openCodeDirectory.resolve("package.json"), "{\"dependencies\":{}}");

        OnboardingService.OnboardingResult result = onboardingService.onboard("personal-repo", "personal");

        Path importedProfileDirectory = result.repositoryPath().resolve("personal");
        assertTrue(Files.exists(importedProfileDirectory.resolve("opencode.json")));
        assertTrue(Files.exists(importedProfileDirectory.resolve("tui.json")));
        assertTrue(Files.notExists(importedProfileDirectory.resolve("package.json")));
        assertEquals(List.of(openCodeDirectory.resolve("opencode.json"), openCodeDirectory.resolve("tui.json")), result.importedFiles());
    }

    @Test
    void onboardExplainsWhichFilesAreImportableWhenOnlyNonWhitelistedFilesExist() throws IOException {
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Files.writeString(openCodeDirectory.resolve("package.json"), "{\"dependencies\":{}}");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> onboardingService.onboard("personal-repo", "personal")
        );

        assertEquals(
            "No importable OpenCode config files were found. Supported files: oh-my-opencode.json, oh-my-opencode.jsonc, opencode.json, opencode.jsonc, tui.json, tui.jsonc.",
            exception.getMessage()
        );
    }

    @Test
    void onboardRejectsUnsafeRepositoryName() throws IOException {
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Files.writeString(openCodeDirectory.resolve("opencode.json"), "{}");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> onboardingService.onboard("bad/name", "personal")
        );

        assertEquals("Repository name must be a single safe path segment.", exception.getMessage());
    }

    @Test
    void onboardRollbackPreservesExistingVersionMetadataConfigFile() throws IOException {
        Path configDirectory = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDirectory);
        OcpConfigFile existingConfig = new OcpConfigFile(new OcpConfigOptions(null, 123L, "1.2.3"), List.of());
        Files.writeString(configDirectory.resolve("config.json"), objectMapper.writeValueAsString(existingConfig));

        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Files.writeString(openCodeDirectory.resolve("opencode.json"), "{\"model\":\"gpt-5\"}");
        Files.writeString(openCodeDirectory.resolve("opencode.jsonc"), "{\"model\":\"gpt-5\"}");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> onboardingService.onboard("personal-repo", "personal")
        );

        assertTrue(exception.getMessage().contains("conflicting config file variants"));
        assertTrue(Files.exists(configDirectory.resolve("config.json")));
        assertEquals(existingConfig, repositoryService.loadConfigFile());
    }

    @Test
    void onboardRollbackRestoresOpenCodeFilesWhenActiveProfileSaveFails() throws IOException {
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Path openCodeFile = openCodeDirectory.resolve("opencode.json");
        Files.writeString(openCodeFile, "{\"model\":\"gpt-5\"}");

        RepositoryService failingRepositoryService = new RepositoryService(
            objectMapperFailingOnActiveProfileWrite(),
            new GitRepositoryClient(new GitProcessExecutor())
        );
        ProfileService failingProfileService = new ProfileService(objectMapper, failingRepositoryService, new GitRepositoryClient(new GitProcessExecutor()));
        OnboardingService failingOnboardingService = new OnboardingService(failingRepositoryService, failingProfileService);

        UncheckedIOException exception = assertThrows(
            UncheckedIOException.class,
            () -> failingOnboardingService.onboard("personal-repo", "personal")
        );

        assertTrue(exception.getMessage().contains("Failed to write repository registry"));
        assertTrue(Files.exists(openCodeFile));
        assertTrue(Files.isRegularFile(openCodeFile));
        assertTrue(!Files.isSymbolicLink(openCodeFile));
        assertEquals("{\"model\":\"gpt-5\"}", Files.readString(openCodeFile));
        assertTrue(Files.notExists(tempDir.resolve("cache").resolve("repositories").resolve("personal-repo")));
        assertTrue(Files.notExists(Path.of(System.getProperty("ocp.config.dir")).resolve("config.json")));
    }

    private ObjectMapper objectMapperFailingOnActiveProfileWrite() {
        InvocationHandler handler = (proxy, method, args) -> {
            if (
                method.getName().equals("writeValueAsString")
                    && args != null
                    && args.length == 1
                    && args[0] instanceof OcpConfigFile configFile
                    && configFile.config().activeProfile() != null
            ) {
                throw new IOException("Injected active-profile write failure");
            }
            try {
                return method.invoke(objectMapper, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (ObjectMapper) Proxy.newProxyInstance(
            ObjectMapper.class.getClassLoader(),
            new Class<?>[] {ObjectMapper.class},
            handler
        );
    }

}
