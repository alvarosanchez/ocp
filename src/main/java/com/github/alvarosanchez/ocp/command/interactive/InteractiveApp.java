package com.github.alvarosanchez.ocp.command.interactive;

import com.github.alvarosanchez.ocp.command.OcpVersionProvider;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.command.Cli;
import com.github.alvarosanchez.ocp.service.OcpPathSettings;
import com.github.alvarosanchez.ocp.git.GitHubRepositoryClient.RepositoryVisibility;
import com.github.alvarosanchez.ocp.model.Profile;
import com.github.alvarosanchez.ocp.service.OnboardingService;
import com.github.alvarosanchez.ocp.service.PathSegmentValidator;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService.PostCreationCapabilities;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService.PostCreationRequest;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService.PostCreationResult;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import com.github.alvarosanchez.ocp.service.RepositoryService.RepositoryCommitPushPreview;
import com.github.alvarosanchez.ocp.service.RepositoryService.ConfiguredRepository;
import dev.tamboui.style.Color;
import dev.tamboui.text.Text;
import dev.tamboui.text.Line;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.TreeElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.tree.TreeNode;
import io.micronaut.serde.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicReference;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

public final class InteractiveApp extends ToolkitApp {

    private enum ActiveOverlay {
        NONE,
        PROMPT,
        STARTUP_NOTICE,
        REFRESH_CONFLICT,
        COMMIT_CONFIRM
    }

    private static final String TREE_ID = "hierarchy-tree";
    private static final String DETAIL_ID = "detail-pane";
    private static final String EDITOR_ID = "file-editor";
    private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};
    private static final Duration SPLASH_MIN_DURATION = Duration.ofMillis(120);
    private static final int TREE_MAX_DEPTH = 6;
    private static final int TREE_MAX_CHILDREN = 200;
    private static final String REPOSITORY_METADATA_FILE = "repository.json";
    private static final String REFRESH_CANCELLED_MESSAGE = "Refresh cancelled. Local changes were left untouched.";
    private static final String STATUS_SELECT_NODE_FIRST = "Select a repository, profile, or file first.";
    private static final String STATUS_INHERITED_FILE_READ_ONLY = "Inherited file is read-only and cannot be edited.";
    private static final String STATUS_MERGED_PARENT_FILE_READ_ONLY = "Merged parent file preview is read-only and cannot be edited.";
    private static final String STATUS_CONFIG_FILE_UNAVAILABLE = "OCP config file does not exist yet. Add or create a repository first.";
    private static final String STATUS_FILE_DELETE_CANCELLED_MISMATCH = "Delete cancelled: file name mismatch.";
    private static final String ERROR_REPOSITORY_SELECTION_REQUIRED = "Repository selection is required.";
    private static final String STATUS_DELETE_CANCELLED_REPOSITORY_MISMATCH = "Delete cancelled: repository name mismatch.";
    private static final String STATUS_DELETE_CANCELLED_PROFILE_MISMATCH = "Delete cancelled: profile name mismatch.";
    private static final String DEFAULT_COMMIT_MESSAGE = "chore: persist local opencode configuration changes";
    private static final String STARTUP_UPDATE_DIALOG_TITLE = "OCP Notice";
    private static final int PREVIEW_CACHE_MAX_ENTRIES = 128;
    private static final List<TreeShortcutHints.Shortcut> GLOBAL_SHORTCUTS = List.of(
        TreeShortcutHints.Shortcut.TAB_SWITCH_PANE,
        TreeShortcutHints.Shortcut.ADD_EXISTING_REPOSITORY,
        TreeShortcutHints.Shortcut.CREATE_NEW_REPOSITORY,
        TreeShortcutHints.Shortcut.EDIT_OCP_CONFIG,
        TreeShortcutHints.Shortcut.REFRESH_ALL_REPOSITORIES,
        TreeShortcutHints.Shortcut.QUIT
    );
    private static final List<TreeShortcutHints.Shortcut> PROMPT_SHORTCUTS = List.of(
        TreeShortcutHints.Shortcut.ENTER_NEXT_APPLY,
        TreeShortcutHints.Shortcut.BACKSPACE_DELETE,
        TreeShortcutHints.Shortcut.ESC_CANCEL
    );
    private static final List<TreeShortcutHints.Shortcut> PROMPT_OPTION_SHORTCUTS = List.of(
        TreeShortcutHints.Shortcut.UP_DOWN_SELECT,
        TreeShortcutHints.Shortcut.ENTER_NEXT_APPLY,
        TreeShortcutHints.Shortcut.BACKSPACE_CLEAR,
        TreeShortcutHints.Shortcut.ESC_CANCEL
    );
    private static final String SPLASH_LOGO_RESOURCE = "/splash-logo.txt";
    private static final List<String> DEFAULT_SPLASH_LOGO_LINES = List.of(
        "▒▒▒▒▒▒▒▒▒█░░░░░░░░░░█▒▒▒▒▒▒▒▒▒",
        "▒▒█████▒▒█░░█████████▒▒█████▒▒",
        "▒▒▓▓▓▓▓▒▒█░░▓▓▓▓▓▓▓▓█▒▒▓▓▓▓▓▒▒",
        "▒▒▓▓▓▓▓▒▒█░░▓▓▓▓▓▓▓▓█▒▒▓▓▓▓▓▒▒",
        "▒▒▓▓▓▓▓▒▒█░░▓▓▓▓▓▓▓▓█▒▒▓▓▓▓▓▒▒",
        "▒▒▒▒▒▒▒▒▒█░░░░░░░░░░█▒▒▒▒▒▒▒▒▒",
        "█████████████████████▒▒███████"
    );

    private final ProfileService profileService;
    private final RepositoryService repositoryService;
    private final OnboardingService onboardingService;
    private final RepositoryPostCreationService repositoryPostCreationService;
    private final ObjectMapper objectMapper;
    private final String currentVersion = OcpVersionProvider.readVersion();
    private final BatPreviewRenderer batPreviewRenderer;
    private final InteractiveClipboardClient clipboardClient;
    private final RefreshExecutor refreshExecutor;

    private final TreeElement<NodeRef> hierarchyTree = Toolkit.<NodeRef>tree()
        .title("Repositories / Profiles / Files")
        .rounded()
        .borderColor(Color.CYAN)
        .highlightColor(Color.GREEN)
        .highlightSymbol("»")
        .nodeRenderer(this::renderTreeNode)
        .scrollbar()
        .id(TREE_ID)
        .focusable()
        .onKeyEvent(this::handleKeyEvent);

    private final TextAreaState editorState = new TextAreaState();

    private List<Profile> profiles = List.of();
    private List<ConfiguredRepository> repositories = List.of();
    private List<TreeNode<NodeRef>> hierarchyRoots = List.of();
    private Map<String, Profile> profilesByName = Map.of();
    private Map<String, List<String>> profileParentByName = Map.of();
    private Map<String, RepositoryDirtyState> repositoryDirtyStateByName = Map.of();

    private Pane activePane = Pane.TREE;
    private String status = "Ready. Select a node in the hierarchy.";
    private PromptState prompt;
    private boolean splashVisible = true;
    private boolean splashMinimumElapsed;
    private boolean initialDataLoaded;
    private boolean busy;
    private String busyMessage;
    private boolean suppressNextRepositoryCommitPushPreviewRefresh;
    private String startupUpdateNotice;
    private int spinnerIndex;

    private RefreshConflictState refreshConflict;
    private CommitConfirmState commitConfirm;
    private RefreshOperation pendingRefreshOperation;
    private String refreshAllCompletionMessage = "Refreshed all repositories.";

    private NodeRef selectedNode;
    private NodeRef configEditReturnSelection;
    private RepositoryCommitPushPreview selectedRepositoryCommitPushPreview;
    private String selectedFileContent = "";
    private String selectedFilePreviewContent = "";
    private boolean selectedFilePreviewDeepMerged;
    private Text selectedFilePreview = DetailPaneRenderer.plainText("");
    private List<String> splashLogoLines = DEFAULT_SPLASH_LOGO_LINES;
    private boolean editMode;
    private boolean batAvailable;
    private int lastSyncedTreeSelection = Integer.MIN_VALUE;
    private String lastRequestedPreviewKey;
    private final Map<String, Text> previewCacheByKey = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Text> eldest) {
            return size() > PREVIEW_CACHE_MAX_ENTRIES;
        }
    };
    private long repositoryDirtyStateRefreshSequence;
    private int previewScrollOffset;
    private boolean skipNextSelectionSync;
    private NodeRef pendingSelectionRestore;
    private Path pendingCreatedFilePath;

    public InteractiveApp(
        ProfileService profileService,
        RepositoryService repositoryService,
        OnboardingService onboardingService,
        RepositoryPostCreationService repositoryPostCreationService,
        ObjectMapper objectMapper
    ) {
        this(
            profileService,
            repositoryService,
            onboardingService,
            repositoryPostCreationService,
            objectMapper,
            new BatPreviewRenderer(),
            new InteractiveClipboardClient(),
            ProfileService::refreshAllRepositories
        );
    }

    InteractiveApp(
        ProfileService profileService,
        RepositoryService repositoryService,
        OnboardingService onboardingService,
        RepositoryPostCreationService repositoryPostCreationService,
        ObjectMapper objectMapper,
        BatPreviewRenderer batPreviewRenderer
    ) {
        this(
            profileService,
            repositoryService,
            onboardingService,
            repositoryPostCreationService,
            objectMapper,
            batPreviewRenderer,
            new InteractiveClipboardClient(),
            ProfileService::refreshAllRepositories
        );
    }

    InteractiveApp(
        ProfileService profileService,
        RepositoryService repositoryService,
        OnboardingService onboardingService,
        RepositoryPostCreationService repositoryPostCreationService,
        ObjectMapper objectMapper,
        BatPreviewRenderer batPreviewRenderer,
        InteractiveClipboardClient clipboardClient
    ) {
        this(
            profileService,
            repositoryService,
            onboardingService,
            repositoryPostCreationService,
            objectMapper,
            batPreviewRenderer,
            clipboardClient,
            ProfileService::refreshAllRepositories
        );
    }

    InteractiveApp(
        ProfileService profileService,
        RepositoryService repositoryService,
        OnboardingService onboardingService,
        RepositoryPostCreationService repositoryPostCreationService,
        ObjectMapper objectMapper,
        BatPreviewRenderer batPreviewRenderer,
        InteractiveClipboardClient clipboardClient,
        RefreshExecutor refreshExecutor
    ) {
        this.profileService = profileService;
        this.repositoryService = repositoryService;
        this.onboardingService = onboardingService;
        this.repositoryPostCreationService = repositoryPostCreationService;
        this.objectMapper = objectMapper;
        this.batPreviewRenderer = batPreviewRenderer;
        this.clipboardClient = clipboardClient;
        this.refreshExecutor = refreshExecutor;
        startupUpdateNotice = Cli.consumeStartupNotice();
    }

    @Override
    protected void onStart() {
        loadSplashLogoLines();

        if (runner() == null) {
            reloadState();
            maybePromptForStartupOnboarding();
            splashVisible = false;
            return;
        }

        runner().schedule(
            () -> runner().runOnRenderThread(() -> {
                splashMinimumElapsed = true;
                maybeHideSplash();
            }),
            SPLASH_MIN_DURATION
        );

        Thread.ofVirtual().start(this::loadInitialDataInBackground);
        Thread.ofVirtual().start(this::prewarmBatAvailability);
    }

    @Override
    protected Element render() {
        if (splashVisible) {
            return splashScreen();
        }

        applyPendingSelectionRestore();
        syncActivePaneFromFocus();
        hierarchyTree.borderColor(activePane == Pane.TREE ? Color.GREEN : Color.CYAN);
        syncSelectionAndPreview();
        TreeShortcutHints.ShortcutHints treeShortcutHints = TreeShortcutHints.forSelection(
            selectedNode,
            isSelectedRepositoryRefreshable(),
            selectedProfileHasParent(),
            isSelectedRepositoryMigratable(),
            isSelectedRepositoryCommitPushAvailable(),
            editMode
        );

        Element root = column(
            panel(
                row(
                    text("OCP - OpenCode Configuration Profiles").bold().fg(Color.CYAN),
                    spacer(),
                    ShortcutHintRenderer.line(GLOBAL_SHORTCUTS)
                )
            ).rounded().borderColor(Color.CYAN).length(3),
            row(
                hierarchyTree.fill().percent(33),
                renderDetailPane().percent(66)
            ).fill(),
            renderShortcutPanel(treeShortcutHints),
            renderStatusPanel()
        );

        return switch (activeOverlay()) {
            case PROMPT -> column(root, renderPromptDialog());
            case STARTUP_NOTICE -> column(root, renderStartupUpdateDialog());
            case REFRESH_CONFLICT -> column(root, renderRefreshConflictDialog());
            case COMMIT_CONFIRM -> column(root, renderCommitConfirmDialog());
            case NONE -> root;
        };
    }

    private ActiveOverlay activeOverlay() {
        if (prompt != null) {
            return ActiveOverlay.PROMPT;
        }
        if (startupUpdateNotice != null) {
            return ActiveOverlay.STARTUP_NOTICE;
        }
        if (refreshConflict != null) {
            return ActiveOverlay.REFRESH_CONFLICT;
        }
        if (commitConfirm != null) {
            return ActiveOverlay.COMMIT_CONFIRM;
        }
        return ActiveOverlay.NONE;
    }

    private EventResult handleKeyEvent(KeyEvent event) {
        syncActivePaneFromFocus();

        if (splashVisible) {
            if (initialDataLoaded) {
                splashVisible = false;
            }
            return EventResult.HANDLED;
        }

        switch (activeOverlay()) {
            case PROMPT:
                return handlePromptKey(event);
            case STARTUP_NOTICE:
                if (event.isCancel()) {
                    startupUpdateNotice = null;
                    return EventResult.HANDLED;
                }
                if (event.isQuit()) {
                    quit();
                }
                return EventResult.HANDLED;
            case REFRESH_CONFLICT:
                return handleRefreshConflictKey(event);
            case COMMIT_CONFIRM:
                return handleCommitConfirmKey(event);
            case NONE:
                break;
        }

        if (busy) {
            if (event.isQuit()) {
                quit();
            }
            return EventResult.HANDLED;
        }

        if (editMode && selectedNode != null && selectedNode.kind() == NodeKind.FILE) {
            if (isSaveKey(event)) {
                saveSelectedFile();
                return EventResult.HANDLED;
            }
            if (event.isCancel()) {
                exitEditModeToTree();
                return EventResult.HANDLED;
            }
        }

        if (event.isQuit()) {
            quit();
            return EventResult.HANDLED;
        }
        if (handlePreviewScrollKeys(event)) {
            return EventResult.HANDLED;
        }
        if (event.isFocusNext() || event.isFocusPrevious() || event.isKey(dev.tamboui.tui.event.KeyCode.TAB)) {
            togglePaneFocus();
            return EventResult.HANDLED;
        }
        if (event.isChar('r')) {
            refreshSelectedRepository();
            return EventResult.HANDLED;
        }
        if (event.isChar('R')) {
            refreshAllRepositories();
            return EventResult.HANDLED;
        }
        if (event.isChar('a')) {
            prompt = PromptState.multi(
                PromptAction.ADD_REPOSITORY,
                "Add existing repository",
                List.of("Repository URI or local path", "Repository name")
            );
            return EventResult.HANDLED;
        }
        if (event.isChar('m')) {
            migrateSelectedRepository();
            return EventResult.HANDLED;
        }
        if (event.isChar('g')) {
            commitAndPushSelectedRepository();
            return EventResult.HANDLED;
        }
        if (event.isChar('d')) {
            openPromptForSelectedNode(PromptShortcut.DELETE);
            return EventResult.HANDLED;
        }
        if (event.isChar('n')) {
            prompt = PromptState.multi(
                PromptAction.CREATE_REPOSITORY,
                "Create repository",
                List.of("Repository name", "Repository location path", "Initial profile name (optional)")
            );
            return EventResult.HANDLED;
        }
        if (event.isChar('o')) {
            editOcpConfigFile();
            return EventResult.HANDLED;
        }
        if (event.isChar('c')) {
            openPromptForSelectedNode(PromptShortcut.CREATE_PROFILE);
            return EventResult.HANDLED;
        }
        if (event.isChar('f')) {
            promptCreateFile();
            return EventResult.HANDLED;
        }
        if (event.isChar('u')) {
            useSelectedProfile();
            return EventResult.HANDLED;
        }
        if (event.isChar('p')) {
            navigateToParentProfile();
            return EventResult.HANDLED;
        }
        if (event.isChar('y')) {
            copySelectedPath();
            return EventResult.HANDLED;
        }
        if (event.isChar('e')) {
            if (selectedNode != null && selectedNode.kind() == NodeKind.FILE) {
                if (selectedNode.readOnly()) {
                    status = selectedNode.parentOnlyMerged() ? STATUS_MERGED_PARENT_FILE_READ_ONLY : STATUS_INHERITED_FILE_READ_ONLY;
                    return EventResult.HANDLED;
                }
                editMode = true;
                resetEditorCursorToTop();
                if (runner() != null) {
                    runner().focusManager().setFocus(EDITOR_ID);
                    activePane = Pane.DETAIL;
                }
                status = "Editing " + selectedNode.path().getFileName() + ". Ctrl+S to save, Esc to cancel.";
            }
            return EventResult.HANDLED;
        }

        if (isTreeFocused()) {
            return handleTreeNavigation(event);
        }
        return EventResult.UNHANDLED;
    }

    private EventResult handleTreeNavigation(KeyEvent event) {
        if (event.isUp()) {
            hierarchyTree.selectPrevious();
            return EventResult.HANDLED;
        }
        if (event.isDown()) {
            hierarchyTree.selectNext();
            return EventResult.HANDLED;
        }
        if (event.isRight()) {
            hierarchyTree.expandSelected();
            return EventResult.HANDLED;
        }
        if (event.isLeft()) {
            hierarchyTree.collapseSelected();
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private EventResult handlePromptKey(KeyEvent event) {
        if (event.isCancel()) {
            prompt = null;
            status = "Cancelled.";
            return EventResult.HANDLED;
        }
        if (event.isFocusNext() || event.isFocusPrevious() || event.isKey(dev.tamboui.tui.event.KeyCode.TAB)) {
            return EventResult.HANDLED;
        }
        if (prompt.currentFieldHasOptions() && event.isUp()) {
            prompt.selectPreviousOption();
            return EventResult.HANDLED;
        }
        if (prompt.currentFieldHasOptions() && event.isDown()) {
            prompt.selectNextOption();
            return EventResult.HANDLED;
        }
        if (event.isDeleteBackward()) {
            prompt.deleteLast();
            return EventResult.HANDLED;
        }
        if (event.isConfirm()) {
            if (advanceCreateProfilePromptIfNeeded()) {
                return EventResult.HANDLED;
            }
            if (prompt.nextField()) {
                return EventResult.HANDLED;
            }
            applyPrompt();
            return EventResult.HANDLED;
        }

        char character = event.character();
        if (character >= 32 && character < 127) {
            prompt.append(character);
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private void applyPrompt() {
        PromptState currentPrompt = prompt;
        prompt = null;
        try {
            String statusMessage = status;
            switch (currentPrompt.action) {
                case ONBOARD_EXISTING_CONFIG_CONFIRM -> {
                    if (!"yes".equalsIgnoreCase(currentPrompt.values.getFirst())) {
                        statusMessage = "Skipped importing existing OpenCode configuration.";
                        break;
                    }
                    prompt = PromptState.single(
                        PromptAction.ONBOARD_EXISTING_CONFIG_REPOSITORY_NAME,
                        "Create onboarding repository",
                        "Repository name"
                    );
                    status = "Name the imported repository.";
                    return;
                }
                case ONBOARD_EXISTING_CONFIG_REPOSITORY_NAME -> {
                    String normalizedRepositoryName = PathSegmentValidator.requireSinglePathSegment(
                        currentPrompt.values.getFirst(),
                        "Repository name"
                    );
                    prompt = PromptState.single(
                        PromptAction.ONBOARD_EXISTING_CONFIG_PROFILE_NAME,
                        "Create onboarding profile",
                        "Profile name"
                    );
                    prompt.contextRepositoryName = normalizedRepositoryName;
                    status = "Name the imported profile.";
                    return;
                }
                case ONBOARD_EXISTING_CONFIG_PROFILE_NAME -> {
                    String repositoryName = currentPrompt.contextRepositoryName;
                    if (repositoryName == null || repositoryName.isBlank()) {
                        throw new IllegalStateException(ERROR_REPOSITORY_SELECTION_REQUIRED);
                    }
                    OnboardingService.OnboardingResult onboardingResult = onboardingService.onboard(
                        repositoryName,
                        currentPrompt.values.getFirst()
                    );
                    maybeStartPostCreationFlow(
                        PostCreationFlowSource.ONBOARDING,
                        new RepositoryEntry(onboardingResult.repositoryName(), null, onboardingResult.repositoryPath().toString()),
                        onboardingCompletedMessage(onboardingResult),
                        true
                    );
                    reloadState();
                    return;
                }
                case CREATE_PROFILE -> {
                    String repositoryName = currentPrompt.contextRepositoryName;
                    if (repositoryName == null || repositoryName.isBlank()) {
                        throw new IllegalStateException(ERROR_REPOSITORY_SELECTION_REQUIRED);
                    }
                    String profileName = currentPrompt.values.getFirst();
                    List<String> parentProfileNames = selectedParentProfileNames(currentPrompt);
                    runBusyOperation(
                        "Creating profile " + profileName + "...",
                        () -> {
                            profileService.createProfile(profileName, repositoryName, parentProfileNames);
                            if (parentProfileNames.isEmpty()) {
                                return "Created profile " + profileName + " in repository " + repositoryName + ".";
                            }
                            return "Created profile "
                                + profileName
                                + " in repository "
                                + repositoryName
                                + " extending from "
                                + String.join(", ", parentProfileNames)
                                + ".";
                        },
                        () -> selectCreatedProfile(repositoryName, profileName)
                    );
                    return;
                }
                case CREATE_FILE -> {
                    String repositoryName = currentPrompt.contextRepositoryName;
                    String profileName = selectedProfileName();
                    if (profileName == null && selectedNode != null && selectedNode.kind() == NodeKind.FILE) {
                        profileName = selectedNode.profileName();
                    }
                    String fileName = PathSegmentValidator.requireSinglePathSegment(currentPrompt.values.getFirst(), "File name");
                    if (repositoryName == null || repositoryName.isBlank()) {
                        throw new IllegalStateException(ERROR_REPOSITORY_SELECTION_REQUIRED);
                    }
                    if (profileName == null || profileName.isBlank()) {
                        throw new IllegalStateException("Profile selection is required.");
                    }
                    String selectedProfileName = profileName;
                    Path createdFilePath = selectedProfileDirectory().resolve(fileName);
                    runBusyOperation(
                        "Creating file " + fileName + "...",
                        () -> {
                            createFile(currentPrompt);
                            return status;
                        },
                        () -> restoreCreatedFileSelection(repositoryName, selectedProfileName, createdFilePath)
                    );
                    return;
                }
                case ADD_REPOSITORY -> {
                    String repositoryName = currentPrompt.values.get(1);
                    runBusyOperation(
                        "Adding repository " + repositoryName + "...",
                        () -> {
                            RepositoryEntry addedRepository = repositoryService.add(currentPrompt.values.getFirst(), repositoryName);
                            if (addedRepository.uri() == null) {
                                maybeStartPostCreationFlow(
                                    PostCreationFlowSource.ADD_REPOSITORY,
                                    addedRepository,
                                    "Added repository " + repositoryName + ".",
                                    false
                                );
                                return status;
                            }
                            return "Added repository " + repositoryName + ".";
                        },
                        () -> selectCreatedRepository(repositoryName)
                    );
                    return;
                }
                case DELETE_REPOSITORY -> {
                    String confirmation = currentPrompt.values.getFirst();
                    if (!confirmation.equals(currentPrompt.expectedConfirmation)) {
                        statusMessage = STATUS_DELETE_CANCELLED_REPOSITORY_MISMATCH;
                        reloadState();
                        status = statusMessage;
                        return;
                    }
                    runBusyOperation(
                        "Deleting repository " + confirmation + "...",
                        () -> repositoryService.delete(confirmation, false, false),
                        "Deleted repository " + confirmation + ".",
                        this::reloadState
                    );
                    return;
                }
                case DELETE_REPOSITORY_FORCE -> {
                    String confirmation = currentPrompt.values.getFirst();
                    if (!confirmation.equals(currentPrompt.expectedConfirmation)) {
                        statusMessage = STATUS_DELETE_CANCELLED_REPOSITORY_MISMATCH;
                        reloadState();
                        status = statusMessage;
                        return;
                    }
                    runBusyOperation(
                        "Deleting repository " + confirmation + " with local changes...",
                        () -> repositoryService.delete(confirmation, true, false),
                        "Deleted repository " + confirmation + " with local changes.",
                        this::reloadState
                    );
                    return;
                }
                case DELETE_REPOSITORY_FILE_BASED -> {
                    String confirmation = currentPrompt.values.getFirst();
                    if (!confirmation.equals(currentPrompt.expectedConfirmation)) {
                        statusMessage = STATUS_DELETE_CANCELLED_REPOSITORY_MISMATCH;
                        reloadState();
                        status = statusMessage;
                        return;
                    }
                    boolean deleteLocalFolder = "yes".equalsIgnoreCase(currentPrompt.values.get(1));
                    runBusyOperation(
                        deleteLocalFolder
                            ? "Deleting repository " + confirmation + " and local folder..."
                            : "Deleting repository " + confirmation + "...",
                        () -> {
                            repositoryService.delete(confirmation, false, deleteLocalFolder);
                            if (deleteLocalFolder) {
                                return "Deleted repository " + confirmation + " and local folder.";
                            }
                            return "Deleted repository " + confirmation + " (local folder kept).";
                        },
                        this::selectNextAvailableNode
                    );
                    return;
                }
                case COMMIT_AND_PUSH_REPOSITORY -> {
                    String repositoryName = currentPrompt.contextRepositoryName;
                    if (repositoryName == null || repositoryName.isBlank()) {
                        throw new IllegalStateException(ERROR_REPOSITORY_SELECTION_REQUIRED);
                    }
                    suppressNextRepositoryCommitPushPreviewRefresh = true;
                    runBusyOperation(
                        "Committing and pushing repository " + repositoryName + "...",
                        () -> {
                            repositoryService.commitAndPush(repositoryName, currentPrompt.values.getFirst());
                            return "Committed and pushed local changes for repository " + repositoryName + ".";
                        },
                        null
                    );
                    return;
                }
                case DELETE_PROFILE -> {
                    String confirmation = currentPrompt.values.getFirst();
                    String profileName = currentPrompt.expectedConfirmation;
                    if (!confirmation.equals(profileName)) {
                        statusMessage = STATUS_DELETE_CANCELLED_PROFILE_MISMATCH;
                        reloadState();
                        status = statusMessage;
                        return;
                    }
                    String repositoryName = currentPrompt.contextRepositoryName;
                    if (repositoryName == null || repositoryName.isBlank()) {
                        throw new IllegalStateException(ERROR_REPOSITORY_SELECTION_REQUIRED);
                    }
                    runBusyOperation(
                        "Deleting profile " + profileName + "...",
                        () -> profileService.deleteProfile(profileName, repositoryName),
                        "Deleted profile " + profileName + " from repository " + repositoryName + ".",
                        this::reloadState
                    );
                    return;
                }
                case DELETE_FILE -> {
                    deleteFile(currentPrompt);
                    return;
                }
                case CREATE_REPOSITORY -> {
                    String repositoryName = currentPrompt.values.getFirst();
                    String locationPath = currentPrompt.values.get(1);
                    String profileName = currentPrompt.values.get(2).isBlank() ? null : currentPrompt.values.get(2);
                    runBusyOperation(
                        "Creating repository " + repositoryName + "...",
                        () -> {
                            RepositoryEntry createdRepository = repositoryService.createAndAdd(repositoryName, profileName, locationPath);
                            maybeStartPostCreationFlow(
                                PostCreationFlowSource.CREATE_REPOSITORY,
                                createdRepository,
                                "Created and added repository " + repositoryName + ".",
                                true
                            );
                            return status;
                        },
                        () -> selectCreatedRepository(repositoryName)
                    );
                    return;
                }
                case POST_CREATION_GIT_INIT -> {
                    PostCreationFlowState postCreationFlowState = requiredPostCreationFlowState(currentPrompt);
                    postCreationFlowState = postCreationFlowState.withInitializeGit("yes".equalsIgnoreCase(currentPrompt.values.getFirst()));
                    if (shouldPromptForGitHubPublish(postCreationFlowState)) {
                        prompt = postCreationPublishPrompt(postCreationFlowState);
                        status = "Publish " + postCreationFlowState.repositoryName() + " to GitHub?";
                        return;
                    }
                    runBusyPostCreationFlow(postCreationFlowState);
                    return;
                }
                case POST_CREATION_PUBLISH_GITHUB -> {
                    PostCreationFlowState postCreationFlowState = requiredPostCreationFlowState(currentPrompt);
                    postCreationFlowState = postCreationFlowState.withPublishToGitHub("yes".equalsIgnoreCase(currentPrompt.values.getFirst()));
                    if (postCreationFlowState.publishToGitHub()) {
                        prompt = postCreationVisibilityPrompt(postCreationFlowState);
                        status = "Choose GitHub visibility for " + postCreationFlowState.repositoryName() + ".";
                        return;
                    }
                    runBusyPostCreationFlow(postCreationFlowState);
                    return;
                }
                case POST_CREATION_GITHUB_VISIBILITY -> {
                    PostCreationFlowState postCreationFlowState = requiredPostCreationFlowState(currentPrompt);
                    RepositoryVisibility visibility = "public".equalsIgnoreCase(currentPrompt.values.getFirst())
                        ? RepositoryVisibility.PUBLIC
                        : RepositoryVisibility.PRIVATE;
                    postCreationFlowState = postCreationFlowState.withVisibility(visibility);
                    runBusyPostCreationFlow(postCreationFlowState);
                    return;
                }
            }
            reloadState();
            status = statusMessage;
        } catch (RuntimeException e) {
            if (isOnboardingPromptAction(currentPrompt.action)) {
                prompt = currentPrompt;
            }
            reloadState();
            status = "Error: " + e.getMessage();
        }
    }


    private void promptCreateFile() {
        String profileName = selectedProfileName();
        if (profileName == null) {
            status = "Select a profile (or a file inside a profile) first.";
            return;
        }
        String repositoryName = selectedRepositoryName();
        if (repositoryName == null) {
            status = STATUS_SELECT_NODE_FIRST;
            return;
        }
        prompt = PromptState.single(PromptAction.CREATE_FILE, "Create file", "File name");
        prompt.contextRepositoryName = repositoryName;
    }

    private void promptDeleteFile() {
        if (selectedNode == null || selectedNode.kind() != NodeKind.FILE) {
            status = "Select a file first.";
            return;
        }
            if (selectedNode.readOnly()) {
                status = selectedNode.parentOnlyMerged() ? STATUS_MERGED_PARENT_FILE_READ_ONLY : STATUS_INHERITED_FILE_READ_ONLY;
                return;
            }
        prompt = PromptState.single(
            PromptAction.DELETE_FILE,
            "Delete file",
            "Type file name to confirm: " + selectedNode.path().getFileName()
        );
        prompt.expectedConfirmation = selectedNode.path().getFileName().toString();
        prompt.contextRepositoryName = selectedRepositoryName();
    }

    private void createFile(PromptState currentPrompt) {
        String repositoryName = currentPrompt.contextRepositoryName;
        String profileName = selectedProfileName();
        if (profileName == null && selectedNode != null && selectedNode.kind() == NodeKind.FILE) {
            profileName = selectedNode.profileName();
        }
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalStateException(ERROR_REPOSITORY_SELECTION_REQUIRED);
        }
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalStateException("Profile selection is required.");
        }
        String selectedProfileName = profileName;
        String fileName = PathSegmentValidator.requireSinglePathSegment(currentPrompt.values.getFirst(), "File name");
        Path filePath = selectedProfileDirectory().resolve(fileName);
        if (Files.exists(filePath)) {
            throw new IllegalStateException("File " + fileName + " already exists in profile " + selectedProfileName + ".");
        }
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, "");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create file " + filePath + ".", e);
        }
        pendingSelectionRestore = NodeRef.file(repositoryName, selectedProfileName, filePath);
        pendingCreatedFilePath = filePath;
    }

    private void restoreCreatedFileSelection(String repositoryName, String profileName, Path filePath) {
        NodeRef createdFileNode = NodeRef.file(repositoryName, profileName, filePath);
        pendingSelectionRestore = createdFileNode;
        pendingCreatedFilePath = filePath;
        selectedFileContent = "";
        editorState.setText("");
        if (runner() == null) {
            applyPendingSelectionRestore();
        }
    }

    private boolean expandPathToProfile(String repositoryName, String profileName, List<TreeNode<NodeRef>> nodes) {
        for (TreeNode<NodeRef> node : nodes) {
            NodeRef data = node.data();
            if (data == null) {
                continue;
            }
            if (data.kind() == NodeKind.PROFILE
                && repositoryName.equals(data.repositoryName())
                && profileName.equals(data.profileName())) {
                node.expanded(true);
                return true;
            }
            if (expandPathToProfile(repositoryName, profileName, node.children())) {
                node.expanded(true);
                return true;
            }
        }
        return false;
    }

    private void applyPendingSelectionRestore() {
        if (pendingSelectionRestore == null) {
            return;
        }
        NodeRef selection = pendingSelectionRestore;
        if (selection.profileName() != null) {
            expandPathToProfile(selection.repositoryName(), selection.profileName(), hierarchyRoots);
        }
        Integer selectionIndex = findVisibleTreeIndex(node -> isSameSelection(node, selection), hierarchyRoots, new int[] {0});
        if (selectionIndex != null) {
            hierarchyTree.selected(selectionIndex);
        }

        TreeNode<NodeRef> selectedTreeNode = visibleTreeNodeAt(hierarchyTree.selected(), hierarchyRoots, new int[] {0});
        NodeRef treeSelection = selectedTreeNode == null ? null : selectedTreeNode.data();
        if (treeSelection != null && isSameSelection(treeSelection, selection)) {
            pendingSelectionRestore = null;
            selectedNode = selection;
            lastSyncedTreeSelection = hierarchyTree.selected();
            skipNextSelectionSync = true;
            if (pendingCreatedFilePath != null) {
                selectedFileContent = "";
                selectedFilePreviewContent = "";
                selectedFilePreviewDeepMerged = false;
                selectedFilePreview = DetailPaneRenderer.plainText("");
                editorState.setText("");
                editMode = true;
                resetEditorCursorToTop();
                if (runner() != null) {
                    runner().focusManager().setFocus(EDITOR_ID);
                    activePane = Pane.DETAIL;
                } else {
                    activePane = Pane.DETAIL;
                }
                status = "Editing " + pendingCreatedFilePath.getFileName() + ". Ctrl+S to save, Esc to cancel.";
                pendingCreatedFilePath = null;
            }
        }
    }

    private void enterEditModeForFileSelection(String fileContent) {
        if (selectedNode == null || selectedNode.kind() != NodeKind.FILE) {
            return;
        }
        if (selectedNode.readOnly()) {
            status = selectedNode.parentOnlyMerged() ? STATUS_MERGED_PARENT_FILE_READ_ONLY : STATUS_INHERITED_FILE_READ_ONLY;
            return;
        }
        selectedFileContent = fileContent;
        selectedFilePreviewContent = fileContent;
        selectedFilePreviewDeepMerged = false;
        selectedFilePreview = DetailPaneRenderer.plainText(fileContent);
        previewScrollOffset = 0;
        editorState.setText(fileContent);
        editMode = true;
        resetEditorCursorToTop();
        if (runner() != null) {
            runner().focusManager().setFocus(EDITOR_ID);
            activePane = Pane.DETAIL;
        } else {
            activePane = Pane.DETAIL;
        }
        status = "Editing " + selectedNode.path().getFileName() + ". Ctrl+S to save, Esc to cancel.";
    }

    private void deleteFile(PromptState currentPrompt) {
        if (selectedNode == null || selectedNode.kind() != NodeKind.FILE || selectedNode.path() == null) {
            status = "Select a file first.";
            return;
        }
        String confirmation = currentPrompt.values.getFirst();
        String expectedFileName = currentPrompt.expectedConfirmation;
        if (!confirmation.equals(expectedFileName)) {
            reloadState();
            status = STATUS_FILE_DELETE_CANCELLED_MISMATCH;
            return;
        }
        if (selectedNode.readOnly()) {
            status = selectedNode.parentOnlyMerged() ? STATUS_MERGED_PARENT_FILE_READ_ONLY : STATUS_INHERITED_FILE_READ_ONLY;
            return;
        }
        String repositoryName = selectedNode.repositoryName();
        String profileName = selectedNode.profileName();
        Path filePath = selectedNode.path();
        try {
            Files.delete(filePath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete file " + filePath + ".", e);
        }
        editMode = false;
        reloadState();
        expandPathToProfile(repositoryName, profileName, hierarchyRoots);
        if (repositoryName != null && profileName != null) {
            boolean selectedProfile = selectNode(node -> node.kind() == NodeKind.PROFILE
                && repositoryName.equals(node.repositoryName())
                && profileName.equals(node.profileName()));
            if (!selectedProfile) {
                selectedNode = NodeRef.profile(repositoryName, profileName, filePath.getParent());
            } else {
                TreeNode<NodeRef> selectedTreeNode = visibleTreeNodeAt(hierarchyTree.selected(), hierarchyRoots, new int[] {0});
                selectedNode = selectedTreeNode == null || selectedTreeNode.data() == null
                    ? NodeRef.profile(repositoryName, profileName, filePath.getParent())
                    : selectedTreeNode.data();
            }
        }
        status = "Deleted file " + expectedFileName + ".";
    }

    private void selectCreatedRepository(String repositoryName) {
        ConfiguredRepository repository = configuredRepositoryByName(repositoryName);
        if (!selectNode(node -> node.kind() == NodeKind.REPOSITORY && repositoryName.equals(node.repositoryName()))) {
            if (repository != null) {
                selectedNode = NodeRef.repository(repositoryName, Path.of(repository.localPath()));
            }
        }
        if ((selectedNode == null || !repositoryName.equals(selectedNode.repositoryName())) && repository != null) {
            selectedNode = NodeRef.repository(repositoryName, Path.of(repository.localPath()));
        }
        activePane = Pane.TREE;
        if (runner() != null) {
            runner().focusManager().setFocus(TREE_ID);
        }
    }

    private void selectCreatedProfile(String repositoryName, String profileName) {
        if (!selectNode(node -> node.kind() == NodeKind.PROFILE
            && repositoryName.equals(node.repositoryName())
            && profileName.equals(node.profileName()))) {
            ConfiguredRepository repository = configuredRepositoryByName(repositoryName);
            if (repository != null) {
                selectedNode = NodeRef.profile(repositoryName, profileName, Path.of(repository.localPath()).resolve(profileName));
            }
        }
        syncSelectionAndPreview();
        activePane = Pane.TREE;
        if (runner() != null) {
            runner().focusManager().setFocus(TREE_ID);
        }
    }

    private void selectNextAvailableNode() {
        if (hierarchyRoots != null && !hierarchyRoots.isEmpty()) {
            syncSelectionAndPreview();
            return;
        }
        selectedNode = null;
        selectedFileContent = null;
        selectedFilePreviewContent = null;
        selectedFilePreviewDeepMerged = false;
        selectedFilePreview = null;
        activePane = Pane.TREE;
        if (runner() != null) {
            runner().focusManager().setFocus(TREE_ID);
        }
    }

    private ConfiguredRepository configuredRepositoryByName(String repositoryName) {
        for (ConfiguredRepository repository : repositories) {
            if (repository.name().equals(repositoryName)) {
                return repository;
            }
        }
        return null;
    }

    private Path selectedProfileDirectory() {
        if (selectedNode == null || selectedNode.profileName() == null) {
            throw new IllegalStateException("Profile selection is required.");
        }
        if (selectedNode.kind() == NodeKind.PROFILE) {
            return selectedNode.path();
        }
        Path currentPath = selectedNode.path();
        if (currentPath == null) {
            throw new IllegalStateException("Profile selection is required.");
        }
        Path repositoryPath = selectedConfiguredRepository() != null
            ? Path.of(selectedConfiguredRepository().localPath())
            : null;
        if (repositoryPath == null) {
            throw new IllegalStateException(ERROR_REPOSITORY_SELECTION_REQUIRED);
        }
        return repositoryPath.resolve(selectedNode.profileName());
    }

    private void useSelectedProfile() {
        String profileName = selectedProfileName();
        if (profileName == null) {
            status = "Select a profile (or a file inside a profile) first.";
            return;
        }
        runBusyOperation(
            "Applying profile " + profileName + "...",
            () -> profileService.useProfile(profileName),
            "Switched to profile " + profileName + ".",
            null
        );
    }

    private String onboardingCompletedMessage(OnboardingService.OnboardingResult onboardingResult) {
        String message = "Imported existing OpenCode configuration as profile " + onboardingResult.profileName();
        if (onboardingResult.switchResult().hasBackups()) {
            return message + " and backed up existing files to " + onboardingResult.switchResult().backupDirectory() + ".";
        }
        return message + ".";
    }

    private void maybeStartPostCreationFlow(
        PostCreationFlowSource source,
        RepositoryEntry repositoryEntry,
        String successMessage,
        boolean defaultInitializeGit
    ) {
        try {
            Path repositoryPath = Path.of(repositoryEntry.localPath());
            PostCreationCapabilities capabilities = repositoryPostCreationService.capabilities(repositoryPath);
            if (capabilities.hasOriginRemote()) {
                String originUri = repositoryPostCreationService.persistExistingOrigin(repositoryEntry.name(), repositoryPath);
                status = successMessage + " Saved origin URI " + originUri + ".";
                return;
            }
            PostCreationFlowState postCreationFlowState = new PostCreationFlowState(
                source,
                repositoryEntry.name(),
                repositoryPath,
                successMessage,
                !capabilities.gitInitialized(),
                defaultInitializeGit,
                capabilities.canPublishWithGh(),
                false,
                RepositoryVisibility.PRIVATE
            );

            if (postCreationFlowState.canInitializeGit()) {
                prompt = postCreationGitInitPrompt(postCreationFlowState);
                status = "Configure git setup for " + postCreationFlowState.repositoryName() + ".";
                return;
            }
            if (shouldPromptForGitHubPublish(postCreationFlowState)) {
                prompt = postCreationPublishPrompt(postCreationFlowState);
                status = "Publish " + postCreationFlowState.repositoryName() + " to GitHub?";
                return;
            }
            status = successMessage;
        } catch (RuntimeException e) {
            status = successMessage + " (note: post-creation setup failed and was skipped).";
        }
    }

    private void migrateSelectedRepository() {
        if (selectedNode == null || selectedNode.kind() != NodeKind.REPOSITORY) {
            status = STATUS_SELECT_NODE_FIRST;
            return;
        }
        String repositoryName = selectedRepositoryName();
        if (repositoryName == null) {
            status = STATUS_SELECT_NODE_FIRST;
            return;
        }
        ConfiguredRepository repository = selectedConfiguredRepository();
        if (repository == null) {
            status = STATUS_SELECT_NODE_FIRST;
            return;
        }
        if (repository.isGitBacked()) {
            status = "Repository " + repositoryName + " is already git-backed.";
            return;
        }
        maybeStartPostCreationFlow(
            PostCreationFlowSource.MIGRATE_REPOSITORY,
            new RepositoryEntry(repository.name(), repository.uri(), repository.localPath()),
            "Migration ready for repository " + repositoryName + ".",
            false
        );
        reloadState();
    }

    private void commitAndPushSelectedRepository() {
        String repositoryName = selectedRepositoryName();
        if (repositoryName == null) {
            status = STATUS_SELECT_NODE_FIRST;
            return;
        }
        ConfiguredRepository repository = selectedConfiguredRepository();
        if (repository == null) {
            status = STATUS_SELECT_NODE_FIRST;
            return;
        }
        RepositoryDirtyState dirtyState = repositoryDirtyStateByName.get(repositoryName);
        if (dirtyState != null && dirtyState.inspectionFailed()) {
            status = "Unable to inspect repository " + repositoryName + " for local git changes.";
            return;
        }
        if (!isSelectedRepositoryCommitPushAvailable()) {
            if (!repository.isGitBacked()) {
                status = "Repository " + repositoryName + " is file-based; nothing to commit and push.";
                return;
            }
            status = "Repository " + repositoryName + " has no local git changes to commit and push.";
            return;
        }
        final String[] diffHolder = new String[1];
        runBusyOperation(
            "Inspecting local changes for " + repositoryName + "...",
            () -> {
                diffHolder[0] = repositoryService.getLocalDiff(repositoryName);
                return "Review local changes for " + repositoryName + ".";
            },
            () -> commitConfirm = new CommitConfirmState(repositoryName, diffHolder[0])
        );
    }

    private PostCreationFlowState requiredPostCreationFlowState(PromptState promptState) {
        if (promptState.postCreationFlowState == null) {
            throw new IllegalStateException("Post-creation prompt state is missing.");
        }
        return promptState.postCreationFlowState;
    }

    private boolean shouldPromptForGitHubPublish(PostCreationFlowState postCreationFlowState) {
        return postCreationFlowState.canPublishToGitHub()
            && (!postCreationFlowState.canInitializeGit() || postCreationFlowState.initializeGit());
    }

    private PromptState postCreationGitInitPrompt(PostCreationFlowState postCreationFlowState) {
        PromptState nextPrompt = PromptState.multiWithOptions(
            PromptAction.POST_CREATION_GIT_INIT,
            "Initialize git repository",
            List.of("Initialize git and create initial commit?"),
            List.of(List.of("yes", "no"))
        );
        nextPrompt.postCreationFlowState = postCreationFlowState;
        if (!postCreationFlowState.initializeGit()) {
            nextPrompt.values.set(0, "no");
        }
        return nextPrompt;
    }

    private PromptState postCreationPublishPrompt(PostCreationFlowState postCreationFlowState) {
        PromptState nextPrompt = PromptState.multiWithOptions(
            PromptAction.POST_CREATION_PUBLISH_GITHUB,
            "Publish repository to GitHub",
            List.of("Publish with gh and set origin?"),
            List.of(List.of("no", "yes"))
        );
        nextPrompt.postCreationFlowState = postCreationFlowState;
        return nextPrompt;
    }

    private PromptState postCreationVisibilityPrompt(PostCreationFlowState postCreationFlowState) {
        PromptState nextPrompt = PromptState.multiWithOptions(
            PromptAction.POST_CREATION_GITHUB_VISIBILITY,
            "GitHub visibility",
            List.of("Repository visibility"),
            List.of(List.of("private", "public"))
        );
        nextPrompt.postCreationFlowState = postCreationFlowState;
        if (postCreationFlowState.visibility() == RepositoryVisibility.PUBLIC) {
            nextPrompt.values.set(0, "public");
        }
        return nextPrompt;
    }

    private void runBusyPostCreationFlow(PostCreationFlowState postCreationFlowState) {
        runBusyOperation(
            "Configuring repository " + postCreationFlowState.repositoryName() + "...",
            () -> applyPostCreationFlow(postCreationFlowState),
            () -> selectCreatedRepository(postCreationFlowState.repositoryName())
        );
    }

    private String applyPostCreationFlow(PostCreationFlowState postCreationFlowState) {
        PostCreationResult result = repositoryPostCreationService.run(
            postCreationFlowState.repositoryName(),
            postCreationFlowState.repositoryPath(),
            new PostCreationRequest(
                postCreationFlowState.initializeGit(),
                postCreationFlowState.publishToGitHub(),
                postCreationFlowState.visibility()
            )
        );

        String message = postCreationFlowState.successMessage();
        if (result.initializedGit()) {
            message += " Initialized git repository.";
            if (result.createdInitialCommit()) {
                message += " Created an initial commit.";
            }
        }
        if (result.publishedToGitHub()) {
            message += " Published to GitHub and saved origin URI " + result.persistedRepositoryUri() + ".";
        }
        return message;
    }

    private void refreshSelectedRepository() {
        String repositoryName = selectedRepositoryName();
        if (repositoryName == null) {
            status = STATUS_SELECT_NODE_FIRST;
            return;
        }
        if (!isRepositoryRefreshable(repositoryName)) {
            status = "Repository " + repositoryName + " is file-based; nothing to refresh.";
            return;
        }
        pendingRefreshOperation = RefreshOperation.singleRepository(repositoryName);
        attemptPendingRefresh();
    }

    private void refreshAllRepositories() {
        int refreshableRepositories = countRefreshableRepositories();
        if (refreshableRepositories == 0) {
            status = "All configured repositories are file-based; nothing to refresh.";
            return;
        }
        int skippedFileBased = repositories.size() - refreshableRepositories;
        refreshAllCompletionMessage = skippedFileBased > 0
            ? "Refreshed git-backed repositories. " + skippedFileBasedRepositoriesMessage(skippedFileBased)
            : "Refreshed all repositories.";
        pendingRefreshOperation = RefreshOperation.allRepositories();
        attemptPendingRefresh();
    }

    static String skippedFileBasedRepositoriesMessage(int skippedFileBased) {
        String noun = skippedFileBased == 1 ? "repository" : "repositories";
        return "Skipped " + skippedFileBased + " file-based " + noun + ".";
    }

    private static PromptState buildCreateProfilePrompt(String repositoryName, List<String> resolvableProfileNames) {
        List<String> parentOptions = buildCreateProfileParentOptions(resolvableProfileNames, List.of());
        PromptState prompt = PromptState.multiWithOptions(
            PromptAction.CREATE_PROFILE,
            "Create profile",
            new ArrayList<>(List.of("Profile name", "Extends from profile (optional)")),
            new ArrayList<>(List.of(List.of(), parentOptions))
        );
        prompt.contextRepositoryName = repositoryName;
        prompt.baseParentOptions = List.copyOf(resolvableProfileNames);
        return prompt;
    }


    private boolean advanceCreateProfilePromptIfNeeded() {
        if (prompt == null || prompt.action != PromptAction.CREATE_PROFILE) {
            return false;
        }
        if (prompt.currentField != prompt.labels.size() - 1) {
            return false;
        }
        if (!prompt.currentFieldHasOptions()) {
            return false;
        }
        String selectedParentProfileName = prompt.currentValue();
        if (selectedParentProfileName == null || selectedParentProfileName.isBlank()) {
            return false;
        }
        List<String> selectedParentProfileNames = selectedParentProfileNames(prompt);
        List<String> nextParentOptions = buildCreateProfileParentOptions(prompt.baseParentOptions, selectedParentProfileNames);
        prompt.appendOptionField("Extends from profile (optional)", nextParentOptions);
        prompt.currentField = prompt.labels.size() - 1;
        return true;
    }

    private static List<String> selectedParentProfileNames(PromptState prompt) {
        if (prompt.values.size() <= 1) {
            return List.of();
        }
        List<String> selectedParentProfileNames = new ArrayList<>();
        for (int index = 1; index < prompt.values.size(); index++) {
            String parentProfileName = prompt.values.get(index);
            if (parentProfileName == null || parentProfileName.isBlank()) {
                continue;
            }
            selectedParentProfileNames.add(parentProfileName);
        }
        return List.copyOf(selectedParentProfileNames);
    }

    private static List<String> buildCreateProfileParentOptions(List<String> resolvableProfileNames, List<String> selectedParentProfileNames) {
        List<String> parentOptions = new ArrayList<>();
        parentOptions.add("");
        for (String profileName : resolvableProfileNames) {
            if (!selectedParentProfileNames.contains(profileName)) {
                parentOptions.add(profileName);
            }
        }
        return List.copyOf(parentOptions);
    }

    private static PromptState buildDeleteRepositoryPrompt(String repositoryName, RepositoryService.RepositoryDeletePreview deletePreview) {
        if (!deletePreview.gitBacked()) {
            PromptState prompt = PromptState.multiWithOptions(
                PromptAction.DELETE_REPOSITORY_FILE_BASED,
                "Delete file-based repository",
                List.of(
                    "Type repository name to confirm: " + repositoryName,
                    "Delete local folder as well?"
                ),
                List.of(List.of(), List.of("no", "yes"))
            );
            prompt.expectedConfirmation = repositoryName;
            return prompt;
        }
        if (deletePreview.hasLocalChanges()) {
            PromptState prompt = PromptState.single(
                PromptAction.DELETE_REPOSITORY_FORCE,
                "Delete repository (local changes detected)",
                "Type repository name to force delete: " + repositoryName
            );
            prompt.expectedConfirmation = repositoryName;
            return prompt;
        }
        PromptState prompt = PromptState.single(
            PromptAction.DELETE_REPOSITORY,
            "Delete repository",
            "Type repository name to confirm: " + repositoryName
        );
        prompt.expectedConfirmation = repositoryName;
        return prompt;
    }

    private void openPromptForSelectedNode(PromptShortcut shortcut) {
        switch (shortcut) {
            case CREATE_PROFILE -> openCreateProfilePromptForCurrentSelection();
            case DELETE -> openDeletePromptForCurrentSelection();
        }
    }

    void testOpenCreateProfilePromptForSelectedNode() {
        openPromptForSelectedNode(PromptShortcut.CREATE_PROFILE);
    }

    void testOpenDeletePromptForSelectedNode() {
        openPromptForSelectedNode(PromptShortcut.DELETE);
    }

    private enum PromptShortcut {
        CREATE_PROFILE,
        DELETE
    }

    private void openCreateProfilePromptForCurrentSelection() {
        String repositoryName = selectedRepositoryName();
        if (repositoryName == null) {
            status = STATUS_SELECT_NODE_FIRST;
            return;
        }
        try {
            prompt = buildCreateProfilePrompt(repositoryName, profileService.listResolvableProfileNames());
        } catch (RuntimeException e) {
            status = "Error: " + e.getMessage();
        }
    }

    private void openDeletePromptForCurrentSelection() {
        if (selectedNode == null) {
            status = STATUS_SELECT_NODE_FIRST;
            return;
        }
        if (selectedNode.kind() == NodeKind.FILE) {
            promptDeleteFile();
            return;
        }
        if (selectedNode.kind() == NodeKind.REPOSITORY) {
            String repositoryName = selectedRepositoryName();
            if (repositoryName == null) {
                status = STATUS_SELECT_NODE_FIRST;
                return;
            }
            try {
                prompt = buildDeleteRepositoryPrompt(repositoryName, repositoryService.inspectDelete(repositoryName));
            } catch (RuntimeException e) {
                status = "Error: " + e.getMessage();
            }
            return;
        }
        String profileName = selectedProfileName();
        String repositoryName = selectedRepositoryName();
        if (profileName == null || repositoryName == null) {
            status = STATUS_SELECT_NODE_FIRST;
            return;
        }
        prompt = PromptState.single(
            PromptAction.DELETE_PROFILE,
            "Delete profile",
            "Type profile name to confirm: " + profileName
        );
        prompt.expectedConfirmation = profileName;
        prompt.contextRepositoryName = repositoryName;
    }

    @FunctionalInterface
    interface RefreshExecutor {
        void refreshAllRepositories(ProfileService profileService);
    }

    private void attemptPendingRefresh() {
        if (pendingRefreshOperation == null) {
            return;
        }
        if (pendingRefreshOperation.scope() == RefreshScope.SINGLE_REPOSITORY) {
            String repositoryName = pendingRefreshOperation.repositoryName();
            runBusyOperation(
                "Refreshing repository " + repositoryName + "...",
                () -> profileService.refreshRepository(repositoryName),
                "Refreshed repository " + repositoryName + ".",
                () -> pendingRefreshOperation = null
            );
            return;
        }
        runBusyOperation(
            "Refreshing all repositories...",
            () -> refreshExecutor.refreshAllRepositories(profileService),
            refreshAllCompletionMessage,
            () -> {
                pendingRefreshOperation = null;
                refreshAllCompletionMessage = "Refreshed all repositories.";
            }
        );
    }

    private int countRefreshableRepositories() {
        int refreshable = 0;
        for (ConfiguredRepository repository : repositories) {
            if (repository.isGitBacked()) {
                refreshable++;
            }
        }
        return refreshable;
    }

    private boolean isSelectedRepositoryRefreshable() {
        String repositoryName = selectedRepositoryName();
        return repositoryName != null && isRepositoryRefreshable(repositoryName);
    }

    private boolean isSelectedRepositoryMigratable() {
        if (selectedNode == null || selectedNode.kind() != NodeKind.REPOSITORY) {
            return false;
        }
        ConfiguredRepository repository = selectedConfiguredRepository();
        return repository != null && !repository.isGitBacked();
    }

    private boolean isSelectedRepositoryCommitPushAvailable() {
        String repositoryName = selectedRepositoryName();
        if (repositoryName == null) {
            return false;
        }
        RepositoryDirtyState dirtyState = repositoryDirtyStateByName.get(repositoryName);
        return dirtyState != null && dirtyState.hasLocalChanges() && !dirtyState.inspectionFailed();
    }

    private boolean isRepositoryRefreshable(String repositoryName) {
        for (ConfiguredRepository repository : repositories) {
            if (repository.name().equals(repositoryName)) {
                return repository.isGitBacked();
            }
        }
        return false;
    }

    private void reloadState() {
        int previousSelection = hierarchyTree.selected();

        try {
            profiles = profileService.getAllProfiles();
        } catch (RuntimeException e) {
            profiles = List.of();
            status = "Error loading profiles: " + e.getMessage();
        }

        try {
            repositories = repositoryService.listConfiguredRepositories();
        } catch (RuntimeException e) {
            repositories = List.of();
            status = "Error loading repositories: " + e.getMessage();
        }
        repositoryDirtyStateByName = loadImmediateRepositoryDirtyStateByName(repositories);

        Map<String, Profile> byName = new HashMap<>();
        for (Profile profile : profiles) {
            byName.put(profileKey(profile.repositoryName(), profile.name()), profile);
        }
        profilesByName = Map.copyOf(byName);
        profileParentByName = loadProfileParentByName();

        List<TreeNode<NodeRef>> roots;
        try {
            roots = buildHierarchyTree();
        } catch (RuntimeException e) {
            roots = List.of();
            status = "Error building hierarchy tree: " + e.getMessage();
        }
        hierarchyRoots = roots;
        hierarchyTree.roots(roots.toArray(TreeNode[]::new));
        hierarchyTree.selected(Math.max(0, previousSelection));
        lastSyncedTreeSelection = Integer.MIN_VALUE;
        if (pendingSelectionRestore == null) {
            syncSelectionAndPreview();
        }
        refreshRepositoryDirtyStateByNameInBackground(repositories);
    }

    private List<TreeNode<NodeRef>> buildHierarchyTree() {
        return HierarchyTreeBuilder.buildHierarchyTree(
            repositories,
            profiles,
            profileParentByName,
            TREE_MAX_DEPTH,
            TREE_MAX_CHILDREN
        );
    }

    private Map<String, List<String>> loadProfileParentByName() {
        Map<String, List<String>> parentByName = new HashMap<>();
        for (ConfiguredRepository repository : repositories) {
            Path metadataFile = Path.of(repository.localPath()).resolve(REPOSITORY_METADATA_FILE);
            if (!Files.exists(metadataFile) || !Files.isRegularFile(metadataFile)) {
                continue;
            }
            try {
                RepositoryConfigFile repositoryConfig = objectMapper.readValue(Files.readString(metadataFile), RepositoryConfigFile.class);
                parentByName.putAll(profileParentByName(repository.name(), repositoryConfig));
            } catch (IOException | RuntimeException e) {
                status = "Error loading metadata from " + metadataFile + ": " + e.getMessage();
            }
        }
        return Map.copyOf(parentByName);
    }

    static Map<String, List<String>> profileParentByName(String repositoryName, RepositoryConfigFile repositoryConfig) {
        Map<String, List<String>> parentByName = new HashMap<>();
        for (RepositoryConfigFile.ProfileEntry profileEntry : repositoryConfig.profiles()) {
            String profileName = profileEntry.name() == null ? "" : profileEntry.name().trim();
            List<String> parentProfileNames = profileEntry.extendsFromProfiles();
            if (profileName.isBlank() || parentProfileNames.isEmpty()) {
                continue;
            }
            parentByName.put(profileKey(repositoryName, profileName), parentProfileNames);
        }
        return Map.copyOf(parentByName);
    }

    private StyledElement<?> renderTreeNode(TreeNode<NodeRef> node) {
        return HierarchyTreeBuilder.renderTreeNode(node, profilesByName, profileParentByName, repositoryDirtyStateByName);
    }

    private void syncSelectionAndPreview() {
        if (skipNextSelectionSync) {
            skipNextSelectionSync = false;
            lastSyncedTreeSelection = hierarchyTree.selected();
            return;
        }
        int currentSelection = hierarchyTree.selected();
        TreeNode<NodeRef> selectedTreeNode = hierarchyTree.selectedNode();
        NodeRef nextSelectedNode = selectedTreeNode == null ? null : selectedTreeNode.data();
        if (editMode
            && selectedNode != null
            && selectedNode.kind() == NodeKind.FILE
            && currentSelection == lastSyncedTreeSelection
            && nextSelectedNode == null) {
            return;
        }
        if (editMode
            && selectedNode != null
            && selectedNode.kind() == NodeKind.FILE
            && isSameSelection(nextSelectedNode, selectedNode)) {
            lastSyncedTreeSelection = currentSelection;
            return;
        }
        if (currentSelection == lastSyncedTreeSelection && isSameSelection(nextSelectedNode, selectedNode)) {
            return;
        }
        if (currentSelection == lastSyncedTreeSelection
            && selectedNode != null
            && selectedNode.path() != null
            && isOcpConfigFile(selectedNode.path())) {
            return;
        }
        lastSyncedTreeSelection = currentSelection;

        selectedNode = nextSelectedNode;
        lastRequestedPreviewKey = null;
        editMode = false;
        previewScrollOffset = 0;

        if (selectedNode == null || selectedNode.kind() != NodeKind.FILE || selectedNode.path() == null) {
            refreshSelectedRepositoryCommitPushPreview();
            selectedFileContent = "";
            selectedFilePreviewContent = "";
            selectedFilePreviewDeepMerged = false;
            selectedFilePreview = DetailPaneRenderer.plainText("");
            editorState.setText("");
            return;
        }

        refreshSelectedRepositoryCommitPushPreview();

        refreshSelectedFilePreview();
        resetEditorCursorToTop();
    }

    private boolean isSameSelection(NodeRef left, NodeRef right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.kind() == right.kind()
            && Objects.equals(left.repositoryName(), right.repositoryName())
            && Objects.equals(left.profileName(), right.profileName())
            && Objects.equals(left.path(), right.path());
    }

    private StyledElement<?> renderDetailPane() {
        return DetailPaneRenderer.renderDetailPane(
            selectedNode,
            isSelectedRepositoryRefreshable(),
            isSelectedRepositoryMigratable(),
            isSelectedRepositoryCommitPushAvailable(),
            selectedProfileHasParent(),
            editMode,
            selectedRepositoryHasLocalChanges(),
            selectedRepositoryInspectionFailed(),
            profilesByName,
            profileParentByName,
            selectedFilePreview,
            previewScrollOffset,
            editorState,
            DETAIL_ID,
            EDITOR_ID,
            this::handleKeyEvent,
            this::handlePreviewKeyEvent
        );
    }

    private EventResult handlePreviewKeyEvent(KeyEvent event) {
        if (selectedNode != null && selectedNode.kind() == NodeKind.FILE && !editMode
            && (event.isUp() || event.isDown() || event.isPageUp() || event.isPageDown() || event.isHome() || event.isEnd())) {
            applyPreviewScroll(event);
            return EventResult.HANDLED;
        }
        if (event.isLeft() || event.isRight()) {
            return EventResult.UNHANDLED;
        }
        return handleKeyEvent(event);
    }

    private boolean selectedRepositoryHasLocalChanges() {
        String repositoryName = selectedRepositoryName();
        if (repositoryName == null) {
            return false;
        }
        RepositoryDirtyState dirtyState = repositoryDirtyStateByName.get(repositoryName);
        return dirtyState != null && dirtyState.hasLocalChanges();
    }

    private boolean selectedRepositoryInspectionFailed() {
        String repositoryName = selectedRepositoryName();
        if (repositoryName == null) {
            return false;
        }
        RepositoryDirtyState dirtyState = repositoryDirtyStateByName.get(repositoryName);
        return dirtyState != null && dirtyState.inspectionFailed();
    }

    private boolean handlePreviewScrollKeys(KeyEvent event) {
        if (!isDetailFocused() || editMode || selectedNode == null || selectedNode.kind() != NodeKind.FILE) {
            return false;
        }

        return applyPreviewScroll(event);
    }

    private boolean applyPreviewScroll(KeyEvent event) {
        if (selectedNode == null || selectedNode.kind() != NodeKind.FILE) {
            return false;
        }

        int maxOffset = Math.max(0, selectedFilePreview.lines().size() - 1);
        int nextOffset;
        if (event.isUp()) {
            nextOffset = Math.max(0, previewScrollOffset - 1);
        } else if (event.isDown()) {
            nextOffset = Math.min(maxOffset, previewScrollOffset + 1);
        } else if (event.isPageUp()) {
            nextOffset = Math.max(0, previewScrollOffset - 20);
        } else if (event.isPageDown()) {
            nextOffset = Math.min(maxOffset, previewScrollOffset + 20);
        } else if (event.isHome()) {
            nextOffset = 0;
        } else if (event.isEnd()) {
            nextOffset = maxOffset;
        } else {
            return false;
        }

        previewScrollOffset = nextOffset;
        return true;
    }

    private boolean isTreeFocused() {
        return TREE_ID.equals(focusedElementId());
    }

    private boolean isDetailFocused() {
        String focusedId = focusedElementId();
        return DETAIL_ID.equals(focusedId) || EDITOR_ID.equals(focusedId);
    }

    private String focusedElementId() {
        if (runner() == null) {
            return null;
        }
        return runner().focusManager().focusedId();
    }

    private void requestBatPreview(Path filePath, String contentSnapshot, boolean deepMergedPreview) {
        if (filePath == null || !batAvailable) {
            return;
        }
        String previewKey = buildPreviewKey(filePath, contentSnapshot, deepMergedPreview);
        if (Objects.equals(previewKey, lastRequestedPreviewKey)) {
            return;
        }
        Text cachedPreview = cachedPreview(previewKey);
        if (cachedPreview != null) {
            lastRequestedPreviewKey = previewKey;
            applyBatPreview(filePath, previewKey, cachedPreview);
            return;
        }
        lastRequestedPreviewKey = previewKey;
        if (runner() == null) {
            Text parsed = deepMergedPreview
                ? batPreviewRenderer.highlight(filePath, contentSnapshot)
                : batPreviewRenderer.highlight(filePath);
            if (parsed == null) {
                lastRequestedPreviewKey = null;
                return;
            }
            if (hasVisiblePreviewContent(parsed)) {
                cachePreview(previewKey, parsed);
            }
            applyBatPreview(filePath, previewKey, parsed);
            return;
        }
        Thread thread = Thread.ofPlatform().daemon(true).unstarted(() -> {
            Text parsed = deepMergedPreview
                ? batPreviewRenderer.highlight(filePath, contentSnapshot)
                : batPreviewRenderer.highlight(filePath);
            if (parsed == null) {
                if (runner() == null) {
                    lastRequestedPreviewKey = null;
                } else {
                    runner().runOnRenderThread(() -> {
                        if (Objects.equals(lastRequestedPreviewKey, previewKey)) {
                            lastRequestedPreviewKey = null;
                        }
                    });
                }
                return;
            }
            if (hasVisiblePreviewContent(parsed)) {
                cachePreview(previewKey, parsed);
            }
            if (runner() == null) {
                return;
            }
            runner().runOnRenderThread(() -> {
                applyBatPreview(filePath, previewKey, parsed);
            });
        });
        thread.start();
    }

    private String buildPreviewKey(Path filePath, String contentSnapshot, boolean deepMergedPreview) {
        String normalizedPath = filePath.toAbsolutePath().normalize().toString();
        return normalizedPath + "|" + deepMergedPreview + "|" + contentFingerprint(contentSnapshot);
    }

    private String contentFingerprint(String contentSnapshot) {
        if (contentSnapshot == null) {
            return "0";
        }
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = messageDigest.digest(contentSnapshot.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fingerprinting is not available.", e);
        }
    }

    private Text cachedPreview(String previewKey) {
        synchronized (previewCacheByKey) {
            return previewCacheByKey.get(previewKey);
        }
    }

    private void cachePreview(String previewKey, Text preview) {
        synchronized (previewCacheByKey) {
            previewCacheByKey.put(previewKey, preview);
        }
    }

    private void applyBatPreview(Path filePath, String previewKey, Text parsed) {
        if (selectedNode == null || selectedNode.kind() != NodeKind.FILE || selectedNode.path() == null) {
            return;
        }
        if (!filePath.equals(selectedNode.path())) {
            return;
        }
        String currentPreviewKey = buildPreviewKey(selectedNode.path(), selectedFilePreviewContent, selectedFilePreviewDeepMerged);
        if (!Objects.equals(previewKey, currentPreviewKey)) {
            return;
        }
        if (!hasVisiblePreviewContent(parsed)) {
            return;
        }
        selectedFilePreview = parsed;
    }

    private boolean hasVisiblePreviewContent(Text preview) {
        for (var line : preview.lines()) {
            for (var span : line.spans()) {
                if (!span.content().isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void saveSelectedFile() {
        if (selectedNode == null || selectedNode.kind() != NodeKind.FILE || selectedNode.path() == null) {
            return;
        }
        if (selectedNode.readOnly()) {
                    status = selectedNode.parentOnlyMerged() ? STATUS_MERGED_PARENT_FILE_READ_ONLY : STATUS_INHERITED_FILE_READ_ONLY;
            return;
        }
        NodeRef savedSelection = selectedNode;
        NodeRef returnSelection = configEditReturnSelection;
        Path filePath = selectedNode.path();
        String editedProfileName = selectedNode.profileName();
        String activeProfileNameToReapply = activeProfileNameToReapplyForEditedProfile(editedProfileName);
        boolean reloadAfterSave = isOcpConfigFile(filePath);
        runBusyOperation(
            "Saving " + filePath.getFileName() + "...",
            () -> {
                try {
                    Files.writeString(filePath, editorState.text());
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to save file " + filePath + ".", e);
                }
                if (activeProfileNameToReapply != null) {
                    profileService.useProfile(activeProfileNameToReapply);
                }
            },
            "Saved " + filePath.getFileName() + ".",
            () -> {
                editMode = false;
                if (reloadAfterSave) {
                    restoreConfigEditReturnSelection(returnSelection);
                } else if (runner() == null && savedSelection != null && savedSelection.kind() == NodeKind.FILE) {
                    selectedNode = savedSelection;
                }
                selectedFileContent = editorState.text();
                if (isOcpConfigFile(filePath)) {
                    configEditReturnSelection = null;
                } else {
                    refreshSelectedFilePreview();
                }
                if (runner() != null) {
                    runner().focusManager().setFocus(TREE_ID);
                    activePane = Pane.TREE;
                } else {
                    activePane = Pane.TREE;
                }
            }
        );
    }

    private String activeProfileNameToReapplyForEditedProfile(String editedProfileName) {
        if (editedProfileName == null || editedProfileName.isBlank()) {
            return null;
        }
        Profile activeProfile = activeProfile();
        if (activeProfile == null) {
            return null;
        }
        if (activeProfile.name().equals(editedProfileName)) {
            return activeProfile.name();
        }
        return activeProfileDependsOn(activeProfile, editedProfileName) ? activeProfile.name() : null;
    }

    private Profile activeProfile() {
        for (Profile profile : profiles) {
            if (profile.active()) {
                return profile;
            }
        }
        return null;
    }

    private boolean activeProfileDependsOn(Profile activeProfile, String editedProfileName) {
        String activeProfileKey = profileKey(activeProfile.repositoryName(), activeProfile.name());
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(activeProfileKey);
        while (!pending.isEmpty()) {
            String currentProfileKey = pending.removeFirst();
            if (!visited.add(currentProfileKey)) {
                continue;
            }
            List<String> parentProfileNames = profileParentByName.getOrDefault(currentProfileKey, List.of());
            for (String parentProfileName : parentProfileNames) {
                if (editedProfileName.equals(parentProfileName)) {
                    return true;
                }
                String parentProfileKey = profileKeyForName(parentProfileName);
                if (parentProfileKey != null) {
                    pending.addLast(parentProfileKey);
                }
            }
        }
        return false;
    }

    private String profileKeyForName(String profileName) {
        for (Profile profile : profiles) {
            if (profileName.equals(profile.name())) {
                return profileKey(profile.repositoryName(), profile.name());
            }
        }
        return null;
    }



    private void restoreConfigEditReturnSelection(NodeRef returnSelection) {
        if (returnSelection == null) {
            selectedNode = null;
            selectedFileContent = "";
            selectedFilePreviewContent = "";
            selectedFilePreviewDeepMerged = false;
            selectedFilePreview = DetailPaneRenderer.plainText("");
            editorState.setText("");
            previewScrollOffset = 0;
            refreshSelectedRepositoryCommitPushPreview();
            return;
        }

        selectNode(node -> isSameSelection(node, returnSelection));
        if (!isSameSelection(selectedNode, returnSelection)) {
            selectedNode = returnSelection;
        }

        previewScrollOffset = 0;
        if (selectedNode.kind() == NodeKind.FILE && selectedNode.path() != null) {
            lastRequestedPreviewKey = null;
            refreshSelectedFilePreview();
        } else {
            selectedFileContent = "";
            selectedFilePreviewContent = "";
            selectedFilePreviewDeepMerged = false;
            selectedFilePreview = DetailPaneRenderer.plainText("");
            editorState.setText("");
            refreshSelectedRepositoryCommitPushPreview();
        }
    }

    private void editOcpConfigFile() {
        Path configFile = ocpConfigFilePath();
        if (!Files.exists(configFile)) {
            status = STATUS_CONFIG_FILE_UNAVAILABLE;
            return;
        }

        try {
            configEditReturnSelection = selectedNode;
            selectedNode = NodeRef.file(null, null, configFile);
            selectedFileContent = Files.readString(configFile);
            selectedFilePreviewContent = selectedFileContent;
            selectedFilePreviewDeepMerged = false;
            selectedFilePreview = DetailPaneRenderer.plainText(selectedFileContent);
            previewScrollOffset = 0;
            editorState.setText(selectedFileContent);
            editMode = true;
            resetEditorCursorToTop();
            if (runner() != null) {
                runner().focusManager().setFocus(EDITOR_ID);
                activePane = Pane.DETAIL;
            } else {
                activePane = Pane.DETAIL;
            }
            status = "Editing config.json. Ctrl+S to save, Esc to cancel.";
        } catch (IOException e) {
            status = "Failed to read OCP config file " + configFile + ": " + e.getMessage();
        }
    }

    private boolean isOcpConfigFile(Path filePath) {
        return ocpConfigFilePath().equals(filePath);
    }

    private Path ocpConfigFilePath() {
        String configuredPath = OcpPathSettings.configuredPath(OcpPathSettings.CONFIG_DIR_PROPERTY, OcpPathSettings.CONFIG_DIR_ENV);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath).resolve("config.json");
        }
        return Path.of(System.getProperty("user.home"), ".config", "ocp", "config.json");
    }

    private void exitEditModeToTree() {
        editMode = false;
        if (selectedNode != null && selectedNode.path() != null && isOcpConfigFile(selectedNode.path())) {
            restoreConfigEditReturnSelection(configEditReturnSelection);
            configEditReturnSelection = null;
        }
        if (runner() != null) {
            runner().focusManager().setFocus(TREE_ID);
            activePane = Pane.TREE;
        } else {
            activePane = Pane.TREE;
        }
        status = "Exited edit mode.";
    }

    private boolean isSaveKey(KeyEvent event) {
        return event.hasCtrl() && (event.isChar('s') || event.isChar('S') || event.character() == 19);
    }

    private void resetEditorCursorToTop() {
        editorState.moveCursorToStart();
        editorState.ensureCursorVisible(0, 0);
    }

    private String selectedRepositoryName() {
        if (selectedNode != null && selectedNode.repositoryName() != null) {
            return selectedNode.repositoryName();
        }
        return null;
    }

    private ConfiguredRepository selectedConfiguredRepository() {
        String repositoryName = selectedRepositoryName();
        if (repositoryName == null) {
            return null;
        }
        for (ConfiguredRepository repository : repositories) {
            if (repository.name().equals(repositoryName)) {
                return repository;
            }
        }
        return null;
    }

    private String selectedProfileName() {
        if (selectedNode != null && selectedNode.profileName() != null) {
            return selectedNode.profileName();
        }
        return null;
    }

    private boolean selectedProfileHasParent() {
        String repositoryName = selectedRepositoryName();
        String profileName = selectedProfileName();
        return repositoryName != null
            && profileName != null
            && !profileParentByName.getOrDefault(profileKey(repositoryName, profileName), List.of()).isEmpty();
    }

    private String immediateParentProfileName(String repositoryName, String profileName) {
        List<String> parentProfileNames = profileParentByName.get(profileKey(repositoryName, profileName));
        if (parentProfileNames == null || parentProfileNames.isEmpty()) {
            return null;
        }
        return parentProfileNames.getLast();
    }

    private String immediateContributorProfileName(NodeRef node) {
        if (node == null) {
            return null;
        }
        if (!node.readOnly()) {
            return null;
        }
        List<String> contributorProfileNames = node.contributorProfileNames();
        if (contributorProfileNames == null || contributorProfileNames.isEmpty()) {
            return null;
        }
        return contributorProfileNames.getLast();
    }

    private Path immediateContributorPath(NodeRef node) {
        if (node == null) {
            return null;
        }
        List<Path> contributorSourcePaths = node.contributorSourcePaths();
        if (contributorSourcePaths == null || contributorSourcePaths.isEmpty()) {
            return null;
        }
        return contributorSourcePaths.get(contributorSourcePaths.size() - 1);
    }

    private void navigateToParentProfile() {
        String repositoryName = selectedRepositoryName();
        String profileName = selectedProfileName();
        if (repositoryName == null || profileName == null) {
            status = "Select a profile or file first.";
            return;
        }

        String contributorParentName = selectedNode != null && selectedNode.kind() == NodeKind.FILE
            ? immediateContributorProfileName(selectedNode)
            : null;
        String parentProfileName = contributorParentName != null
            ? contributorParentName
            : immediateParentProfileName(repositoryName, profileName);
        if (parentProfileName == null || parentProfileName.isBlank()) {
            status = "Profile " + profileName + " does not extend another profile.";
            return;
        }

        String parentRepositoryName = repositoryNameForProfile(parentProfileName);
        if (parentRepositoryName == null) {
            status = "Parent profile " + parentProfileName + " was not found in the tree.";
            return;
        }

        Path parentFilePath = immediateContributorPath(selectedNode);
        boolean selectingInheritedParentFile = selectedNode != null
            && selectedNode.kind() == NodeKind.FILE
            && selectedNode.path() != null
            && selectedNode.profileName() != null
            && parentFilePath != null
            && selectedNode.readOnly();
        Path inheritedParentPath = selectingInheritedParentFile ? parentFilePath : null;
        boolean selectedParent = selectingInheritedParentFile
            ? selectNode(node -> node.kind() == NodeKind.FILE
                && parentRepositoryName.equals(node.repositoryName())
                && parentProfileName.equals(node.profileName())
                && inheritedParentPath.equals(node.path())
                && !node.inherited())
            : selectNode(node -> node.kind() == NodeKind.PROFILE
                && parentRepositoryName.equals(node.repositoryName())
                && parentProfileName.equals(node.profileName()));
        if (!selectedParent) {
            status = selectingInheritedParentFile
                ? "Parent file in profile " + parentProfileName + " was not found in the tree."
                : "Parent profile " + parentProfileName + " was not found in the tree.";
            return;
        }


        syncSelectionAndPreview();
        if (runner() != null) {
            runner().focusManager().setFocus(TREE_ID);
            activePane = Pane.TREE;
        }
        status = selectingInheritedParentFile
            ? "Selected inherited parent file from profile " + parentProfileName + "."
            : "Selected parent profile " + parentProfileName + ".";
    }

    private void copySelectedPath() {
        if (selectedNode == null || selectedNode.path() == null) {
            status = STATUS_SELECT_NODE_FIRST;
            return;
        }
        if (selectedNode.kind() != NodeKind.FILE) {
            status = "Select a file first.";
            return;
        }

        Path absolutePath = selectedNode.path().toAbsolutePath().normalize();
        try {
            clipboardClient.copy(absolutePath.toString());
            status = "Copied path " + absolutePath + " to the clipboard.";
        } catch (IllegalStateException e) {
            status = e.getMessage();
        }
    }

    private boolean selectNode(java.util.function.Predicate<NodeRef> predicate) {
        Integer selectedIndex = findVisibleTreeIndex(predicate, hierarchyRoots, new int[] {0});
        if (selectedIndex == null) {
            return false;
        }
        hierarchyTree.selected(selectedIndex);
        return true;
    }

    private Integer findVisibleTreeIndex(
        java.util.function.Predicate<NodeRef> predicate,
        List<TreeNode<NodeRef>> nodes,
        int[] currentIndex
    ) {
        for (TreeNode<NodeRef> node : nodes) {
            NodeRef data = node.data();
            if (data != null && predicate.test(data)) {
                return currentIndex[0];
            }
            currentIndex[0] += 1;
            if (!node.isLeaf() && node.isExpanded()) {
                Integer childMatch = findVisibleTreeIndex(predicate, node.children(), currentIndex);
                if (childMatch != null) {
                    return childMatch;
                }
            }
        }
        return null;
    }

    private TreeNode<NodeRef> visibleTreeNodeAt(int targetIndex, List<TreeNode<NodeRef>> nodes, int[] currentIndex) {
        for (TreeNode<NodeRef> node : nodes) {
            if (currentIndex[0] == targetIndex) {
                return node;
            }
            currentIndex[0] += 1;
            if (!node.isLeaf() && node.isExpanded()) {
                TreeNode<NodeRef> childMatch = visibleTreeNodeAt(targetIndex, node.children(), currentIndex);
                if (childMatch != null) {
                    return childMatch;
                }
            }
        }
        return null;
    }

    private String repositoryNameForProfile(String profileName) {
        for (Profile profile : profiles) {
            if (profileName.equals(profile.name())) {
                return profile.repositoryName();
            }
        }
        return null;
    }

    private Map<String, RepositoryDirtyState> loadImmediateRepositoryDirtyStateByName(List<ConfiguredRepository> configuredRepositories) {
        Map<String, RepositoryDirtyState> dirtyStateByName = new HashMap<>();
        for (ConfiguredRepository repository : configuredRepositories) {
            dirtyStateByName.put(repository.name(), RepositoryDirtyState.clean());
        }
        return Map.copyOf(dirtyStateByName);
    }

    private Map<String, RepositoryDirtyState> loadRepositoryDirtyStateByName(List<ConfiguredRepository> configuredRepositories) {
        Map<String, RepositoryDirtyState> dirtyStateByName = new HashMap<>();
        for (ConfiguredRepository repository : configuredRepositories) {
            if (!repository.isGitBacked()) {
                dirtyStateByName.put(repository.name(), RepositoryDirtyState.clean());
                continue;
            }
            try {
                dirtyStateByName.put(
                    repository.name(),
                    RepositoryDirtyState.fromPreview(
                        repositoryService.inspectCommitPush(
                            new RepositoryEntry(repository.name(), repository.uri(), repository.localPath())
                        )
                    )
                );
            } catch (RuntimeException e) {
                dirtyStateByName.put(repository.name(), RepositoryDirtyState.inspectionError());
            }
        }
        return Map.copyOf(dirtyStateByName);
    }

    private void refreshRepositoryDirtyStateByNameInBackground(List<ConfiguredRepository> configuredRepositories) {
        if (runner() == null) {
            repositoryDirtyStateByName = loadRepositoryDirtyStateByName(configuredRepositories);
            return;
        }
        long refreshSequence = ++repositoryDirtyStateRefreshSequence;
        List<ConfiguredRepository> repositoriesSnapshot = List.copyOf(configuredRepositories);
        Thread.ofVirtual().start(() -> {
            Map<String, RepositoryDirtyState> refreshedDirtyStateByName = loadRepositoryDirtyStateByName(repositoriesSnapshot);
            runner().runOnRenderThread(() -> {
                if (refreshSequence != repositoryDirtyStateRefreshSequence) {
                    return;
                }
                repositoryDirtyStateByName = refreshedDirtyStateByName;
                refreshSelectedRepositoryCommitPushPreview();
            });
        });
    }

    private void refreshSelectedRepositoryCommitPushPreview() {
        if (suppressNextRepositoryCommitPushPreviewRefresh) {
            suppressNextRepositoryCommitPushPreviewRefresh = false;
            return;
        }
        if (selectedNode == null || selectedNode.kind() != NodeKind.REPOSITORY || selectedNode.repositoryName() == null) {
            selectedRepositoryCommitPushPreview = null;
            return;
        }
        try {
            selectedRepositoryCommitPushPreview = repositoryService.inspectCommitPush(selectedNode.repositoryName());
            repositoryDirtyStateByName = withRepositoryDirtyState(
                selectedNode.repositoryName(),
                RepositoryDirtyState.fromPreview(selectedRepositoryCommitPushPreview)
            );
        } catch (RuntimeException e) {
            selectedRepositoryCommitPushPreview = null;
            repositoryDirtyStateByName = withRepositoryDirtyState(selectedNode.repositoryName(), RepositoryDirtyState.inspectionError());
            status = "Error: " + e.getMessage();
        }
    }

    private void refreshSelectedFilePreview() {
        if (selectedNode == null || selectedNode.kind() != NodeKind.FILE || selectedNode.path() == null) {
            selectedFileContent = "";
            selectedFilePreviewContent = "";
            selectedFilePreviewDeepMerged = false;
            selectedFilePreview = DetailPaneRenderer.plainText("");
            editorState.setText("");
            return;
        }
        try {
            ProfileService.ResolvedFilePreview resolvedPreview = profileService.resolvedFilePreview(
                selectedNode.profileName(),
                selectedNode.path()
            );
            String loadedFileContent = Files.readString(selectedNode.path());
            String resolvedPreviewContent = resolvedPreview.content();
            selectedFileContent = loadedFileContent;
            selectedFilePreviewContent = resolvedPreviewContent;
            selectedFilePreviewDeepMerged = resolvedPreview.deepMerged();
            selectedFilePreview = DetailPaneRenderer.plainText(selectedFilePreviewContent);
            requestBatPreview(selectedNode.path(), selectedFilePreviewContent, resolvedPreview.deepMerged());
            editorState.setText(selectedFileContent);
        } catch (IOException e) {
            selectedFileContent = "";
            selectedFilePreviewContent = "";
            selectedFilePreviewDeepMerged = false;
            selectedFilePreview = DetailPaneRenderer.plainText("");
            editorState.setText("");
            status = "Error loading file: " + e.getMessage();
        } catch (RuntimeException e) {
            selectedFileContent = "";
            selectedFilePreviewContent = "";
            selectedFilePreviewDeepMerged = false;
            selectedFilePreview = DetailPaneRenderer.plainText("");
            editorState.setText("");
            status = "Error loading file: " + e.getMessage();
        }
    }

    private Map<String, RepositoryDirtyState> withRepositoryDirtyState(String repositoryName, RepositoryDirtyState dirtyState) {
        Map<String, RepositoryDirtyState> updated = new HashMap<>(repositoryDirtyStateByName);
        updated.put(repositoryName, dirtyState);
        return Map.copyOf(updated);
    }

    private static String profileKey(String repositoryName, String profileName) {
        return repositoryName + "/" + profileName;
    }

    private int countTreeNodes(List<TreeNode<NodeRef>> roots) {
        int count = 0;
        for (TreeNode<NodeRef> root : roots) {
            count += 1;
            count += countTreeNodes(root.children());
        }
        return count;
    }

    private void prewarmBatAvailability() {
        boolean available = batPreviewRenderer.probeAvailability();
        if (runner() != null) {
            runner().runOnRenderThread(() -> {
                batAvailable = available;
                if (available && selectedNode != null && selectedNode.kind() == NodeKind.FILE && selectedNode.path() != null) {
                    requestBatPreview(selectedNode.path(), selectedFilePreviewContent, selectedFilePreviewDeepMerged);
                }
            });
        } else {
            batAvailable = available;
        }
    }

    private void loadInitialDataInBackground() {
        OnboardingLoadResult onboardingLoadResult = new OnboardingLoadResult(null, null);
        try {
            reloadState();
            onboardingLoadResult = detectOnboardingCandidate();
        } finally {
            if (runner() != null) {
                OnboardingLoadResult finalOnboardingLoadResult = onboardingLoadResult;
                runner().runOnRenderThread(() -> {
                    applyInitialDataLoad(finalOnboardingLoadResult.candidate(), finalOnboardingLoadResult.errorStatus());
                });
            } else {
                applyInitialDataLoad(onboardingLoadResult.candidate(), onboardingLoadResult.errorStatus());
            }
        }
    }

    private void applyInitialDataLoad(OnboardingService.OnboardingCandidate onboardingCandidate, String onboardingLoadErrorStatus) {
        initialDataLoaded = true;
        if (onboardingLoadErrorStatus != null) {
            status = onboardingLoadErrorStatus;
        }
        maybePromptForStartupOnboarding(onboardingCandidate);
        maybeHideSplash();
    }

    private void maybePromptForStartupOnboarding() {
        OnboardingLoadResult onboardingLoadResult = detectOnboardingCandidate();
        if (onboardingLoadResult.errorStatus() != null) {
            status = onboardingLoadResult.errorStatus();
            return;
        }
        maybePromptForStartupOnboarding(onboardingLoadResult.candidate());
    }

    private OnboardingLoadResult detectOnboardingCandidate() {
        try {
            return new OnboardingLoadResult(onboardingService.detect().orElse(null), null);
        } catch (RuntimeException e) {
            return new OnboardingLoadResult(null, "Error loading onboarding: " + e.getMessage());
        }
    }

    private void maybePromptForStartupOnboarding(OnboardingService.OnboardingCandidate candidate) {
        if (prompt != null) {
            return;
        }
        if (candidate == null) {
            return;
        }
        String fileSummary = candidate.configFiles()
            .stream()
            .map(path -> "- " + path.getFileName())
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
        prompt = PromptState.multiWithOptions(
            PromptAction.ONBOARD_EXISTING_CONFIG_CONFIRM,
            "Import existing OpenCode config files into OCP?",
            List.of(fileSummary),
            List.of(List.of("yes", "no"))
        );
    }

    private boolean isOnboardingPromptAction(PromptAction action) {
        return action == PromptAction.ONBOARD_EXISTING_CONFIG_CONFIRM
            || action == PromptAction.ONBOARD_EXISTING_CONFIG_REPOSITORY_NAME
            || action == PromptAction.ONBOARD_EXISTING_CONFIG_PROFILE_NAME;
    }

    private void maybeHideSplash() {
        if (initialDataLoaded && splashMinimumElapsed) {
            splashVisible = false;
        }
    }

    private String statusLine() {
        if (!busy) {
            return status;
        }
        return SPINNER_FRAMES[spinnerIndex % SPINNER_FRAMES.length] + " " + busyMessage;
    }

    private Element renderShortcutPanel(TreeShortcutHints.ShortcutHints shortcutHints) {
        List<Element> lines = new ArrayList<>();
        if (!shortcutHints.navigation().isEmpty()) {
            lines.add(ShortcutHintRenderer.line("Navigate", shortcutHints.navigation()));
        }
        if (!shortcutHints.actions().isEmpty()) {
            lines.add(ShortcutHintRenderer.line(
                "Actions",
                shortcutHints.actions(),
                ShortcutHintRenderer.emphasizedPrefixStyle()
            ));
        }
        return panel(lines.toArray(Element[]::new)).rounded().borderColor(Color.CYAN).length(lines.size() + 2);
    }

    private Element renderStatusPanel() {
        int terminalWidth = resolvedTerminalColumns();
        int contentWidth = Math.max(20, terminalWidth - 4);
        String versionLabel = "v" + currentVersion;
        boolean renderVersionInline = contentWidth - versionLabel.length() - 1 >= 12;
        int statusWidth = renderVersionInline ? contentWidth - versionLabel.length() - 1 : contentWidth;

        List<String> lines = wrapStatusLines(statusLine(), statusWidth);
        List<Element> panelContent = new ArrayList<>();
        List<Line> statusTextLines = statusTextLines(statusWidth);

        if (renderVersionInline) {
            panelContent.add(
                row(
                    richText(Text.from(statusTextLines.getFirst())),
                    spacer(),
                    text(versionLabel).bold().fg(Color.CYAN)
                )
            );
            for (int i = 1; i < statusTextLines.size(); i++) {
                panelContent.add(richText(Text.from(statusTextLines.get(i))));
            }
        } else {
            for (Line line : statusTextLines) {
                panelContent.add(richText(Text.from(line)));
            }
            panelContent.add(row(spacer(), text(versionLabel).bold().fg(Color.CYAN)));
        }

        return panel(
            column(panelContent.toArray(Element[]::new))
        ).rounded().borderColor(busy ? Color.GREEN : Color.YELLOW).length(2 + panelContent.size());
    }

    private List<Line> statusTextLines(int width) {
        List<String> rawLines = wrapStatusLines(statusLine(), width);
        List<Line> lines = new ArrayList<>();
        for (String line : rawLines) {
            lines.add(Cli.highlightedNoticeLine(line));
        }
        return lines;
    }

    private int resolvedTerminalColumns() {
        OptionalInt terminalColumns = parseColumns(System.getenv("COLUMNS"));
        if (terminalColumns.isPresent()) {
            return terminalColumns.getAsInt();
        }
        return 120;
    }

    static List<String> wrapStatusLines(String text, int width) {
        if (text == null || text.isBlank()) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (word.length() > width) {
                if (!currentLine.isEmpty()) {
                    lines.add(currentLine.toString());
                    currentLine.setLength(0);
                }
                int index = 0;
                while (word.length() - index > width) {
                    lines.add(word.substring(index, index + width));
                    index += width;
                }
                if (index < word.length()) {
                    currentLine.append(word.substring(index));
                }
                continue;
            }

            if (currentLine.isEmpty()) {
                currentLine.append(word);
                continue;
            }

            if (currentLine.length() + 1 + word.length() <= width) {
                currentLine.append(' ').append(word);
                continue;
            }

            lines.add(currentLine.toString());
            currentLine.setLength(0);
            currentLine.append(word);
        }
        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private void syncActivePaneFromFocus() {
        if (runner() == null) {
            return;
        }
        String focusedId = runner().focusManager().focusedId();
        if (TREE_ID.equals(focusedId)) {
            activePane = Pane.TREE;
            return;
        }
        if (DETAIL_ID.equals(focusedId) || EDITOR_ID.equals(focusedId)) {
            activePane = Pane.DETAIL;
        }
    }

    private void togglePaneFocus() {
        if (runner() == null) {
            return;
        }
        if (activePane == Pane.TREE) {
            if (editMode && selectedNode != null && selectedNode.kind() == NodeKind.FILE) {
                runner().focusManager().setFocus(EDITOR_ID);
            } else {
                runner().focusManager().setFocus(DETAIL_ID);
            }
            activePane = Pane.DETAIL;
            return;
        }
        runner().focusManager().setFocus(TREE_ID);
        activePane = Pane.TREE;
    }

    private void runBusyOperation(String message, Runnable operation, String successMessage, Runnable onSuccess) {
        runBusyOperation(message, () -> {
            operation.run();
            return successMessage;
        }, onSuccess);
    }

    private void runBusyOperation(String message, java.util.function.Supplier<String> operation, Runnable onSuccess) {
        if (busy) {
            return;
        }
        if (runner() == null) {
            try {
                status = operation.get();
                reloadState();
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } catch (Exception e) {
                status = "Error: " + e.getMessage();
                reloadState();
            }
            return;
        }

        busy = true;
        busyMessage = message;
        spinnerIndex = 0;

        AtomicReference<ToolkitRunner.ScheduledAction> spinnerActionRef = new AtomicReference<>();
        spinnerActionRef.set(
            runner().scheduleRepeating(
                () -> runner().runOnRenderThread(() -> spinnerIndex = (spinnerIndex + 1) % SPINNER_FRAMES.length),
                Duration.ofMillis(120)
            )
        );

        Thread.ofVirtual().start(() -> {
            String finalMessage = null;
            ProfileService.ProfileRefreshConflictException repositoryConflict = null;
            ProfileService.ProfileRefreshUserConfigConflictException mergedFilesConflict = null;
            boolean operationSucceeded = true;
            try {
                finalMessage = operation.get();
            } catch (ProfileService.ProfileRefreshConflictException e) {
                repositoryConflict = e;
                operationSucceeded = false;
            } catch (ProfileService.ProfileRefreshUserConfigConflictException e) {
                mergedFilesConflict = e;
                operationSucceeded = false;
            } catch (RuntimeException e) {
                finalMessage = "Error: " + e.getMessage();
                operationSucceeded = false;
            } catch (Exception e) {
                finalMessage = "Unexpected error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                operationSucceeded = false;
            }

            String messageToShow = finalMessage;
            ProfileService.ProfileRefreshConflictException finalRepositoryConflict = repositoryConflict;
            ProfileService.ProfileRefreshUserConfigConflictException finalMergedFilesConflict = mergedFilesConflict;
            boolean finalOperationSucceeded = operationSucceeded;
            runner().runOnRenderThread(() -> {
                ToolkitRunner.ScheduledAction spinnerAction = spinnerActionRef.get();
                if (spinnerAction != null) {
                    spinnerAction.cancel();
                }
                busy = false;
                busyMessage = null;

                if (finalRepositoryConflict != null) {
                    refreshConflict = RefreshConflictState.forRepository(finalRepositoryConflict);
                    status = finalRepositoryConflict.getMessage();
                    return;
                }
                if (finalMergedFilesConflict != null) {
                    refreshConflict = RefreshConflictState.forMergedFiles(finalMergedFilesConflict);
                    status = finalMergedFilesConflict.getMessage();
                    return;
                }

                status = messageToShow;
                reloadState();
                if (finalOperationSucceeded && onSuccess != null) {
                    onSuccess.run();
                }
            });
        });
    }

    private EventResult handleRefreshConflictKey(KeyEvent event) {
        if (event.isQuit()) {
            quit();
            return EventResult.HANDLED;
        }
        if (event.isCancel()) {
            refreshConflict = null;
            pendingRefreshOperation = null;
            status = REFRESH_CANCELLED_MESSAGE;
            return EventResult.HANDLED;
        }

        if (refreshConflict.kind() == RefreshConflictKind.REPOSITORY) {
            if (event.isChar('1')) {
                resolveRepositoryConflict(ProfileService.RefreshConflictResolution.DISCARD_AND_REFRESH);
                return EventResult.HANDLED;
            }
            if (event.isChar('2')) {
                resolveRepositoryConflict(ProfileService.RefreshConflictResolution.COMMIT_AND_FORCE_PUSH);
                return EventResult.HANDLED;
            }
            if (event.isChar('3')) {
                refreshConflict = null;
                pendingRefreshOperation = null;
                status = REFRESH_CANCELLED_MESSAGE;
                return EventResult.HANDLED;
            }
            return EventResult.HANDLED;
        }

        if (event.isChar('1')) {
            resolveMergedFilesConflict();
            return EventResult.HANDLED;
        }
        if (event.isChar('2')) {
            refreshConflict = null;
            pendingRefreshOperation = null;
            status = REFRESH_CANCELLED_MESSAGE;
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    private void resolveRepositoryConflict(ProfileService.RefreshConflictResolution resolution) {
        ProfileService.ProfileRefreshConflictException conflict = refreshConflict.repositoryConflict();
        if (conflict == null) {
            return;
        }
        refreshConflict = null;

        if (resolution == ProfileService.RefreshConflictResolution.DISCARD_AND_REFRESH) {
            runBusyOperation(
                "Discarding local changes in repository " + conflict.repositoryName() + "...",
                () -> profileService.resolveRefreshConflict(conflict, resolution),
                "Local changes discarded in repository " + conflict.repositoryName() + ".",
                this::attemptPendingRefresh
            );
            return;
        }

        runBusyOperation(
            "Committing local changes and force-pushing repository " + conflict.repositoryName() + "...",
            () -> profileService.resolveRefreshConflict(conflict, resolution),
            "Local changes committed and force-pushed for repository " + conflict.repositoryName() + ".",
            this::attemptPendingRefresh
        );
    }

    private void resolveMergedFilesConflict() {
        ProfileService.ProfileRefreshUserConfigConflictException conflict = refreshConflict.mergedFilesConflict();
        if (conflict == null) {
            return;
        }
        refreshConflict = null;
        runBusyOperation(
            "Discarding local merged-file changes for profile " + conflict.profileName() + "...",
            () -> profileService.resolveRefreshConflict(conflict, ProfileService.RefreshConflictResolution.DISCARD_AND_REFRESH),
            "Local changes discarded in merged user config files for profile " + conflict.profileName() + ".",
            this::attemptPendingRefresh
        );
    }

    private Element renderRefreshConflictDialog() {
        return RefreshConflictDialogRenderer.render(refreshConflict, this::handleKeyEvent);
    }

    private Element renderCommitConfirmDialog() {
        return CommitConfirmDialogRenderer.render(commitConfirm, this::handleKeyEvent);
    }

    private EventResult handleCommitConfirmKey(KeyEvent event) {
        if (event.isQuit()) {
            quit();
            return EventResult.HANDLED;
        }
        if (event.isCancel()) {
            commitConfirm = null;
            status = "Commit cancelled.";
            return EventResult.HANDLED;
        }
        if (event.isChar('y') || event.isChar('Y')) {
            String repositoryName = commitConfirm.repositoryName();
            commitConfirm = null;
            PromptState nextPrompt = PromptState.single(
                PromptAction.COMMIT_AND_PUSH_REPOSITORY,
                "Commit and push changes",
                "Commit message"
            );
            nextPrompt.contextRepositoryName = repositoryName;
            nextPrompt.values.set(0, DEFAULT_COMMIT_MESSAGE);
            prompt = nextPrompt;
            status = "Review commit message for " + repositoryName + ".";
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    private Element renderPromptDialog() {
        int dialogWidth = promptDialogWidth(prompt);
        List<Element> promptContent = new ArrayList<>();
        if (prompt.action == PromptAction.ONBOARD_EXISTING_CONFIG_CONFIRM) {
            for (String line : prompt.label().split("\\R", -1)) {
                promptContent.add(text(line));
            }
            promptContent.add(spacer());
        } else {
            promptContent.add(text(prompt.label()).bold());
            promptContent.add(text(promptDisplayValue(prompt)));
        }
        if (prompt.currentFieldHasOptions()) {
            List<String> options = prompt.currentFieldOptions();
            int selectedIndex = prompt.currentFieldSelectedOptionIndex();
            for (int index = 0; index < options.size(); index++) {
                String value = options.get(index);
                String optionLabel = value.isBlank() ? "<none>" : value;
                if (index == selectedIndex) {
                    promptContent.add(text("-> " + optionLabel).bold().fg(Color.CYAN));
                } else {
                    promptContent.add(text("   " + optionLabel).dim());
                }
            }
        }
        promptContent.add(ShortcutHintRenderer.line(prompt.currentFieldHasOptions() ? PROMPT_OPTION_SHORTCUTS : PROMPT_SHORTCUTS));

        return dialog(
            prompt.title,
            promptContent.toArray(Element[]::new)
        ).rounded().borderColor(Color.MAGENTA).width(dialogWidth);
    }

    private Element renderStartupUpdateDialog() {
        List<Element> content = new ArrayList<>();
        String notice = startupUpdateNotice == null ? "" : startupUpdateNotice;
        for (String line : notice.split("\\R", -1)) {
            content.add(richText(Text.from(Cli.highlightedNoticeLine(line))));
        }
        content.add(spacer());
        content.add(ShortcutHintRenderer.line(List.of(TreeShortcutHints.Shortcut.ESC_CANCEL)));
        return dialog(STARTUP_UPDATE_DIALOG_TITLE, content.toArray(Element[]::new))
            .rounded()
            .borderColor(Color.CYAN)
            .width(88);
    }

    static int promptDialogWidthForContent(String title, String label, String currentValue, String shortcutsLine, int maxWidth) {
        int safeMaxWidth = Math.max(48, maxWidth);
        int desiredWidth = Math.max(
            maxDisplayWidth(title, label, currentValue, shortcutsLine) + 8,
            52
        );
        return Math.min(desiredWidth, safeMaxWidth);
    }

    private int promptDialogWidth(PromptState promptState) {
        List<String> widthCandidates = new ArrayList<>();
        widthCandidates.add(promptState.label());
        widthCandidates.add(promptDisplayValue(promptState));
        widthCandidates.add(ShortcutHintRenderer.plainLine(promptState.currentFieldHasOptions() ? PROMPT_OPTION_SHORTCUTS : PROMPT_SHORTCUTS));
        if (promptState.currentFieldHasOptions()) {
            for (String option : promptState.currentFieldOptions()) {
                widthCandidates.add((option.isBlank() ? "<none>" : option) + "    ");
            }
        }
        return promptDialogWidthForContent(
            promptState.title,
            String.join("\n", widthCandidates),
            "",
            "",
            resolvedPromptDialogMaxWidth()
        );
    }

    private String promptDisplayValue(PromptState promptState) {
        if (!promptState.currentFieldHasOptions()) {
            return promptState.currentValue();
        }
        String value = promptState.currentValue();
        return value.isBlank() ? "<none>" : value;
    }

    private int resolvedPromptDialogMaxWidth() {
        OptionalInt terminalColumns = parseColumns(System.getenv("COLUMNS"));
        if (terminalColumns.isPresent()) {
            return Math.max(48, terminalColumns.getAsInt() - 6);
        }
        return 120;
    }

    private static OptionalInt parseColumns(String rawColumns) {
        if (rawColumns == null || rawColumns.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            int columns = Integer.parseInt(rawColumns.trim());
            return columns > 0 ? OptionalInt.of(columns) : OptionalInt.empty();
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    private static int maxDisplayWidth(String... values) {
        int maxWidth = 0;
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String[] lines = value.split("\\R", -1);
            for (String line : lines) {
                if (line.length() > maxWidth) {
                    maxWidth = line.length();
                }
            }
        }
        return maxWidth;
    }

    private Element splashScreen() {
        List<Element> content = new ArrayList<>();
        for (String line : splashLogoLines) {
            content.add(row(spacer(), text(line), spacer()));
        }
        content.add(spacer());
        content.add(row(spacer(), text("OpenCode Configuration Profiles").bold().fg(Color.CYAN), spacer()));
        content.add(row(spacer(), text("Interactive Tree Workspace").fg(Color.YELLOW), spacer()));
        content.add(row(spacer(), text("Current version: v" + currentVersion).fg(Color.CYAN), spacer()));
        content.add(spacer());
        content.add(row(spacer(), text("Loading repositories and profile trees...").dim(), spacer()));

        return dialog(
            column(content.toArray(Element[]::new))
        )
            .rounded()
            .borderColor(Color.CYAN)
            .width(72)
            .padding(1)
            .id("splash")
            .focusable()
            .onKeyEvent(this::handleKeyEvent);
    }

    private void loadSplashLogoLines() {
        try (var input = InteractiveApp.class.getResourceAsStream(SPLASH_LOGO_RESOURCE)) {
            if (input == null) {
                status = "Splash logo file not found (splash-logo.txt), using default.";
                return;
            }

            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            List<String> loadedLines = content.lines().toList();
            if (loadedLines.isEmpty()) {
                status = "Splash logo file is empty, using default.";
                return;
            }
            splashLogoLines = List.copyOf(loadedLines);
        } catch (IOException | RuntimeException e) {
            status = "Error loading splash logo file: " + e.getMessage();
        }
    }

    private record OnboardingLoadResult(OnboardingService.OnboardingCandidate candidate, String errorStatus) {
    }

    void testReloadState() {
        reloadState();
    }

    void testApplyPrompt() {
        applyPrompt();
    }

    void testSyncSelectionAndPreview() {
        syncSelectionAndPreview();
    }

    void testRestoreConfigEditReturnSelection(NodeRef nodeRef) {
        restoreConfigEditReturnSelection(nodeRef);
    }

    void testRefreshAllRepositories() {
        refreshAllRepositories();
    }

    void testNavigateToParentProfile() {
        navigateToParentProfile();
    }

    void testExitEditModeToTree() {
        exitEditModeToTree();
    }

    void testSaveSelectedFile() {
        saveSelectedFile();
    }

    void testEditOcpConfigFile() {
        editOcpConfigFile();
    }

    void testHandleRefreshConflictKey(KeyEvent event) {
        handleRefreshConflictKey(event);
    }

    void testHandleCommitConfirmKey(KeyEvent event) {
        handleCommitConfirmKey(event);
    }

    void testHandlePromptKey(KeyEvent event) {
        handlePromptKey(event);
    }

    void testRefreshSelectedRepositoryCommitPushPreview() {
        refreshSelectedRepositoryCommitPushPreview();
    }

    void testCommitAndPushSelectedRepository() {
        commitAndPushSelectedRepository();
    }

    void testMigrateSelectedRepository() {
        migrateSelectedRepository();
    }

    boolean testIsSelectedRepositoryCommitPushAvailable() {
        return isSelectedRepositoryCommitPushAvailable();
    }

    void testLoadInitialDataInBackground() {
        loadInitialDataInBackground();
    }

    ActiveOverlay testActiveOverlay() {
        return activeOverlay();
    }

    void testCopySelectedPath() {
        copySelectedPath();
    }

    void testSetSelectedNode(NodeRef nodeRef) {
        selectedNode = nodeRef;
    }

    NodeRef testSelectedNode() {
        return selectedNode;
    }

    void testSetPrompt(PromptState promptState) {
        prompt = promptState;
    }

    PromptState testPrompt() {
        return prompt;
    }

    String testStatus() {
        return status;
    }

    boolean testEditMode() {
        return editMode;
    }

    void testSetEditMode(boolean value) {
        editMode = value;
    }

    Pane testActivePane() {
        return activePane;
    }

    void testSetActivePane(Pane pane) {
        activePane = pane;
    }

    TextAreaState testEditorState() {
        return editorState;
    }

    Text testSelectedFilePreview() {
        return selectedFilePreview;
    }

    void testSetSelectedFilePreview(Text preview) {
        selectedFilePreview = preview;
    }

    String testSelectedFileContent() {
        return selectedFileContent;
    }

    List<TreeNode<NodeRef>> testHierarchyRoots() {
        return hierarchyRoots;
    }

    TreeElement<NodeRef> testHierarchyTree() {
        return hierarchyTree;
    }

    boolean testSelectTreeNode(Predicate<NodeRef> predicate) {
        int selectedIndex = 0;
        for (TreeNode<NodeRef> root : hierarchyRoots) {
            int match = testFindVisibleNodeIndex(root, predicate, selectedIndex);
            if (match >= 0) {
                hierarchyTree.selected(match);
                TreeNode<NodeRef> selectedTreeNode = hierarchyTree.selectedNode();
                if (selectedTreeNode != null && selectedTreeNode.data() != null && predicate.test(selectedTreeNode.data())) {
                    selectedNode = selectedTreeNode.data();
                    lastSyncedTreeSelection = hierarchyTree.selected();
                    return true;
                }
                TreeNode<NodeRef> visibleTreeNode = visibleTreeNodeAt(match, hierarchyRoots, new int[] {0});
                if (visibleTreeNode != null && visibleTreeNode.data() != null && predicate.test(visibleTreeNode.data())) {
                    selectedNode = visibleTreeNode.data();
                    lastSyncedTreeSelection = hierarchyTree.selected();
                    return true;
                }
                return false;
            }
            selectedIndex += testCountVisibleNodes(root);
        }
        return false;
    }

    boolean testSelectTreeNodeAndSync(Predicate<NodeRef> predicate) {
        if (!testSelectTreeNode(predicate)) {
            return false;
        }
        skipNextSelectionSync = false;
        lastSyncedTreeSelection = -1;
        syncSelectionAndPreview();
        return selectedNode != null && predicate.test(selectedNode);
    }


    boolean testAdvanceCreateProfilePromptIfNeeded() {
        return advanceCreateProfilePromptIfNeeded();
    }

    String testRefreshAllCompletionMessage() {
        return refreshAllCompletionMessage;
    }

    RefreshOperation testPendingRefreshOperation() {
        return pendingRefreshOperation;
    }

    void testSetPendingRefreshOperation(RefreshOperation operation) {
        pendingRefreshOperation = operation;
    }

    RefreshConflictState testRefreshConflict() {
        return refreshConflict;
    }

    void testSetRefreshConflict(RefreshConflictState state) {
        refreshConflict = state;
    }

    CommitConfirmState testCommitConfirm() {
        return commitConfirm;
    }

    Map<String, RepositoryDirtyState> testRepositoryDirtyStateByName() {
        return repositoryDirtyStateByName;
    }

    List<ConfiguredRepository> testRepositories() {
        return repositories;
    }

    boolean testSplashVisible() {
        return splashVisible;
    }

    void testSetBatAvailable(boolean value) {
        batAvailable = value;
    }

    String testStartupUpdateNotice() {
        return startupUpdateNotice;
    }

    public String startupUpdateNotice() {
        return startupUpdateNotice;
    }

    public String status() {
        return status;
    }

    private int testFindVisibleNodeIndex(TreeNode<NodeRef> node, Predicate<NodeRef> predicate, int currentIndex) {
        if (node.data() != null && predicate.test(node.data())) {
            return currentIndex;
        }
        int nextIndex = currentIndex + 1;
        if (!node.isLeaf() && node.isExpanded()) {
            for (TreeNode<NodeRef> child : node.children()) {
                int match = testFindVisibleNodeIndex(child, predicate, nextIndex);
                if (match >= 0) {
                    return match;
                }
                nextIndex += testCountVisibleNodes(child);
            }
        }
        return -1;
    }

    private int testCountVisibleNodes(TreeNode<NodeRef> node) {
        int count = 1;
        if (!node.isLeaf() && node.isExpanded()) {
            for (TreeNode<NodeRef> child : node.children()) {
                count += testCountVisibleNodes(child);
            }
        }
        return count;
    }

}
