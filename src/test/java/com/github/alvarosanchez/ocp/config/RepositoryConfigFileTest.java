package com.github.alvarosanchez.ocp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RepositoryConfigFileTest {

    @Test
    void normalizeLegacyExtendsFromScalarsTrimsNonBlankScalarParents() throws IOException {
        try (ApplicationContext applicationContext = ApplicationContext.run()) {
            ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
            String content = "{\"profiles\":[{\"name\":\"child\",\"extends_from\":\"  base-parent  \"}]}";

            RepositoryConfigFile.LegacyExtendsFromMigration migration = RepositoryConfigFile
                .normalizeLegacyExtendsFromScalars(content, objectMapper);

            assertTrue(migration.migrated());
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(migration.content(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> profile = (Map<String, Object>) ((List<?>) parsed.get("profiles")).getFirst();
            assertEquals(
                List.of("base-parent"),
                profile.get("extends_from")
            );
        }
    }

    @Test
    void normalizeLegacyExtendsFromScalarsRemovesBlankScalarParents() throws IOException {
        try (ApplicationContext applicationContext = ApplicationContext.run()) {
            ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
            String content = "{\"profiles\":[{\"name\":\"child\",\"extends_from\":\"   \"}]}";

            RepositoryConfigFile.LegacyExtendsFromMigration migration = RepositoryConfigFile
                .normalizeLegacyExtendsFromScalars(content, objectMapper);

            assertTrue(migration.migrated());
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(migration.content(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> profile = (Map<String, Object>) ((List<?>) parsed.get("profiles")).getFirst();
            assertFalse(profile.containsKey("extends_from"));
        }
    }


    @Test
    void profileEntryNormalizesBlankDescriptionAndTrimmedScalarParent() {
        RepositoryConfigFile.ProfileEntry entry = new RepositoryConfigFile.ProfileEntry("child", "   ", "  base-parent  ");

        assertNull(entry.description());
        assertEquals(List.of("base-parent"), entry.extendsFromProfiles());
        assertEquals("base-parent", entry.extendsFrom());
    }

    @Test
    void profileEntryRejectsBlankParentNames() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> new RepositoryConfigFile.ProfileEntry("child", null, List.of("base", "   "))
        );

        assertTrue(thrown.getMessage().contains("blank parent profile names"));
    }

    @Test
    void profileEntryRejectsDuplicateParentNamesAfterTrim() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> new RepositoryConfigFile.ProfileEntry("child", null, List.of("base", " base "))
        );

        assertTrue(thrown.getMessage().contains("duplicate parent profile"));
    }

    @Test
    void profileEntryFromJsonRejectsNonStringArrayMembers() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> RepositoryConfigFile.ProfileEntry.fromJson("child", null, List.of("base", 42))
        );

        assertTrue(thrown.getMessage().contains("only string values"));
    }

    @Test
    void profileEntryFromJsonRejectsUnsupportedExtendsFromShape() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> RepositoryConfigFile.ProfileEntry.fromJson("child", null, Map.of("name", "base"))
        );

        assertTrue(thrown.getMessage().contains("must be a string or array of strings"));
    }

}
