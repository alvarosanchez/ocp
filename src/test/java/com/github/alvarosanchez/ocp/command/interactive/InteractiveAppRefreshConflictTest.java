package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.alvarosanchez.ocp.command.Cli;
import com.github.alvarosanchez.ocp.service.OnboardingService;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveAppRefreshConflictTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private String previousConfigDir;
    private String previousCacheDir;
    private String previousOpenCodeConfigDir;
    private String previousWorkingDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        Cli.init();
        previousConfigDir = System.getProperty("ocp.config.dir");
        previousCacheDir = System.getProperty("ocp.cache.dir");
        previousOpenCodeConfigDir = System.getProperty("ocp.opencode.config.dir");
        previousWorkingDir = System.getProperty("ocp.working.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("cache").toString());
        System.setProperty("ocp.opencode.config.dir", tempDir.resolve("opencode").toString());
        System.setProperty("ocp.working.dir", tempDir.resolve("workspace").toString());
    }

    @AfterEach
    void tearDown() {
        applicationContext.close();
        restoreProperty("ocp.config.dir", previousConfigDir);
        restoreProperty("ocp.cache.dir", previousCacheDir);
        restoreProperty("ocp.opencode.config.dir", previousOpenCodeConfigDir);
        restoreProperty("ocp.working.dir", previousWorkingDir);
    }

    @Test
    void handleRefreshConflictRepositoryEscapeCancelsAndClearsPendingRefresh() throws Exception {
        InteractiveApp app = createApp();
        app.testSetPendingRefreshOperation(RefreshOperation.singleRepository("repo-a"));
        app.testSetRefreshConflict(RefreshConflictState.forRepository(repositoryConflict("repo-a", "/tmp/repo-a", "diff")));

        app.testHandleRefreshConflictKey(dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE));

        assertNull(app.testRefreshConflict());
        assertNull(app.testPendingRefreshOperation());
        assertEquals("Refresh cancelled. Local changes were left untouched.", app.testStatus());
    }

    @Test
    void handleRefreshConflictRepositoryOptionThreeCancelsAndClearsPendingRefresh() throws Exception {
        InteractiveApp app = createApp();
        app.testSetPendingRefreshOperation(RefreshOperation.singleRepository("repo-a"));
        app.testSetRefreshConflict(RefreshConflictState.forRepository(repositoryConflict("repo-a", "/tmp/repo-a", "diff")));

        app.testHandleRefreshConflictKey(dev.tamboui.tui.event.KeyEvent.ofChar('3'));

        assertNull(app.testRefreshConflict());
        assertNull(app.testPendingRefreshOperation());
        assertEquals("Refresh cancelled. Local changes were left untouched.", app.testStatus());
    }

    @Test
    void handleRefreshConflictMergedFilesOptionTwoCancelsAndClearsPendingRefresh() throws Exception {
        InteractiveApp app = createApp();
        app.testSetPendingRefreshOperation(RefreshOperation.singleRepository("repo-a"));
        app.testSetRefreshConflict(
            RefreshConflictState.forMergedFiles(
                mergedFilesConflict("work", Path.of("/tmp/opencode"), List.of(Path.of("opencode.json")))
            )
        );

        app.testHandleRefreshConflictKey(dev.tamboui.tui.event.KeyEvent.ofChar('2'));

        assertNull(app.testRefreshConflict());
        assertNull(app.testPendingRefreshOperation());
        assertEquals("Refresh cancelled. Local changes were left untouched.", app.testStatus());
    }

    @Test
    void handleRefreshConflictMergedFilesEscapeCancelsAndClearsPendingRefresh() throws Exception {
        InteractiveApp app = createApp();
        app.testSetPendingRefreshOperation(RefreshOperation.singleRepository("repo-a"));
        app.testSetRefreshConflict(
            RefreshConflictState.forMergedFiles(
                mergedFilesConflict("work", Path.of("/tmp/opencode"), List.of(Path.of("opencode.json")))
            )
        );

        app.testHandleRefreshConflictKey(dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE));

        assertNull(app.testRefreshConflict());
        assertNull(app.testPendingRefreshOperation());
        assertEquals("Refresh cancelled. Local changes were left untouched.", app.testStatus());
    }

    private InteractiveApp createApp() {
        return new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            applicationContext.getBean(ObjectMapper.class)
        );
    }

    private static ProfileService.ProfileRefreshConflictException repositoryConflict(String repositoryName, String repositoryPath, String diff) {
        return ProfileService.testRepositoryRefreshConflict(repositoryName, repositoryPath, diff);
    }

    private static ProfileService.ProfileRefreshUserConfigConflictException mergedFilesConflict(
        String profileName,
        Path targetDirectory,
        List<Path> driftedFiles
    ) {
        return ProfileService.testMergedFilesRefreshConflict(profileName, targetDirectory, driftedFiles);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
