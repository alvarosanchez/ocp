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
import java.util.LinkedHashMap;
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

        DiscoveredProfile discoveredProfile = discoverProfilesByName().get(activeProfileName);
        if (discoveredProfile == null) {
            throw new IllegalStateException(
                "Active profile `" + activeProfileName + "` is not available in configured repositories."
            );
        }

        RepositoryStatus repositoryStatus = repositoryStatusFor(discoveredProfile.repositoryEntry());
        return toProfile(discoveredProfile, repositoryStatus, true);
    }

    /**
     * Discovers all available profiles across configured repositories.
     *
     * @return profiles sorted by name
     */
    public List<Profile> getAllProfiles() {
        Map<String, DiscoveredProfile> profilesByName = discoverProfilesByName();
        Map<String, RepositoryStatus> statusesByRepository = new HashMap<>();
        for (RepositoryEntry repositoryEntry : repositoryService.load()) {
            statusesByRepository.put(repositoryEntry.name(), repositoryStatusFor(repositoryEntry));
        }

        List<String> sortedProfileNames = new ArrayList<>(profilesByName.keySet());
        sortedProfileNames.sort(String::compareTo);

        String activeProfileName = currentActiveProfileName();
        List<Profile> profiles = new ArrayList<>();
        for (String profileName : sortedProfileNames) {
            DiscoveredProfile discoveredProfile = profilesByName.get(profileName);
            RepositoryStatus repositoryStatus = statusesByRepository.get(discoveredProfile.repositoryEntry().name());
            if (repositoryStatus == null) {
                continue;
            }
            profiles.add(toProfile(discoveredProfile, repositoryStatus, profileName.equals(activeProfileName)));
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
        Map<String, DiscoveredProfile> profilesByName = discoverProfilesByName();
        DiscoveredProfile discoveredProfile = profilesByName.get(normalizedProfileName);
        if (discoveredProfile == null) {
            throw new IllegalStateException("Profile `" + normalizedProfileName + "` was not found.");
        }

        List<ResolvedProfileFile> resolvedFiles = resolveProfileFiles(normalizedProfileName, profilesByName, true);
        List<ResolvedProfileFile> previousResolvedFiles = List.of();
        String previousProfileName = currentActiveProfileName();
        if (previousProfileName != null && !previousProfileName.equals(normalizedProfileName)) {
            DiscoveredProfile previousProfile = profilesByName.get(previousProfileName);
            if (previousProfile != null) {
                previousResolvedFiles = resolveProfileFiles(previousProfileName, profilesByName, false);
            }
        }

        switchProfileFiles(resolvedFiles, previousResolvedFiles, openCodeDirectory());
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
        Map<String, DiscoveredProfile> profilesByName = discoverProfilesByName();
        DiscoveredProfile discoveredProfile = profilesByName.get(normalizedProfileName);
        if (discoveredProfile == null) {
            throw new IllegalStateException("Profile `" + normalizedProfileName + "` was not found.");
        }

        refreshRepository(discoveredProfile.repositoryEntry());
        refreshActiveProfileIfAffected(normalizedProfileName);
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
        refreshActiveProfileIfConfigured();
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

    private void switchProfileFiles(
        List<ResolvedProfileFile> sourceFiles,
        List<ResolvedProfileFile> previousSourceFiles,
        Path targetDirectory
    ) {
        Set<Path> sourceFileRelativePaths = new HashSet<>();
        Set<Path> sourceLogicalRelativePaths = new HashSet<>();
        for (ResolvedProfileFile sourceFile : sourceFiles) {
            Path relativePath = sourceFile.relativePath();
            sourceFileRelativePaths.add(relativePath);
            Path logicalRelativePath = logicalRelativePath(relativePath);
            if (!sourceLogicalRelativePaths.add(logicalRelativePath)) {
                throw new IllegalStateException(
                    "Profile contains conflicting config file variants for " + logicalRelativePath
                );
            }
        }

        Path backupRoot = backupsDirectory().resolve(timestamp());
        List<FileSwitchState> switchStates = new ArrayList<>();

        try {
            for (ResolvedProfileFile previousSourceFile : previousSourceFiles) {
                Path relativePath = previousSourceFile.relativePath();
                if (sourceFileRelativePaths.contains(relativePath) || sourceLogicalRelativePaths.contains(logicalRelativePath(relativePath))) {
                    continue;
                }

                Path targetFile = targetDirectory.resolve(relativePath);
                if (!Files.isSymbolicLink(targetFile)) {
                    continue;
                }

                Path currentSymlinkTarget = Files.readSymbolicLink(targetFile);
                if (!currentSymlinkTarget.equals(previousSourceFile.sourcePath().toAbsolutePath())) {
                    continue;
                }

                switchStates.add(new FileSwitchState(targetFile, null, currentSymlinkTarget));
                Files.delete(targetFile);
            }

            for (ResolvedProfileFile sourceFile : sourceFiles) {
                Path relativePath = sourceFile.relativePath();
                Path targetFile = targetDirectory.resolve(relativePath);

                Files.createDirectories(targetFile.getParent());

                boolean targetFileHadState = false;
                for (Path occupiedTarget : occupiedTargetFiles(relativePath, targetDirectory)) {
                    if (!Files.exists(occupiedTarget, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }

                    Path backupPath = null;
                    Path previousSymlinkTarget = null;
                    if (Files.isSymbolicLink(occupiedTarget)) {
                        previousSymlinkTarget = Files.readSymbolicLink(occupiedTarget);
                        Files.delete(occupiedTarget);
                    } else {
                        Path occupiedRelativePath = targetDirectory.relativize(occupiedTarget);
                        backupPath = backupRoot.resolve(occupiedRelativePath);
                        Files.createDirectories(backupPath.getParent());
                        Files.move(occupiedTarget, backupPath);
                    }

                    switchStates.add(new FileSwitchState(occupiedTarget, backupPath, previousSymlinkTarget));
                    if (occupiedTarget.equals(targetFile)) {
                        targetFileHadState = true;
                    }
                }

                if (!targetFileHadState) {
                    switchStates.add(new FileSwitchState(targetFile, null, null));
                }
                Files.createSymbolicLink(targetFile, sourceFile.sourcePath().toAbsolutePath());
            }
        } catch (IOException e) {
            IOException rollbackFailure = rollbackSwitch(switchStates);
            if (rollbackFailure != null) {
                e.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("Failed to switch active profile files", e);
        }
    }

    private List<Path> occupiedTargetFiles(Path relativePath, Path targetDirectory) {
        List<Path> targets = new ArrayList<>();
        targets.add(targetDirectory.resolve(relativePath));

        String fileName = relativePath.getFileName().toString();
        String alternateFileName = alternateJsonVariant(fileName);
        if (alternateFileName == null) {
            return targets;
        }

        Path parent = relativePath.getParent();
        Path alternateRelativePath = parent == null ? Path.of(alternateFileName) : parent.resolve(alternateFileName);
        targets.add(targetDirectory.resolve(alternateRelativePath));
        return targets;
    }

    private Path logicalRelativePath(Path relativePath) {
        String fileName = relativePath.getFileName().toString();
        if (!fileName.endsWith(".jsonc")) {
            return relativePath;
        }

        String normalizedFileName = fileName.substring(0, fileName.length() - 1);
        Path parent = relativePath.getParent();
        return parent == null ? Path.of(normalizedFileName) : parent.resolve(normalizedFileName);
    }

    private String alternateJsonVariant(String fileName) {
        if (fileName.endsWith(".jsonc")) {
            return fileName.substring(0, fileName.length() - 1);
        }
        if (fileName.endsWith(".json")) {
            return fileName + "c";
        }
        return null;
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

    private List<ResolvedProfileFile> resolveProfileFiles(
        String profileName,
        Map<String, DiscoveredProfile> profilesByName,
        boolean materializeMergedFiles
    ) {
        List<DiscoveredProfile> lineage = profileLineageFor(profileName, profilesByName);
        Map<Path, EffectiveProfileFile> filesByLogicalRelativePath = new LinkedHashMap<>();

        for (DiscoveredProfile discoveredProfile : lineage) {
            Path profilePath = profilePathFor(discoveredProfile);
            if (!Files.isDirectory(profilePath)) {
                throw new IllegalStateException("Profile directory does not exist: " + profilePath);
            }

            Set<Path> profileLogicalPaths = new HashSet<>();
            for (Path sourceFile : profileFiles(profilePath)) {
                Path relativePath = profilePath.relativize(sourceFile);
                Path logicalRelativePath = logicalRelativePath(relativePath);
                if (!profileLogicalPaths.add(logicalRelativePath)) {
                    throw new IllegalStateException(
                        "Profile contains conflicting config file variants for " + logicalRelativePath
                    );
                }

                EffectiveProfileFile existing = filesByLogicalRelativePath.get(logicalRelativePath);
                boolean currentIsJson = isMergeableJsonFile(relativePath);
                if (existing != null && existing.jsonMergeCandidate() && currentIsJson) {
                    String existingExtension = jsonExtension(existing.relativePath());
                    String currentExtension = jsonExtension(relativePath);
                    if (!existingExtension.equals(currentExtension)) {
                        throw new IllegalStateException(
                            "Profile `" + discoveredProfile.name()
                                + "` must use the same extension as its parent for `"
                                + logicalRelativePath
                                + "`: found `"
                                + relativePath.getFileName()
                                + "` but parent defines `"
                                + existing.relativePath().getFileName()
                                + "`."
                        );
                    }
                    Object parentJson = existing.jsonValue() != null ? existing.jsonValue() : parseJsonFile(existing.sourcePath());
                    Object childJson = parseJsonFile(sourceFile);
                    Object merged = mergeJsonValues(parentJson, childJson);
                    filesByLogicalRelativePath.put(
                        logicalRelativePath,
                        new EffectiveProfileFile(relativePath, sourceFile, true, merged, true)
                    );
                } else if (currentIsJson) {
                    filesByLogicalRelativePath.put(
                        logicalRelativePath,
                        new EffectiveProfileFile(relativePath, sourceFile, true, null, false)
                    );
                } else {
                    filesByLogicalRelativePath.put(
                        logicalRelativePath,
                        new EffectiveProfileFile(relativePath, sourceFile, false, null, false)
                    );
                }
            }
        }

        Path resolvedDirectory = resolvedProfileDirectory(profileName);
        if (materializeMergedFiles) {
            deleteRecursively(resolvedDirectory);
        }

        List<ResolvedProfileFile> resolvedFiles = new ArrayList<>();
        for (EffectiveProfileFile effectiveFile : filesByLogicalRelativePath.values()) {
            if (effectiveFile.mergedJson()) {
                Path resolvedFile = resolvedDirectory.resolve(effectiveFile.relativePath());
                if (materializeMergedFiles) {
                    try {
                        Files.createDirectories(resolvedFile.getParent());
                        Files.writeString(resolvedFile, objectMapper.writeValueAsString(effectiveFile.jsonValue()));
                    } catch (IOException e) {
                        throw new IllegalStateException(
                            "Failed to materialize merged profile file " + effectiveFile.relativePath(),
                            e
                        );
                    }
                }
                resolvedFiles.add(new ResolvedProfileFile(effectiveFile.relativePath(), resolvedFile));
            } else {
                resolvedFiles.add(new ResolvedProfileFile(effectiveFile.relativePath(), effectiveFile.sourcePath()));
            }
        }

        resolvedFiles.sort(Comparator.comparing(file -> file.relativePath().toString()));
        return resolvedFiles;
    }

    private List<DiscoveredProfile> profileLineageFor(
        String profileName,
        Map<String, DiscoveredProfile> profilesByName
    ) {
        List<DiscoveredProfile> lineage = new ArrayList<>();
        collectProfileLineage(profileName, profilesByName, new ArrayList<>(), new HashSet<>(), lineage);
        return lineage;
    }

    private void collectProfileLineage(
        String profileName,
        Map<String, DiscoveredProfile> profilesByName,
        List<String> traversalPath,
        Set<String> visiting,
        List<DiscoveredProfile> lineage
    ) {
        DiscoveredProfile discoveredProfile = profilesByName.get(profileName);
        if (discoveredProfile == null) {
            throw new IllegalStateException("Profile `" + profileName + "` was not found.");
        }
        if (!visiting.add(profileName)) {
            List<String> cycle = new ArrayList<>(traversalPath);
            cycle.add(profileName);
            throw new IllegalStateException("Profile inheritance cycle detected: " + String.join(" -> ", cycle));
        }

        traversalPath.add(profileName);
        String parentProfileName = discoveredProfile.extendsFrom();
        if (parentProfileName != null) {
            if (parentProfileName.equals(profileName)) {
                throw new IllegalStateException("Profile `" + profileName + "` cannot extend itself.");
            }
            if (!profilesByName.containsKey(parentProfileName)) {
                throw new IllegalStateException(
                    "Profile `" + profileName + "` extends unknown profile `" + parentProfileName + "`."
                );
            }
            collectProfileLineage(parentProfileName, profilesByName, traversalPath, visiting, lineage);
        }

        lineage.add(discoveredProfile);
        traversalPath.remove(traversalPath.size() - 1);
        visiting.remove(profileName);
    }

    private Object parseJsonFile(Path jsonFile) {
        try {
            String content = Files.readString(jsonFile);
            if (isJsoncFile(jsonFile)) {
                content = stripJsoncComments(content);
            }
            return objectMapper.readValue(content, Object.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse JSON/JSONC profile file: " + jsonFile, e);
        }
    }

    private String stripJsoncComments(String jsoncContent) {
        StringBuilder jsonBuilder = new StringBuilder(jsoncContent.length());
        boolean inString = false;
        boolean escaped = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int index = 0; index < jsoncContent.length(); index++) {
            char current = jsoncContent.charAt(index);
            char next = index + 1 < jsoncContent.length() ? jsoncContent.charAt(index + 1) : '\0';

            if (inLineComment) {
                if (current == '\n' || current == '\r') {
                    inLineComment = false;
                    jsonBuilder.append(current);
                }
                continue;
            }

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    index++;
                    continue;
                }
                if (current == '\n' || current == '\r') {
                    jsonBuilder.append(current);
                }
                continue;
            }

            if (inString) {
                jsonBuilder.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
                jsonBuilder.append(current);
                continue;
            }

            if (current == '/' && next == '/') {
                inLineComment = true;
                index++;
                continue;
            }

            if (current == '/' && next == '*') {
                inBlockComment = true;
                index++;
                continue;
            }

            jsonBuilder.append(current);
        }

        return jsonBuilder.toString();
    }

    @SuppressWarnings("unchecked")
    private Object mergeJsonValues(Object parent, Object child) {
        if (parent instanceof Map<?, ?> parentMap && child instanceof Map<?, ?> childMap) {
            Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) parentMap);
            for (Map.Entry<?, ?> entry : childMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (merged.containsKey(key)) {
                    merged.put(key, mergeJsonValues(merged.get(key), entry.getValue()));
                } else {
                    merged.put(key, entry.getValue());
                }
            }
            return merged;
        }
        return child;
    }

    private boolean isMergeableJsonFile(Path relativePath) {
        String fileName = relativePath.getFileName().toString();
        return fileName.endsWith(".json") || fileName.endsWith(".jsonc");
    }

    private String jsonExtension(Path relativePath) {
        String fileName = relativePath.getFileName().toString();
        if (fileName.endsWith(".jsonc")) {
            return ".jsonc";
        }
        if (fileName.endsWith(".json")) {
            return ".json";
        }
        return "";
    }

    private boolean isJsoncFile(Path path) {
        return path.getFileName().toString().endsWith(".jsonc");
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

    private void refreshActiveProfileIfAffected(String refreshedProfileName) {
        String activeProfileName = currentActiveProfileName();
        if (activeProfileName == null || activeProfileName.isBlank()) {
            return;
        }

        Map<String, DiscoveredProfile> profilesByName = discoverProfilesByName();
        if (!profilesByName.containsKey(activeProfileName)) {
            return;
        }

        if (isProfileInLineage(activeProfileName, refreshedProfileName, profilesByName)) {
            useProfile(activeProfileName);
        }
    }

    private void refreshActiveProfileIfConfigured() {
        String activeProfileName = currentActiveProfileName();
        if (activeProfileName == null || activeProfileName.isBlank()) {
            return;
        }

        Map<String, DiscoveredProfile> profilesByName = discoverProfilesByName();
        if (!profilesByName.containsKey(activeProfileName)) {
            return;
        }

        useProfile(activeProfileName);
    }

    private boolean isProfileInLineage(
        String profileName,
        String targetProfileName,
        Map<String, DiscoveredProfile> profilesByName
    ) {
        for (DiscoveredProfile discoveredProfile : profileLineageFor(profileName, profilesByName)) {
            if (discoveredProfile.name().equals(targetProfileName)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, DiscoveredProfile> discoverProfilesByName() {
        Map<String, DiscoveredProfile> profilesByName = new HashMap<>();
        Set<String> duplicates = new TreeSet<>();

        for (RepositoryEntry repositoryEntry : repositoryService.load()) {
            Path localPath = Path.of(repositoryEntry.localPath());
            for (ProfileEntry profileEntry : readProfiles(localPath.resolve("repository.json"))) {
                DiscoveredProfile discoveredProfile = new DiscoveredProfile(
                    profileEntry.name(),
                    profileEntry.description(),
                    repositoryEntry,
                    profileEntry.extendsFrom()
                );
                DiscoveredProfile existing = profilesByName.putIfAbsent(profileEntry.name(), discoveredProfile);
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

    private Path profilePathFor(DiscoveredProfile discoveredProfile) {
        return Path.of(discoveredProfile.repositoryEntry().localPath()).resolve(discoveredProfile.name());
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

    private Profile toProfile(DiscoveredProfile discoveredProfile, RepositoryStatus repositoryStatus, boolean active) {
        return new Profile(
            discoveredProfile.name(),
            discoveredProfile.description(),
            discoveredProfile.repositoryEntry().name(),
            discoveredProfile.repositoryEntry().uri(),
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
            .map(entry -> new ProfileEntry(entry.name().trim(), entry.description(), entry.extendsFrom()))
            .toList();
    }

    private Path resolvedProfileDirectory(String profileName) {
        return cacheDirectory().resolve("resolved-profiles").resolve(profileName);
    }

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths
                .sorted(Comparator.reverseOrder())
                .forEach(candidate -> {
                    try {
                        Files.deleteIfExists(candidate);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to delete " + candidate, e);
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + path, e);
        }
    }

    private Path cacheDirectory() {
        String configuredPath = System.getProperty("ocp.cache.dir");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.home"), ".cache", "ocp");
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

    private record DiscoveredProfile(
        String name,
        String description,
        RepositoryEntry repositoryEntry,
        String extendsFrom
    ) {
    }

    private record EffectiveProfileFile(
        Path relativePath,
        Path sourcePath,
        boolean jsonMergeCandidate,
        Object jsonValue,
        boolean mergedJson
    ) {
    }

    private record ResolvedProfileFile(Path relativePath, Path sourcePath) {
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
