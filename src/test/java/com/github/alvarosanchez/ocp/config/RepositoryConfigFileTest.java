package com.github.alvarosanchez.ocp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
