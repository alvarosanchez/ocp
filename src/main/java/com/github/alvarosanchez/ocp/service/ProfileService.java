package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import com.github.alvarosanchez.ocp.git.GitRepositoryClient.CommitMetadata;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile.ProfileEntry;
import com.github.alvarosanchez.ocp.model.Profile;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Singleton
public final class ProfileService {

    private final ObjectMapper objectMapper;
    private final RepositoryService repositoryService;
    private final GitRepositoryClient gitRepositoryClient;

    ProfileService(
        ObjectMapper objectMapper,
        RepositoryService repositoryService,
        GitRepositoryClient gitRepositoryClient
    ) {
        this.objectMapper = objectMapper;
        this.repositoryService = repositoryService;
        this.gitRepositoryClient = gitRepositoryClient;
    }

    public Profile getActiveProfile() {
        String activeProfileName = currentActiveProfileName();
        if (activeProfileName == null || activeProfileName.isBlank()) {
            throw new NoActiveProfileException("No active profile selected yet.");
        }

        RepositoryEntry repositoryEntry = repositoryForProfile(activeProfileName);
        if (repositoryEntry == null) {
            throw new IllegalStateException(
                "Active profile `" + activeProfileName + "` is not available in configured repositories."
            );
        }

        RepositoryStatus repositoryStatus = repositoryStatusFor(repositoryEntry);
        return toProfile(activeProfileName, repositoryEntry, repositoryStatus, true);
    }

    public List<Profile> getAllProfiles() {
        Map<String, RepositoryEntry> profilesByName = discoverProfilesByName();
        Map<String, RepositoryStatus> statusesByRepository = new HashMap<>();
        for (RepositoryEntry repositoryEntry : repositoryService.load()) {
            statusesByRepository.put(repositoryEntry.name(), repositoryStatusFor(repositoryEntry));
        }

        List<String> sortedProfileNames = new ArrayList<>(profilesByName.keySet());
        sortedProfileNames.sort(String::compareTo);

        String activeProfileName = currentActiveProfileName();
        List<Profile> profiles = new ArrayList<>();
        for (String profileName : sortedProfileNames) {
            RepositoryEntry repositoryEntry = profilesByName.get(profileName);
            RepositoryStatus repositoryStatus = statusesByRepository.get(repositoryEntry.name());
            if (repositoryStatus == null) {
                continue;
            }
            profiles.add(toProfile(profileName, repositoryEntry, repositoryStatus, profileName.equals(activeProfileName)));
        }

        return profiles;
    }

    public boolean createProfile(String profileName) {
        String normalizedProfileName = normalizeProfileName(profileName);
        Path repositoryPath = workingDirectory();
        Path metadataFile = repositoryPath.resolve("repository.json");
        RepositoryConfigFile repositoryConfigFile = readRepositoryConfigFile(metadataFile);

        for (ProfileEntry profileEntry : repositoryConfigFile.profiles()) {
            if (normalizedProfileName.equals(profileEntry.name())) {
                throw new IllegalStateException("Profile `" + normalizedProfileName + "` already exists.");
            }
        }

        try {
            Files.createDirectories(repositoryPath.resolve(normalizedProfileName));
            List<ProfileEntry> profiles = new ArrayList<>(repositoryConfigFile.profiles());
            profiles.add(new ProfileEntry(normalizedProfileName));
            writeRepositoryConfigFile(metadataFile, new RepositoryConfigFile(profiles));
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create profile `" + normalizedProfileName + "` in " + repositoryPath, e);
        }
    }

    public boolean useProfile(String profileName) {
        String normalizedProfileName = normalizeProfileName(profileName);
        Map<String, RepositoryEntry> profilesByName = discoverProfilesByName();
        RepositoryEntry repositoryEntry = profilesByName.get(normalizedProfileName);
        if (repositoryEntry == null) {
            throw new IllegalStateException("Profile `" + normalizedProfileName + "` was not found.");
        }

        Path profilePath = profilePathFor(normalizedProfileName, repositoryEntry);
        if (!Files.isDirectory(profilePath)) {
            throw new IllegalStateException("Profile directory does not exist: " + profilePath);
        }

        Path previousProfilePath = null;
        String previousProfileName = currentActiveProfileName();
        if (previousProfileName != null && !previousProfileName.equals(normalizedProfileName)) {
            RepositoryEntry previousRepository = profilesByName.get(previousProfileName);
            if (previousRepository != null) {
                previousProfilePath = profilePathFor(previousProfileName, previousRepository);
            }
        }

        switchProfileFiles(profilePath, previousProfilePath, openCodeDirectory());
        writeActiveProfile(normalizedProfileName);
        return true;
    }

    public boolean refreshProfile(String profileName) {
        String normalizedProfileName = normalizeProfileName(profileName);
        RepositoryEntry repositoryEntry = repositoryForProfile(normalizedProfileName);
        if (repositoryEntry == null) {
            throw new IllegalStateException("Profile `" + normalizedProfileName + "` was not found.");
        }

        gitRepositoryClient.pull(Path.of(repositoryEntry.localPath()));
        return true;
    }

    public boolean refreshAllProfiles() {
        for (RepositoryEntry repositoryEntry : repositoryService.load()) {
            gitRepositoryClient.pull(Path.of(repositoryEntry.localPath()));
        }
        return true;
    }

    private void writeActiveProfile(String profileName) {
        OcpConfigFile currentConfig = repositoryService.loadConfigFile();
        OcpConfigOptions nextOptions = new OcpConfigOptions(profileName);
        repositoryService.saveConfig(new OcpConfigFile(nextOptions, currentConfig.repositories()));
    }

    private String currentActiveProfileName() {
        return repositoryService.loadConfigFile().config().activeProfile();
    }

    private void switchProfileFiles(Path sourceDirectory, Path previousSourceDirectory, Path targetDirectory) {
        List<Path> sourceFiles = profileFiles(sourceDirectory);
        Set<Path> sourceFileRelativePaths = new HashSet<>();
        for (Path sourceFile : sourceFiles) {
            sourceFileRelativePaths.add(sourceDirectory.relativize(sourceFile));
        }

        Path backupRoot = backupsDirectory().resolve(timestamp());
        List<FileSwitchState> switchStates = new ArrayList<>();

        try {
            if (previousSourceDirectory != null) {
                for (Path previousSourceFile : profileFiles(previousSourceDirectory)) {
                    Path relativePath = previousSourceDirectory.relativize(previousSourceFile);
                    if (sourceFileRelativePaths.contains(relativePath)) {
                        continue;
                    }

                    Path targetFile = targetDirectory.resolve(relativePath);
                    if (!Files.isSymbolicLink(targetFile)) {
                        continue;
                    }

                    Path currentSymlinkTarget = Files.readSymbolicLink(targetFile);
                    if (!currentSymlinkTarget.equals(previousSourceFile.toAbsolutePath())) {
                        continue;
                    }

                    switchStates.add(new FileSwitchState(targetFile, null, currentSymlinkTarget));
                    Files.delete(targetFile);
                }
            }

            for (Path sourceFile : sourceFiles) {
                Path relativePath = sourceDirectory.relativize(sourceFile);
                Path targetFile = targetDirectory.resolve(relativePath);

                Files.createDirectories(targetFile.getParent());

                Path backupPath = null;
                Path previousSymlinkTarget = null;
                boolean existedBefore = Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS);

                if (existedBefore) {
                    if (Files.isSymbolicLink(targetFile)) {
                        previousSymlinkTarget = Files.readSymbolicLink(targetFile);
                        Files.delete(targetFile);
                    } else {
                        backupPath = backupRoot.resolve(relativePath);
                        Files.createDirectories(backupPath.getParent());
                        Files.move(targetFile, backupPath);
                    }
                }

                switchStates.add(new FileSwitchState(targetFile, backupPath, previousSymlinkTarget));
                Files.createSymbolicLink(targetFile, sourceFile.toAbsolutePath());
            }
        } catch (IOException e) {
            IOException rollbackFailure = rollbackSwitch(switchStates);
            if (rollbackFailure != null) {
                e.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("Failed to switch active profile files", e);
        }
    }

    private IOException rollbackSwitch(List<FileSwitchState> switchStates) {
        List<FileSwitchState> rollbackStates = new ArrayList<>(switchStates);
        rollbackStates.sort(Comparator.comparing((FileSwitchState state) -> state.target().toString()).reversed());
        IOException rollbackFailure = null;
        for (FileSwitchState state : rollbackStates) {
            try {
                Files.deleteIfExists(state.target());
                if (state.previousSymlinkTarget() != null) {
                    Files.createSymbolicLink(state.target(), state.previousSymlinkTarget());
                } else if (state.backupPath() != null && Files.exists(state.backupPath())) {
                    Files.createDirectories(state.target().getParent());
                    Files.move(state.backupPath(), state.target());
                }
            } catch (IOException e) {
                if (rollbackFailure == null) {
                    rollbackFailure = new IOException("Failed to rollback profile switch");
                }
                rollbackFailure.addSuppressed(e);
            }
        }
        return rollbackFailure;
    }

    private List<Path> profileFiles(Path sourceDirectory) {
        if (!Files.exists(sourceDirectory)) {
            return List.of();
        }
        try (var paths = Files.walk(sourceDirectory)) {
            return paths
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list profile files from " + sourceDirectory, e);
        }
    }

    private String normalizeProfileName(String profileName) {
        String normalizedProfileName = profileName == null ? "" : profileName.trim();
        if (normalizedProfileName.isBlank()) {
            throw new IllegalStateException("Profile name is required.");
        }
        return normalizedProfileName;
    }

    private RepositoryEntry repositoryForProfile(String profileName) {
        return discoverProfilesByName().get(profileName);
    }

    private Map<String, RepositoryEntry> discoverProfilesByName() {
        Map<String, RepositoryEntry> profilesByName = new HashMap<>();
        Set<String> duplicates = new TreeSet<>();

        for (RepositoryEntry repositoryEntry : repositoryService.load()) {
            Path localPath = Path.of(repositoryEntry.localPath());
            for (ProfileEntry profileEntry : readProfiles(localPath.resolve("repository.json"))) {
                RepositoryEntry existing = profilesByName.putIfAbsent(profileEntry.name(), repositoryEntry);
                if (existing != null) {
                    duplicates.add(profileEntry.name());
                }
            }
        }

        if (!duplicates.isEmpty()) {
            throw new DuplicateProfilesException(duplicates);
        }

        return profilesByName;
    }

    private Path profilePathFor(String profileName, RepositoryEntry repositoryEntry) {
        return Path.of(repositoryEntry.localPath()).resolve(profileName);
    }

    private RepositoryStatus repositoryStatusFor(RepositoryEntry repositoryEntry) {
        Path localPath = Path.of(repositoryEntry.localPath());

        String shortSha = "-";
        long commitEpochSeconds = 0L;
        String message = "No local commits";

        try {
            CommitMetadata latestCommit = gitRepositoryClient.latestCommit(localPath);
            shortSha = latestCommit.shortSha();
            commitEpochSeconds = latestCommit.epochSeconds();
            message = latestCommit.message();
        } catch (RuntimeException e) {
            shortSha = "-";
            commitEpochSeconds = 0L;
            message = "No local commits";
        }

        boolean updateAvailable = false;
        boolean versionCheckFailed = false;
        try {
            updateAvailable = gitRepositoryClient.differsFromUpstream(localPath);
        } catch (RuntimeException e) {
            versionCheckFailed = true;
        }

        return new RepositoryStatus(shortSha, commitEpochSeconds, message, updateAvailable, versionCheckFailed);
    }

    private Profile toProfile(
        String profileName,
        RepositoryEntry repositoryEntry,
        RepositoryStatus repositoryStatus,
        boolean active
    ) {
        return new Profile(
            profileName,
            repositoryEntry.name(),
            repositoryEntry.uri(),
            repositoryStatus.shortSha(),
            humanizeInstant(repositoryStatus.commitEpochSeconds()),
            repositoryStatus.message(),
            repositoryStatus.updateAvailable(),
            active,
            repositoryStatus.versionCheckFailed()
        );
    }

    private String humanizeInstant(long epochSeconds) {
        if (epochSeconds <= 0) {
            return "unknown";
        }

        Duration duration = Duration.between(Instant.ofEpochSecond(epochSeconds), Instant.now());
        if (duration.isNegative()) {
            return "just now";
        }

        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return ago(seconds, "second");
        }

        long minutes = seconds / 60;
        if (minutes < 60) {
            return ago(minutes, "minute");
        }

        long hours = minutes / 60;
        if (hours < 24) {
            return ago(hours, "hour");
        }

        long days = hours / 24;
        if (days < 30) {
            return ago(days, "day");
        }

        long months = days / 30;
        if (months < 12) {
            return ago(months, "month");
        }

        long years = months / 12;
        return ago(years, "year");
    }

    private String ago(long value, String unit) {
        return value + " " + unit + (value == 1 ? "" : "s") + " ago";
    }

    private RepositoryConfigFile readRepositoryConfigFile(Path metadataFile) {
        if (!Files.exists(metadataFile)) {
            return new RepositoryConfigFile(List.of());
        }
        try {
            String content = Files.readString(metadataFile);
            return objectMapper.readValue(content, RepositoryConfigFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read profile metadata from " + metadataFile, e);
        }
    }

    private void writeRepositoryConfigFile(Path metadataFile, RepositoryConfigFile configFile) {
        try {
            Files.createDirectories(metadataFile.getParent());
            Files.writeString(metadataFile, objectMapper.writeValueAsString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write profile metadata to " + metadataFile, e);
        }
    }

    private List<ProfileEntry> readProfiles(Path profilesFile) {
        RepositoryConfigFile configFile = readRepositoryConfigFile(profilesFile);
        return configFile
            .profiles()
            .stream()
            .filter(entry -> entry.name() != null && !entry.name().isBlank())
            .toList();
    }

    private Path configDirectory() {
        String configuredPath = System.getProperty("ocp.config.dir");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.home"), ".config", "ocp");
    }

    private Path backupsDirectory() {
        return configDirectory().resolve("backups");
    }

    private Path openCodeDirectory() {
        String configuredPath = System.getProperty("ocp.opencode.config.dir");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.home"), ".config", "opencode");
    }

    private Path workingDirectory() {
        String configuredPath = System.getProperty("ocp.working.dir");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    private String timestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now());
    }

    private record FileSwitchState(Path target, Path backupPath, Path previousSymlinkTarget) {
    }

    private static final class RepositoryStatus {

        private final String shortSha;
        private final long commitEpochSeconds;
        private final String message;
        private final boolean updateAvailable;
        private final boolean versionCheckFailed;

        RepositoryStatus(String shortSha, long commitEpochSeconds, String message, boolean updateAvailable, boolean versionCheckFailed) {
            this.shortSha = shortSha;
            this.commitEpochSeconds = commitEpochSeconds;
            this.message = message;
            this.updateAvailable = updateAvailable;
            this.versionCheckFailed = versionCheckFailed;
        }

        String shortSha() {
            return shortSha;
        }

        long commitEpochSeconds() {
            return commitEpochSeconds;
        }

        String message() {
            return message;
        }

        boolean updateAvailable() {
            return updateAvailable;
        }

        boolean versionCheckFailed() {
            return versionCheckFailed;
        }
    }

    public static final class NoActiveProfileException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NoActiveProfileException(String message) {
            super(message);
        }
    }

    public static final class DuplicateProfilesException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Set<String> duplicateProfileNames;

        DuplicateProfilesException(Set<String> duplicateProfileNames) {
            super("Duplicate profile names found: " + String.join(", ", duplicateProfileNames));
            this.duplicateProfileNames = Set.copyOf(duplicateProfileNames);
        }

        public Set<String> duplicateProfileNames() {
            return duplicateProfileNames;
        }
    }
}
