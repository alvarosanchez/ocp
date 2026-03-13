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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
        setPendingRefreshOperation(app, RefreshOperation.singleRepository("repo-a"));
        setRefreshConflict(app, RefreshConflictState.forRepository(repositoryConflict("repo-a", "/tmp/repo-a", "diff")));

        invokeHandleRefreshConflictKey(app, dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE));

        assertNull(readRefreshConflict(app));
        assertNull(readPendingRefreshOperation(app));
        assertEquals("Refresh cancelled. Local changes were left untouched.", readStatus(app));
    }

    @Test
    void handleRefreshConflictRepositoryOptionThreeCancelsAndClearsPendingRefresh() throws Exception {
        InteractiveApp app = createApp();
        setPendingRefreshOperation(app, RefreshOperation.singleRepository("repo-a"));
        setRefreshConflict(app, RefreshConflictState.forRepository(repositoryConflict("repo-a", "/tmp/repo-a", "diff")));

        invokeHandleRefreshConflictKey(app, dev.tamboui.tui.event.KeyEvent.ofChar('3'));

        assertNull(readRefreshConflict(app));
        assertNull(readPendingRefreshOperation(app));
        assertEquals("Refresh cancelled. Local changes were left untouched.", readStatus(app));
    }

    @Test
    void handleRefreshConflictMergedFilesOptionTwoCancelsAndClearsPendingRefresh() throws Exception {
        InteractiveApp app = createApp();
        setPendingRefreshOperation(app, RefreshOperation.singleRepository("repo-a"));
        setRefreshConflict(
            app,
            RefreshConflictState.forMergedFiles(
                mergedFilesConflict("work", Path.of("/tmp/opencode"), List.of(Path.of("opencode.json")))
            )
        );

        invokeHandleRefreshConflictKey(app, dev.tamboui.tui.event.KeyEvent.ofChar('2'));

        assertNull(readRefreshConflict(app));
        assertNull(readPendingRefreshOperation(app));
        assertEquals("Refresh cancelled. Local changes were left untouched.", readStatus(app));
    }

    @Test
    void handleRefreshConflictMergedFilesEscapeCancelsAndClearsPendingRefresh() throws Exception {
        InteractiveApp app = createApp();
        setPendingRefreshOperation(app, RefreshOperation.singleRepository("repo-a"));
        setRefreshConflict(
            app,
            RefreshConflictState.forMergedFiles(
                mergedFilesConflict("work", Path.of("/tmp/opencode"), List.of(Path.of("opencode.json")))
            )
        );

        invokeHandleRefreshConflictKey(app, dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE));

        assertNull(readRefreshConflict(app));
        assertNull(readPendingRefreshOperation(app));
        assertEquals("Refresh cancelled. Local changes were left untouched.", readStatus(app));
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

    private static void invokeHandleRefreshConflictKey(InteractiveApp app, dev.tamboui.tui.event.KeyEvent event) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("handleRefreshConflictKey", dev.tamboui.tui.event.KeyEvent.class);
        method.setAccessible(true);
        method.invoke(app, event);
    }

    private static void setPendingRefreshOperation(InteractiveApp app, RefreshOperation refreshOperation) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("pendingRefreshOperation");
        field.setAccessible(true);
        field.set(app, refreshOperation);
    }

    private static RefreshOperation readPendingRefreshOperation(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("pendingRefreshOperation");
        field.setAccessible(true);
        return (RefreshOperation) field.get(app);
    }

    private static void setRefreshConflict(InteractiveApp app, RefreshConflictState refreshConflict) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("refreshConflict");
        field.setAccessible(true);
        field.set(app, refreshConflict);
    }

    private static RefreshConflictState readRefreshConflict(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("refreshConflict");
        field.setAccessible(true);
        return (RefreshConflictState) field.get(app);
    }

    private static String readStatus(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("status");
        field.setAccessible(true);
        return (String) field.get(app);
    }

    private static ProfileService.ProfileRefreshConflictException repositoryConflict(String repositoryName, String repositoryPath, String diff) {
        try {
            Constructor<ProfileService.ProfileRefreshConflictException> constructor =
                ProfileService.ProfileRefreshConflictException.class.getDeclaredConstructor(String.class, String.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(repositoryName, repositoryPath, diff);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to instantiate repository refresh conflict", e);
        }
    }

    private static ProfileService.ProfileRefreshUserConfigConflictException mergedFilesConflict(
        String profileName,
        Path targetDirectory,
        List<Path> driftedFiles
    ) {
        try {
            Constructor<ProfileService.ProfileRefreshUserConfigConflictException> constructor =
                ProfileService.ProfileRefreshUserConfigConflictException.class.getDeclaredConstructor(String.class, Path.class, List.class);
            constructor.setAccessible(true);
            return constructor.newInstance(profileName, targetDirectory, driftedFiles);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to instantiate merged-files refresh conflict", e);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
