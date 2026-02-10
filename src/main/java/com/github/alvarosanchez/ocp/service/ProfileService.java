package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.model.ProfileEntry;
import com.github.alvarosanchez.ocp.model.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.model.RepositoryEntry;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import jakarta.inject.Singleton;

/**
 * Service that discovers available profile names across configured repositories.
 */
@Singleton
public final class ProfileService {

    private final ObjectMapper objectMapper;
    private final RepositoryService repositoryService;

    ProfileService(
        ObjectMapper objectMapper,
        RepositoryService repositoryService
    ) {
        this.objectMapper = objectMapper;
        this.repositoryService = repositoryService;
    }

    /**
     * Lists all known profiles from configured repositories.
     *
     * @return sorted, unique profiles discovered from repository metadata
     */
    public List<ProfileEntry> listProfiles() {
        Set<String> profileNames = new TreeSet<>();
        Set<String> duplicateProfileNames = new TreeSet<>();
        for (RepositoryEntry repositoryEntry : repositoryService.load()) {
            Path localPath = Path.of(repositoryEntry.localPath());
            for (ProfileEntry profileEntry : readProfiles(localPath.resolve("repository.json"))) {
                if (!profileNames.add(profileEntry.name())) {
                    duplicateProfileNames.add(profileEntry.name());
                }
            }
        }
        if (!duplicateProfileNames.isEmpty()) {
            throw new DuplicateProfilesException(duplicateProfileNames);
        }
        List<ProfileEntry> profiles = new ArrayList<>();
        for (String profileName : profileNames) {
            profiles.add(new ProfileEntry(profileName));
        }
        return profiles;
    }

    private List<ProfileEntry> readProfiles(Path profilesFile) {
        if (!Files.exists(profilesFile)) {
            return List.of();
        }
        try {
            String content = Files.readString(profilesFile);
            RepositoryConfigFile configFile = objectMapper.readValue(content, RepositoryConfigFile.class);
            return configFile
                .profiles()
                .stream()
                .filter(entry -> entry.name() != null && !entry.name().isBlank())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read profile metadata from " + profilesFile, e);
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
