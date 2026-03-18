package com.github.alvarosanchez.ocp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.git.GitProcessExecutor;
import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryMetadataMigrationServiceTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private ObjectMapper objectMapper;
    private String previousConfigDir;
    private String previousCacheDir;
    private RepositoryMetadataMigrationService migrationService;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        objectMapper = applicationContext.getBean(ObjectMapper.class);
        previousConfigDir = System.getProperty("ocp.config.dir");
        previousCacheDir = System.getProperty("ocp.cache.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("cache").toString());
        RepositoryService repositoryService = RepositoryService.forTest(objectMapper, new GitRepositoryClient(new GitProcessExecutor()));
        migrationService = new RepositoryMetadataMigrationService(repositoryService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        applicationContext.close();
        restoreProperty("ocp.config.dir", previousConfigDir);
        restoreProperty("ocp.cache.dir", previousCacheDir);
    }

    @Test
    void migrateLegacyExtendsFromScalarsRewritesLegacyMetadata() throws Exception {
        Path repositoryPath = tempDir.resolve("repo");
        Files.createDirectories(repositoryPath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"child\",\"extends_from\":\"base\"}]}");
        writeConfig(repositoryPath);

        migrationService.migrateLegacyExtendsFromScalars();

        String migrated = Files.readString(repositoryPath.resolve("repository.json"));
        assertTrue(migrated.contains("base"));
        assertFalse(RepositoryConfigFile.normalizeLegacyExtendsFromScalars(migrated, objectMapper).migrated());
    }

    @Test
    void migrateLegacyExtendsFromScalarsLeavesCanonicalMetadataUntouched() throws Exception {
        Path repositoryPath = tempDir.resolve("repo");
        Files.createDirectories(repositoryPath);
        Path metadataFile = repositoryPath.resolve("repository.json");
        String canonical = "{\"profiles\":[{\"name\":\"child\",\"extends_from\":[\"base\"]}]}";
        Files.writeString(metadataFile, canonical);
        writeConfig(repositoryPath);

        migrationService.migrateLegacyExtendsFromScalars();

        assertEquals(canonical, Files.readString(metadataFile));
    }

    @Test
    void migrateLegacyExtendsFromScalarsSkipsMissingMetadataFiles() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-without-metadata");
        Files.createDirectories(repositoryPath);
        writeConfig(repositoryPath);

        migrationService.migrateLegacyExtendsFromScalars();

        assertTrue(Files.notExists(repositoryPath.resolve("repository.json")));
    }

    @Test
    void migrateLegacyExtendsFromScalarsSkipsNonRegularMetadataPaths() throws Exception {
        Path repositoryPath = tempDir.resolve("repo");
        Files.createDirectories(repositoryPath);
        Path metadataDirectory = repositoryPath.resolve("repository.json");
        Files.createDirectories(metadataDirectory);
        writeConfig(repositoryPath);

        migrationService.migrateLegacyExtendsFromScalars();

        assertTrue(Files.isDirectory(metadataDirectory));
    }

    private void writeConfig(Path repositoryPath) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        OcpConfigFile configFile = new OcpConfigFile(
            new OcpConfigOptions(),
            List.of(new RepositoryEntry("demo", null, repositoryPath.toString()))
        );
        Files.writeString(configDir.resolve("config.json"), objectMapper.writeValueAsString(configFile));
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
