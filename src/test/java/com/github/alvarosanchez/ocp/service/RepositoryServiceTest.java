package com.github.alvarosanchez.ocp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.git.GitProcessExecutor;
import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import com.github.alvarosanchez.ocp.model.OcpConfigFile;
import com.github.alvarosanchez.ocp.model.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.model.OcpConfigFile.RepositoryEntry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryServiceTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private RepositoryService repositoryService;
    private ObjectMapper objectMapper;
    private String previousConfigDir;
    private String previousCacheDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        objectMapper = applicationContext.getBean(ObjectMapper.class);
        repositoryService = new RepositoryService(objectMapper, new GitRepositoryClient(new GitProcessExecutor()));
        previousConfigDir = System.getProperty("ocp.config.dir");
        previousCacheDir = System.getProperty("ocp.cache.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("cache").toString());
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
    }

    @Test
    void loadReturnsEmptyWhenConfigFileDoesNotExist() {
        List<RepositoryEntry> repositories = repositoryService.load();

        assertTrue(repositories.isEmpty());
    }

    @Test
    void loadNormalizesEntriesUsingRepositoryNameAndCacheDirectory() throws IOException {
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(
                    new RepositoryEntry("", " git@github.com:acme/alpha.git ", null),
                    new RepositoryEntry("custom", "https://github.com/acme/beta.git", null),
                    new RepositoryEntry("ignored", "  ", null)
                )
            )
        );

        List<RepositoryEntry> repositories = repositoryService.load();

        assertEquals(2, repositories.size());
        assertEquals("alpha", repositories.get(0).name());
        assertEquals("git@github.com:acme/alpha.git", repositories.get(0).uri());
        assertEquals(
            Path.of(System.getProperty("ocp.cache.dir"), "repositories", "alpha").toString(),
            repositories.get(0).localPath()
        );
        assertEquals("custom", repositories.get(1).name());
        assertEquals("https://github.com/acme/beta.git", repositories.get(1).uri());
        assertEquals(
            Path.of(System.getProperty("ocp.cache.dir"), "repositories", "custom").toString(),
            repositories.get(1).localPath()
        );
    }

    @Test
    void loadWrapsReadErrorsAsUncheckedIOException() throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), "not-json");

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, repositoryService::load);

        assertTrue(thrown.getMessage().contains("Failed to read repository registry"));
    }

    private void writeConfig(OcpConfigFile configFile) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), objectMapper.writeValueAsString(configFile));
    }
}
