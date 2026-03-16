package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.github.alvarosanchez.ocp.command.Cli;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.git.GitProcessExecutor;
import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import com.github.alvarosanchez.ocp.service.OnboardingService;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveAppRepositoryCommitPushTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private ObjectMapper objectMapper;
    private String previousConfigDir;
    private String previousCacheDir;
    private String previousOpenCodeConfigDir;
    private String previousWorkingDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        objectMapper = applicationContext.getBean(ObjectMapper.class);
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
    void commitAndPushSelectedDirtyGitRepositoryOpensDiffReviewBeforePrompt() throws Exception {
        Path localRepository = tempDir.resolve("dirty-repository");
        Files.createDirectories(localRepository.resolve(".git"));
        writeConfig(new RepositoryEntry("dirty-repository", "git@github.com:acme/dirty-repository.git", localRepository.toString()));
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(
            List.of(
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, "diff --git a/opencode.json b/opencode.json\n+foo")
            )
        );
        RepositoryService repositoryService = repositoryService(processExecutor);

        InteractiveApp app = createApp(repositoryService);
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("dirty-repository", localRepository));
        app.testRefreshSelectedRepositoryCommitPushPreview();

        app.testCommitAndPushSelectedRepository();

        assertNull(app.testPrompt());
        CommitConfirmState commitConfirm = app.testCommitConfirm();
        assertNotNull(commitConfirm);
        assertEquals("dirty-repository", commitConfirm.repositoryName());
        assertEquals("diff --git a/opencode.json b/opencode.json\n+foo", commitConfirm.diff());
    }

    @Test
    void selectingRepositoryRefreshesDirtyMarkerStateAlongsideCommitPushAvailability() throws Exception {
        Path localRepository = tempDir.resolve("drifting-repository");
        Files.createDirectories(localRepository.resolve(".git"));
        writeConfig(new RepositoryEntry("drifting-repository", "git@github.com:acme/drifting-repository.git", localRepository.toString()));
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(
            List.of(new StubProcess(0, ""), new StubProcess(0, " M opencode.json\n"))
        );
        RepositoryService repositoryService = repositoryService(processExecutor);

        InteractiveApp app = createApp(repositoryService);
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("drifting-repository", localRepository));

        app.testRefreshSelectedRepositoryCommitPushPreview();

        assertTrue(app.testRepositoryDirtyStateByName().get("drifting-repository").hasLocalChanges());
        assertTrue(app.testIsSelectedRepositoryCommitPushAvailable());
    }

    @Test
    void commitAndPushPromptRunsRepositoryCommitAndPush() throws Exception {
        skipIfNativeImage();
        Path localRepository = tempDir.resolve("dirty-repository");
        Files.createDirectories(localRepository.resolve(".git"));
        writeConfig(new RepositoryEntry("dirty-repository", "git@github.com:acme/dirty-repository.git", localRepository.toString()));
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(
            List.of(
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, "diff --git a/opencode.json b/opencode.json\n+foo"),
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, ""),
                new StubProcess(0, ""),
                new StubProcess(0, ""),
                new StubProcess(0, ""),
                new StubProcess(0, "")
            )
        );
        RepositoryService repositoryService = repositoryService(processExecutor);

        InteractiveApp app = createApp(repositoryService);
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("dirty-repository", localRepository));
        app.testRefreshSelectedRepositoryCommitPushPreview();
        app.testCommitAndPushSelectedRepository();

        CommitConfirmState commitConfirm = app.testCommitConfirm();
        assertNotNull(commitConfirm);
        assertEquals("diff --git a/opencode.json b/opencode.json\n+foo", commitConfirm.diff());

        app.testHandleCommitConfirmKey(dev.tamboui.tui.event.KeyEvent.ofChar('y'));

        PromptState prompt = app.testPrompt();
        assertNotNull(prompt);
        prompt.values.set(0, "chore: save local changes");
        app.testSetPrompt(prompt);
        app.testApplyPrompt();

        waitForCommitConfirmToClear(app);

        assertNull(app.testPrompt());
        assertNull(app.testCommitConfirm());
        assertNotNull(app.testStatus());
        assertEquals(
            List.of(
                List.of("git", "-C", localRepository.toString(), "status", "--porcelain"),
                List.of("git", "-C", localRepository.toString(), "status", "--porcelain"),
                List.of("git", "-c", "color.ui=always", "-C", localRepository.toString(), "diff", "--color=always"),
                List.of("git", "-C", localRepository.toString(), "status", "--porcelain"),
                List.of("git", "-C", localRepository.toString(), "status", "--porcelain"),
                List.of("git", "-C", localRepository.toString(), "add", "-A"),
                List.of(
                    "git",
                    "-C",
                    localRepository.toString(),
                    "-c",
                    "user.email=ocp@local",
                    "-c",
                    "user.name=ocp",
                    "commit",
                    "-m",
                    "chore: save local changes"
                ),
                List.of("git", "-C", localRepository.toString(), "push"),
                List.of("git", "-C", localRepository.toString(), "status", "--porcelain")
            ),
            processExecutor.commands()
        );
    }

    @Test
    void commitAndPushPromptCanBeCancelledAtConfirmDialog() throws Exception {
        skipIfNativeImage();
        Path localRepository = tempDir.resolve("dirty-repository");
        Files.createDirectories(localRepository.resolve(".git"));
        writeConfig(new RepositoryEntry("dirty-repository", "git@github.com:acme/dirty-repository.git", localRepository.toString()));
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(
            List.of(
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, "diff --git a/opencode.json b/opencode.json\n+foo")
            )
        );
        RepositoryService repositoryService = repositoryService(processExecutor);

        InteractiveApp app = createApp(repositoryService);
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("dirty-repository", localRepository));
        app.testRefreshSelectedRepositoryCommitPushPreview();
        app.testCommitAndPushSelectedRepository();

        app.testHandleCommitConfirmKey(dev.tamboui.tui.event.KeyEvent.ofChar('y'));

        PromptState prompt = app.testPrompt();
        assertNotNull(prompt);
        prompt.values.set(0, "chore: save local changes");
        app.testSetPrompt(prompt);
        app.testHandlePromptKey(dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE));

        assertNull(app.testPrompt());
        assertNull(app.testCommitConfirm());
        assertEquals("Cancelled.", app.testStatus());

        assertEquals(
            List.of(
                List.of("git", "-C", localRepository.toString(), "status", "--porcelain"),
                List.of("git", "-C", localRepository.toString(), "status", "--porcelain"),
                List.of("git", "-c", "color.ui=always", "-C", localRepository.toString(), "diff", "--color=always"),
                List.of("git", "-C", localRepository.toString(), "status", "--porcelain")
            ),
            processExecutor.commands()
        );
    }

    @Test
    void commitAndPushReviewCanBeCancelledBeforeCommitMessage() throws Exception {
        skipIfNativeImage();
        Path localRepository = tempDir.resolve("dirty-repository");
        Files.createDirectories(localRepository.resolve(".git"));
        writeConfig(new RepositoryEntry("dirty-repository", "git@github.com:acme/dirty-repository.git", localRepository.toString()));
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(
            List.of(
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, "diff --git a/opencode.json b/opencode.json\n+foo")
            )
        );
        RepositoryService repositoryService = repositoryService(processExecutor);

        InteractiveApp app = createApp(repositoryService);
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("dirty-repository", localRepository));
        app.testRefreshSelectedRepositoryCommitPushPreview();
        app.testCommitAndPushSelectedRepository();

        assertNotNull(app.testCommitConfirm());

        app.testHandleCommitConfirmKey(dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE));

        assertNull(app.testCommitConfirm());
        assertNull(app.testPrompt());
        assertEquals("Commit cancelled.", app.testStatus());
        assertEquals(
            List.of(
                List.of("git", "-C", localRepository.toString(), "status", "--porcelain"),
                List.of("git", "-C", localRepository.toString(), "status", "--porcelain"),
                List.of("git", "-c", "color.ui=always", "-C", localRepository.toString(), "diff", "--color=always"),
                List.of("git", "-C", localRepository.toString(), "status", "--porcelain")
            ),
            processExecutor.commands()
        );
    }

    @Test
    void commitAndPushFromProfileNodeUsesOwningDirtyRepository() throws Exception {
        Path localRepository = tempDir.resolve("dirty-repository");
        Path profilePath = localRepository.resolve("work");
        Files.createDirectories(localRepository.resolve(".git"));
        Files.createDirectories(profilePath);
        writeConfig(new RepositoryEntry("dirty-repository", "git@github.com:acme/dirty-repository.git", localRepository.toString()));
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(
            List.of(
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, "diff --git a/opencode.json b/opencode.json\n+foo")
            )
        );
        RepositoryService repositoryService = repositoryService(processExecutor);

        InteractiveApp app = createApp(repositoryService);
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.profile("dirty-repository", "work", profilePath));

        assertTrue(app.testIsSelectedRepositoryCommitPushAvailable());

        app.testCommitAndPushSelectedRepository();

        CommitConfirmState commitConfirm = app.testCommitConfirm();
        assertNotNull(commitConfirm);
        assertEquals("dirty-repository", commitConfirm.repositoryName());
        assertEquals("diff --git a/opencode.json b/opencode.json\n+foo", commitConfirm.diff());
    }

    @Test
    void commitAndPushSelectedCleanGitRepositoryShowsHelpfulStatus() throws Exception {
        Path localRepository = tempDir.resolve("clean-repository");
        Files.createDirectories(localRepository.resolve(".git"));
        writeConfig(new RepositoryEntry("clean-repository", "git@github.com:acme/clean-repository.git", localRepository.toString()));
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(
            List.of(new StubProcess(0, ""), new StubProcess(0, ""))
        );
        RepositoryService repositoryService = repositoryService(processExecutor);

        InteractiveApp app = createApp(repositoryService);
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("clean-repository", localRepository));
        app.testRefreshSelectedRepositoryCommitPushPreview();

        app.testCommitAndPushSelectedRepository();

        assertNull(app.testPrompt());
        assertTrue(app.testStatus().contains("has no local git changes"));
    }

    @Test
    void commitAndPushSelectedFileBasedRepositoryShowsHelpfulStatus() throws Exception {
        Path localRepository = tempDir.resolve("file-based-repository");
        Files.createDirectories(localRepository);
        writeConfig(new RepositoryEntry("file-based-repository", null, localRepository.toString()));
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(List.of());
        RepositoryService repositoryService = repositoryService(processExecutor);

        InteractiveApp app = createApp(repositoryService);
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("file-based-repository", localRepository));
        app.testRefreshSelectedRepositoryCommitPushPreview();

        app.testCommitAndPushSelectedRepository();

        assertNull(app.testPrompt());
        assertTrue(app.testStatus().contains("is file-based"));
    }

    @Test
    void commitAndPushPreservesInspectionErrorWhenRepositoryStatusCannotBeRead() throws Exception {
        Path localRepository = tempDir.resolve("broken-repository");
        Files.createDirectories(localRepository.resolve(".git"));
        writeConfig(new RepositoryEntry("broken-repository", "git@github.com:acme/broken-repository.git", localRepository.toString()));
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(new IOException("git status failed"));
        RepositoryService repositoryService = repositoryService(processExecutor);

        InteractiveApp app = createApp(repositoryService);
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("broken-repository", localRepository));
        app.testRefreshSelectedRepositoryCommitPushPreview();
        String inspectionStatus = app.testStatus();

        app.testCommitAndPushSelectedRepository();

        assertNull(app.testPrompt());
        assertTrue(app.testRepositoryDirtyStateByName().get("broken-repository").inspectionFailed());
    }

    @Test
    void commitAndPushConvertsDiffFailuresIntoInspectionStatusInsteadOfThrowing() throws Exception {
        Path localRepository = tempDir.resolve("broken-diff-repository");
        Files.createDirectories(localRepository.resolve(".git"));
        writeConfig(new RepositoryEntry("broken-diff-repository", "git@github.com:acme/broken-diff-repository.git", localRepository.toString()));
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(
            List.of(new StubProcess(0, " M opencode.json\n"), new StubProcess(0, " M opencode.json\n"), new StubProcess(1, ""))
        );
        RepositoryService repositoryService = repositoryService(processExecutor);

        InteractiveApp app = createApp(repositoryService);
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("broken-diff-repository", localRepository));
        app.testRefreshSelectedRepositoryCommitPushPreview();

        app.testCommitAndPushSelectedRepository();

        assertNull(app.testPrompt());
        assertNull(app.testCommitConfirm());
        assertNotNull(app.testStatus());
        assertTrue(app.testStatus().contains("Error:") || app.testStatus().contains("Unable to inspect repository `broken-diff-repository` for local git changes"));
    }

    private InteractiveApp createApp(RepositoryService repositoryService) {
        return new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            repositoryService,
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            objectMapper
        );
    }

    private RepositoryService repositoryService(RecordingGitProcessExecutor processExecutor) throws Exception {
        return RepositoryService.forTest(objectMapper, new GitRepositoryClient(processExecutor));
    }

    private void writeConfig(RepositoryEntry repositoryEntry) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(
            configDir.resolve("config.json"),
            objectMapper.writeValueAsString(new OcpConfigFile(new OcpConfigOptions(), List.of(repositoryEntry)))
        );
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void waitForCommitConfirmToClear(InteractiveApp app) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (app.testCommitConfirm() == null) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for commit confirmation to clear");
    }

    private static void skipIfNativeImage() {
        assumeFalse(
            System.getProperty("org.graalvm.nativeimage.imagecode") != null,
            "Interactive KeyEvent tests require dev.tamboui built-in bindings resources not present in native test runtime"
        );
    }

    private static final class RecordingGitProcessExecutor extends GitProcessExecutor {

        private final Deque<Process> processes;
        private final IOException exception;
        private final List<List<String>> commands = new ArrayList<>();

        RecordingGitProcessExecutor(List<Process> processes) {
            this.processes = new ArrayDeque<>(processes);
            this.exception = null;
        }

        RecordingGitProcessExecutor(IOException exception) {
            this.processes = new ArrayDeque<>();
            this.exception = exception;
        }

        @Override
        public Process start(List<String> command) throws IOException {
            commands.add(List.copyOf(command));
            if (exception != null) {
                throw exception;
            }
            if (processes.isEmpty()) {
                throw new IOException("No process available");
            }
            return processes.removeFirst();
        }

        List<List<String>> commands() {
            return List.copyOf(commands);
        }
    }

    private static class StubProcess extends Process {

        private final int exitCode;
        private final String output;

        StubProcess(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
