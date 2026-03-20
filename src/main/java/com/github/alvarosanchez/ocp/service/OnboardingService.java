package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Singleton
public final class OnboardingService {

    private static final List<String> IMPORTABLE_CONFIG_FILE_NAMES = List.of(
        "oh-my-opencode.json",
        "oh-my-opencode.jsonc",
        "opencode.json",
        "opencode.jsonc",
        "tui.json",
        "tui.jsonc"
    );
    private static final Set<String> IMPORTABLE_CONFIG_FILE_NAME_SET = Set.copyOf(IMPORTABLE_CONFIG_FILE_NAMES);

    private final RepositoryService repositoryService;
    private final ProfileService profileService;

    OnboardingService(RepositoryService repositoryService, ProfileService profileService) {
        this.repositoryService = repositoryService;
        this.profileService = profileService;
    }

    public Optional<OnboardingCandidate> detect() {
        if (hasExistingOcpSetup()) {
            return Optional.empty();
        }

        List<Path> configFiles = existingOpenCodeConfigFiles();
        if (configFiles.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new OnboardingCandidate(openCodeDirectory(), configFiles));
    }

    public OnboardingResult onboard(String repositoryName, String profileName) {
        if (hasExistingOcpSetup()) {
            throw new IllegalStateException(
                "Onboarding is only available before an OCP setup (repositories and active profile) has been configured."
            );
        }

        boolean configFileExistedBeforeOnboarding = Files.exists(configFile());
        String normalizedRepositoryName = PathSegmentValidator.requireSinglePathSegment(repositoryName, "Repository name");
        String normalizedProfileName = PathSegmentValidator.requireSinglePathSegment(profileName, "Profile name");
        List<Path> configFiles = existingOpenCodeConfigFiles();
        if (configFiles.isEmpty()) {
            throw new IllegalStateException(
                "No importable OpenCode config files were found. Supported files: "
                    + String.join(", ", IMPORTABLE_CONFIG_FILE_NAMES)
                    + "."
            );
        }

        RepositoryEntry repositoryEntry = null;
        try {
            repositoryEntry = repositoryService.createAndAdd(
                normalizedRepositoryName,
                normalizedProfileName,
                repositoriesDirectory().toString()
            );

            Path profileDirectory = Path.of(repositoryEntry.localPath()).resolve(normalizedProfileName);
            copyConfigFiles(configFiles, profileDirectory);

            ProfileService.ProfileSwitchResult switchResult = profileService.useProfileWithDetails(normalizedProfileName);
            return new OnboardingResult(
                repositoryEntry.name(),
                normalizedProfileName,
                Path.of(repositoryEntry.localPath()),
                configFiles,
                switchResult
            );
        } catch (IOException e) {
            rollbackCreatedRepository(repositoryEntry, configFileExistedBeforeOnboarding, e);
            throw new UncheckedIOException("Failed to import existing OpenCode config files.", e);
        } catch (RuntimeException e) {
            rollbackCreatedRepository(repositoryEntry, configFileExistedBeforeOnboarding, e);
            throw e;
        }
    }

    private void copyConfigFiles(List<Path> sourceFiles, Path profileDirectory) throws IOException {
        Files.createDirectories(profileDirectory);
        for (Path sourceFile : sourceFiles) {
            Files.copy(
                sourceFile,
                profileDirectory.resolve(sourceFile.getFileName().toString()),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
            );
        }
    }

    private void rollbackCreatedRepository(RepositoryEntry repositoryEntry, boolean configFileExistedBeforeOnboarding, Exception failure) {
        if (repositoryEntry == null) {
            return;
        }

        try {
            repositoryService.delete(repositoryEntry.name(), false, true);
            deleteConfigFileWhenNoRepositoriesRemain(configFileExistedBeforeOnboarding);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void deleteConfigFileWhenNoRepositoriesRemain(boolean configFileExistedBeforeOnboarding) {
        if (configFileExistedBeforeOnboarding) {
            return;
        }
        if (!repositoryService.load().isEmpty()) {
            return;
        }
        try {
            Files.deleteIfExists(configFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to remove onboarding config file " + configFile(), e);
        }
    }

    private List<Path> existingOpenCodeConfigFiles() {
        Path openCodeDirectory = openCodeDirectory();
        if (!Files.isDirectory(openCodeDirectory)) {
            return List.of();
        }

        try (var files = Files.list(openCodeDirectory)) {
            return files
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(this::isJsonConfigFile)
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect OpenCode config directory " + openCodeDirectory, e);
        }
    }

    private boolean hasExistingOcpSetup() {
        if (!Files.exists(configFile())) {
            return false;
        }
        var configFile = repositoryService.loadConfigFile();
        if (!configFile.repositories().isEmpty()) {
            return true;
        }
        OcpConfigOptions options = configFile.config();
        return options.activeProfile() != null;
    }

    private boolean isJsonConfigFile(Path path) {
        String fileName = path.getFileName().toString();
        return IMPORTABLE_CONFIG_FILE_NAME_SET.contains(fileName);
    }

    private Path configFile() {
        return configDirectory().resolve("config.json");
    }

    private Path repositoriesDirectory() {
        return repositoryStorageDirectory().resolve("repositories");
    }

    private Path repositoryStorageDirectory() {
        String configuredPath = OcpPathSettings.configuredPath(OcpPathSettings.CACHE_DIR_PROPERTY, OcpPathSettings.CACHE_DIR_ENV);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return configDirectory();
    }

    private Path configDirectory() {
        String configuredPath = OcpPathSettings.configuredPath(OcpPathSettings.CONFIG_DIR_PROPERTY, OcpPathSettings.CONFIG_DIR_ENV);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.home"), ".config", "ocp");
    }

    private Path openCodeDirectory() {
        String configuredPath = OcpPathSettings.configuredPath(OcpPathSettings.OPENCODE_CONFIG_DIR_PROPERTY, OcpPathSettings.OPENCODE_CONFIG_DIR_ENV);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.home"), ".config", "opencode");
    }

    public record OnboardingCandidate(Path openCodeDirectory, List<Path> configFiles) {

        public OnboardingCandidate {
            configFiles = configFiles == null ? List.of() : List.copyOf(configFiles);
        }
    }

    public record OnboardingResult(
        String repositoryName,
        String profileName,
        Path repositoryPath,
        List<Path> importedFiles,
        ProfileService.ProfileSwitchResult switchResult
    ) {

        public OnboardingResult {
            importedFiles = importedFiles == null ? List.of() : List.copyOf(importedFiles);
        }
    }
}
