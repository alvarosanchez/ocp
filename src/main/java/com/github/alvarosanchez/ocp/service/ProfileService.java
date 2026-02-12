package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import com.github.alvarosanchez.ocp.git.GitRepositoryClient.CommitMetadata;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile.ProfileEntry;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Service that discovers available profile names across configured repositories.
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
     * Lists all known profiles from configured repositories.
     *
     * @return sorted, unique profiles discovered from repository metadata
     */
    public List<ProfileEntry> listProfiles() {
        List<ProfileEntry> profiles = new ArrayList<>();
        for (DiscoveredProfile profile : discoverProfiles()) {
            profiles.add(new ProfileEntry(profile.profileName()));
        }
        return profiles;
    }

    /**
     * Lists profiles with repository and commit metadata for tabular CLI output.
     *
     * @return profile table data with optional update footnotes
     */
    public ProfileListResult listProfilesTable() {
        List<DiscoveredProfile> discoveredProfiles = discoverProfiles();
        Map<String, RepositorySnapshot> snapshotsByRepository = new HashMap<>();
        Set<String> failedVersionChecks = new TreeSet<>();

        for (RepositoryEntry repositoryEntry : repositoryService.load()) {
            snapshotsByRepository.put(
                repositoryEntry.name(),
                snapshotForRepository(repositoryEntry, failedVersionChecks)
            );
        }

        List<ProfileListRow> rows = new ArrayList<>();
        boolean hasUpdates = false;
        for (DiscoveredProfile discoveredProfile : discoveredProfiles) {
            RepositorySnapshot snapshot = snapshotsByRepository.get(discoveredProfile.repositoryEntry().name());
            if (snapshot == null) {
                continue;
            }
            boolean updateAvailable = snapshot.commitsBehindRemote() > 0;
            hasUpdates = hasUpdates || updateAvailable;
            rows.add(
                new ProfileListRow(
                    discoveredProfile.profileName(),
                    discoveredProfile.repositoryEntry().uri(),
                    snapshot.shortSha(),
                    humanizeInstant(snapshot.commitEpochSeconds()),
                    snapshot.message(),
                    updateAvailable
                )
            );
        }

        return new ProfileListResult(rows, hasUpdates, List.copyOf(failedVersionChecks));
    }

    /**
     * Creates a profile directory and registers it in repository metadata.
     *
     * @param repositoryPath repository root path
     * @param profileName profile name to create
     */
    public void createProfile(Path repositoryPath, String profileName) {
        String normalizedProfileName = profileName == null ? "" : profileName.trim();
        if (normalizedProfileName.isBlank()) {
            throw new IllegalStateException("Profile name is required.");
        }

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
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create profile `" + normalizedProfileName + "` in " + repositoryPath, e);
        }
    }

    /**
     * Switches the active profile by linking its files into the OpenCode config directory.
     *
     * @param profileName profile name to activate
     */
    public void useProfile(String profileName) {
        ResolvedProfile resolvedProfile = resolveProfile(profileName);
        Path profilePath = Path.of(resolvedProfile.repositoryEntry().localPath()).resolve(resolvedProfile.profileName());
        if (!Files.isDirectory(profilePath)) {
            throw new IllegalStateException("Profile directory does not exist: " + profilePath);
        }

        Optional<Path> previousProfilePath = activeProfileName()
            .filter(previousProfileName -> !previousProfileName.equals(resolvedProfile.profileName()))
            .flatMap(this::profilePathByName);

        switchProfileFiles(profilePath, previousProfilePath.orElse(null), openCodeDirectory());
        writeActiveProfile(resolvedProfile.profileName());
    }

    /**
     * Pulls latest changes for the repository containing the requested profile.
     *
     * @param profileName profile name used to resolve repository
     */
    public void refreshProfile(String profileName) {
        ResolvedProfile resolvedProfile = resolveProfile(profileName);
        gitRepositoryClient.pull(Path.of(resolvedProfile.repositoryEntry().localPath()));
    }

    /**
     * Pulls latest changes for all configured repositories.
     */
    public void refreshAllProfiles() {
        for (RepositoryEntry repositoryEntry : repositoryService.load()) {
            gitRepositoryClient.pull(Path.of(repositoryEntry.localPath()));
        }
    }

    /**
     * Returns the currently active profile name when available.
     *
     * @return active profile name, or empty when no profile is active
     */
    public Optional<String> activeProfileName() {
        return Optional.ofNullable(repositoryService.loadConfigFile().config().activeProfile());
    }

    private void writeActiveProfile(String profileName) {
        OcpConfigFile currentConfig = repositoryService.loadConfigFile();
        OcpConfigOptions nextOptions = new OcpConfigOptions(profileName);
        repositoryService.saveConfig(new OcpConfigFile(nextOptions, currentConfig.repositories()));
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

    private Optional<Path> profilePathByName(String profileName) {
        for (RepositoryEntry repositoryEntry : repositoryService.load()) {
            Path localPath = Path.of(repositoryEntry.localPath());
            for (ProfileEntry profileEntry : readProfiles(localPath.resolve("repository.json"))) {
                if (profileName.equals(profileEntry.name())) {
                    return Optional.of(localPath.resolve(profileName));
                }
            }
        }
        return Optional.empty();
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

    private ResolvedProfile resolveProfile(String profileName) {
        String normalizedProfileName = profileName == null ? "" : profileName.trim();
        if (normalizedProfileName.isBlank()) {
            throw new IllegalStateException("Profile name is required.");
        }

        for (DiscoveredProfile profile : discoverProfiles()) {
            if (normalizedProfileName.equals(profile.profileName())) {
                return new ResolvedProfile(profile.repositoryEntry(), normalizedProfileName);
            }
        }

        throw new IllegalStateException("Profile `" + normalizedProfileName + "` was not found.");
    }

    private List<DiscoveredProfile> discoverProfiles() {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new TreeSet<>();
        List<DiscoveredProfile> discoveredProfiles = new ArrayList<>();

        for (RepositoryEntry repositoryEntry : repositoryService.load()) {
            Path localPath = Path.of(repositoryEntry.localPath());
            for (ProfileEntry profileEntry : readProfiles(localPath.resolve("repository.json"))) {
                if (!seen.add(profileEntry.name())) {
                    duplicates.add(profileEntry.name());
                    continue;
                }
                discoveredProfiles.add(new DiscoveredProfile(profileEntry.name(), repositoryEntry));
            }
        }

        if (!duplicates.isEmpty()) {
            throw new DuplicateProfilesException(duplicates);
        }

        discoveredProfiles.sort(Comparator.comparing(DiscoveredProfile::profileName));
        return discoveredProfiles;
    }

    private RepositorySnapshot snapshotForRepository(RepositoryEntry repositoryEntry, Set<String> failedVersionChecks) {
        Path localPath = Path.of(repositoryEntry.localPath());

        String shortSha = "-";
        long commitEpochSeconds = 0L;
        String message = "-";

        try {
            CommitMetadata latestCommit = gitRepositoryClient.latestCommit(localPath);
            shortSha = latestCommit.shortSha();
            commitEpochSeconds = latestCommit.epochSeconds();
            message = latestCommit.message();
        } catch (RuntimeException e) {
            message = "No local commits";
        }

        int commitsBehindRemote = 0;
        try {
            commitsBehindRemote = gitRepositoryClient.commitsBehindRemote(localPath);
        } catch (RuntimeException e) {
            failedVersionChecks.add(repositoryEntry.name());
        }

        return new RepositorySnapshot(shortSha, commitEpochSeconds, message, commitsBehindRemote);
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

    private String timestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now());
    }

    private record FileSwitchState(Path target, Path backupPath, Path previousSymlinkTarget) {
    }

    private record DiscoveredProfile(String profileName, RepositoryEntry repositoryEntry) {
    }

    private record RepositorySnapshot(String shortSha, long commitEpochSeconds, String message, int commitsBehindRemote) {
    }

    private record ResolvedProfile(RepositoryEntry repositoryEntry, String profileName) {
    }

    /**
     * Row data for `ocp profile list` table output.
     *
     * @param name profile name
     * @param repository repository URI
     * @param version short local commit SHA, optionally with update marker
     * @param lastUpdated humanized relative local commit time
     * @param message latest local commit message
     * @param updateAvailable whether remote has newer commits
     */
    public record ProfileListRow(
        String name,
        String repository,
        String version,
        String lastUpdated,
        String message,
        boolean updateAvailable
    ) {
    }

    /**
     * Result for profile table rendering, including update footnotes.
     *
     * @param rows rows to render in table output
     * @param hasUpdates whether at least one row has updates available
     * @param failedVersionChecks repositories where remote checks failed
     */
    public record ProfileListResult(List<ProfileListRow> rows, boolean hasUpdates, List<String> failedVersionChecks) {

        /**
         * Creates a profile list result.
         *
         * @param rows rows to render in table output
         * @param hasUpdates whether at least one row has updates available
         * @param failedVersionChecks repositories where remote checks failed
         */
        public ProfileListResult {
            rows = List.copyOf(rows);
            failedVersionChecks = List.copyOf(failedVersionChecks);
        }
    }

    /**
     * Exception thrown when duplicate profile names are found across repositories.
     */
    public static final class DuplicateProfilesException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Set<String> duplicateProfileNames;

        DuplicateProfilesException(Set<String> duplicateProfileNames) {
            super("Duplicate profile names found: " + String.join(", ", duplicateProfileNames));
            this.duplicateProfileNames = Set.copyOf(duplicateProfileNames);
        }

        /**
         * Returns duplicate profile names discovered while listing profiles.
         *
         * @return duplicate profile names
         */
        public Set<String> duplicateProfileNames() {
            return duplicateProfileNames;
        }
    }
}
