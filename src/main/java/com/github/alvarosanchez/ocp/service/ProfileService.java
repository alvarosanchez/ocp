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

/**
 * Service that manages profile discovery, activation, and repository refresh operations.
 */
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

    /**
     * Returns the currently active profile with repository metadata.
     *
     * @return active profile
     */
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

    /**
     * Discovers all available profiles across configured repositories.
     *
     * @return profiles sorted by name
     */
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

    /**
     * Creates a profile scaffold in the current working repository.
     *
     * @param profileName profile name to create
     * @return {@code true} when the profile is created
     */
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

    /**
     * Switches the active profile and updates symlinks in the OpenCode config directory.
     *
     * @param profileName profile name to activate
     * @return {@code true} when the switch is applied
     */
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

    /**
     * Refreshes the repository that contains the specified profile.
     *
     * @param profileName profile name whose repository should be refreshed
     * @return {@code true} when refresh completes successfully
     */
    public boolean refreshProfile(String profileName) {
        String normalizedProfileName = normalizeProfileName(profileName);
        RepositoryEntry repositoryEntry = repositoryForProfile(normalizedProfileName);
        if (repositoryEntry == null) {
            throw new IllegalStateException("Profile `" + normalizedProfileName + "` was not found.");
        }

        refreshRepository(repositoryEntry);
        return true;
    }

    /**
     * Refreshes all configured repositories.
     *
     * @return {@code true} when all refresh operations complete successfully
     */
    public boolean refreshAllProfiles() {
        for (RepositoryEntry repositoryEntry : repositoryService.load()) {
            refreshRepository(repositoryEntry);
        }
        return true;
    }

    /**
     * Applies a selected conflict resolution strategy and retries refresh.
     *
     * @param conflict detected refresh conflict
     * @param resolution selected conflict resolution
     * @return {@code true} when changes were applied, {@code false} when no action was taken
     */
    public boolean resolveRefreshConflict(ProfileRefreshConflictException conflict, RefreshConflictResolution resolution) {
        Path localPath = Path.of(conflict.repositoryPath());
        if (resolution == RefreshConflictResolution.DISCARD_AND_REFRESH) {
            gitRepositoryClient.discardLocalChanges(localPath);
        } else if (resolution == RefreshConflictResolution.COMMIT_AND_FORCE_PUSH) {
            gitRepositoryClient.commitLocalChangesAndForcePush(localPath);
        } else {
            return false;
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

    private void refreshRepository(RepositoryEntry repositoryEntry) {
        Path localPath = Path.of(repositoryEntry.localPath());
        if (gitRepositoryClient.hasLocalChanges(localPath)) {
            throw new ProfileRefreshConflictException(
                repositoryEntry.name(),
                repositoryEntry.localPath(),
                gitRepositoryClient.localDiff(localPath)
            );
        }
        gitRepositoryClient.pull(localPath);
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

    /**
     * Raised when no active profile is configured.
     */
    public static final class NoActiveProfileException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NoActiveProfileException(String message) {
            super(message);
        }
    }

    /**
     * Raised when duplicate profile names are discovered across repositories.
     */
    public static final class DuplicateProfilesException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Set<String> duplicateProfileNames;

        DuplicateProfilesException(Set<String> duplicateProfileNames) {
            super("Duplicate profile names found: " + String.join(", ", duplicateProfileNames));
            this.duplicateProfileNames = Set.copyOf(duplicateProfileNames);
        }

        /**
         * Returns duplicate profile names.
         *
         * @return immutable set of duplicate names
         */
        public Set<String> duplicateProfileNames() {
            return duplicateProfileNames;
        }
    }

    /**
     * Refresh conflict resolution options.
     */
    public enum RefreshConflictResolution {
        /**
         * Discard local uncommitted changes and continue refresh.
         */
        DISCARD_AND_REFRESH,
        /**
         * Commit local changes, force-push, and continue refresh.
         */
        COMMIT_AND_FORCE_PUSH,
        /**
         * Do not apply automatic conflict resolution.
         */
        DO_NOTHING
    }

    /**
     * Raised when a repository has local changes that block refresh.
     */
    public static final class ProfileRefreshConflictException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String repositoryName;
        private final String repositoryPath;
        private final String diff;

        ProfileRefreshConflictException(String repositoryName, String repositoryPath, String diff) {
            super("Local changes detected in repository `" + repositoryName + "`.");
            this.repositoryName = repositoryName;
            this.repositoryPath = repositoryPath;
            this.diff = diff == null ? "" : diff;
        }

        /**
         * Returns the repository name that caused the conflict.
         *
         * @return repository name
         */
        public String repositoryName() {
            return repositoryName;
        }

        /**
         * Returns the repository local path that caused the conflict.
         *
         * @return repository local path
         */
        public String repositoryPath() {
            return repositoryPath;
        }

        /**
         * Returns the local diff captured at conflict time.
         *
         * @return textual diff output
         */
        public String diff() {
            return diff;
        }
    }
}
