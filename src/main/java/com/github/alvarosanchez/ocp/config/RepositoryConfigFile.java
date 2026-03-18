package com.github.alvarosanchez.ocp.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Root JSON model for repository metadata.
 *
 * @param profiles profiles available in a repository
 */
@Serdeable
public record RepositoryConfigFile(List<ProfileEntry> profiles) {

    public static LegacyExtendsFromMigration normalizeLegacyExtendsFromScalars(String content, ObjectMapper objectMapper)
        throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(content, Map.class);
        boolean migrated = normalizeLegacyExtendsFromScalars(parsed);
        if (!migrated) {
            return new LegacyExtendsFromMigration(false, content);
        }
        return new LegacyExtendsFromMigration(true, objectMapper.writeValueAsString(parsed));
    }

    @SuppressWarnings("unchecked")
    private static boolean normalizeLegacyExtendsFromScalars(Map<String, Object> root) {
        Object profilesObject = root.get("profiles");
        if (!(profilesObject instanceof List<?> profiles)) {
            return false;
        }

        boolean migrated = false;
        for (Object profileObject : profiles) {
            if (!(profileObject instanceof Map<?, ?> rawProfile)) {
                continue;
            }
            Map<String, Object> profile = (Map<String, Object>) rawProfile;
            Object extendsFrom = profile.get("extends_from");
            if (!(extendsFrom instanceof String extendsFromScalar)) {
                continue;
            }
            profile.put("extends_from", new ArrayList<>(List.of(extendsFromScalar)));
            migrated = true;
        }
        return migrated;
    }

    /**
     * Creates a repository configuration instance.
     *
     * @param profiles profiles available in a repository
     */
    public RepositoryConfigFile {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    /**
     * Profile definition entry from profile metadata.
     *
     * @param name profile name
     * @param description optional profile description
     * @param extendsFromProfiles optional ordered parent profile names
     */
    @Serdeable
    public record ProfileEntry(
        String name,
        String description,
        @JsonProperty("extends_from") List<String> extendsFromProfiles
    ) {

        @JsonCreator
        public static ProfileEntry fromJson(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("extends_from") Object extendsFrom
        ) {
            return new ProfileEntry(name, description, normalizeExtendsFromProfiles(extendsFrom));
        }

        public ProfileEntry(String name) {
            this(name, null, List.of());
        }

        public ProfileEntry(String name, String description) {
            this(name, description, List.of());
        }

        public ProfileEntry(String name, String description, String extendsFrom) {
            this(name, description, normalizeLegacyScalarParent(extendsFrom));
        }

        private static List<String> normalizeExtendsFromProfiles(Object extendsFrom) {
            if (extendsFrom == null) {
                return List.of();
            }
            if (extendsFrom instanceof String parentProfileName) {
                return normalizeLegacyScalarParent(parentProfileName);
            }
            if (extendsFrom instanceof List<?> parentProfileNames) {
                List<String> normalizedParentProfileNames = new ArrayList<>(parentProfileNames.size());
                for (Object parentProfileName : parentProfileNames) {
                    if (parentProfileName == null) {
                        normalizedParentProfileNames.add(null);
                        continue;
                    }
                    if (!(parentProfileName instanceof String parentProfileNameString)) {
                        throw new IllegalArgumentException("`extends_from` must contain only string values.");
                    }
                    normalizedParentProfileNames.add(parentProfileNameString);
                }
                return List.copyOf(normalizedParentProfileNames);
            }
            throw new IllegalArgumentException("`extends_from` must be a string or array of strings.");
        }

        private static List<String> normalizeLegacyScalarParent(String parentProfileName) {
            if (parentProfileName == null) {
                return List.of();
            }
            String normalizedParentProfileName = parentProfileName.trim();
            if (normalizedParentProfileName.isBlank()) {
                return List.of();
            }
            return List.of(normalizedParentProfileName);
        }

        public ProfileEntry {
            if (description != null && description.isBlank()) {
                description = null;
            }

            if (extendsFromProfiles == null || extendsFromProfiles.isEmpty()) {
                extendsFromProfiles = List.of();
            } else {
                List<String> normalizedParents = new ArrayList<>(extendsFromProfiles.size());
                Set<String> seenParents = new LinkedHashSet<>();
                String profileLabel = name == null || name.isBlank() ? "<unnamed>" : name.trim();
                for (String parentProfileName : extendsFromProfiles) {
                    String normalizedParentProfileName = parentProfileName == null ? "" : parentProfileName.trim();
                    if (normalizedParentProfileName.isBlank()) {
                        throw new IllegalArgumentException(
                            "Profile `" + profileLabel + "` contains blank parent profile names in `extends_from`."
                        );
                    }
                    if (!seenParents.add(normalizedParentProfileName)) {
                        throw new IllegalArgumentException(
                            "Profile `" + profileLabel + "` contains duplicate parent profile `"
                                + normalizedParentProfileName
                                + "` in `extends_from`."
                        );
                    }
                    normalizedParents.add(normalizedParentProfileName);
                }

                extendsFromProfiles = List.copyOf(normalizedParents);
            }
        }

        public String extendsFrom() {
            return extendsFromProfiles.isEmpty() ? null : extendsFromProfiles.getFirst();
        }
    }

    public record LegacyExtendsFromMigration(boolean migrated, String content) {
    }
}
