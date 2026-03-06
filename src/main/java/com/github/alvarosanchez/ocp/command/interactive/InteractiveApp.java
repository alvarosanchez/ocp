package com.github.alvarosanchez.ocp.command.interactive;

import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.model.Profile;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import com.github.alvarosanchez.ocp.service.RepositoryService.ConfiguredRepository;
import dev.tamboui.style.Color;
import dev.tamboui.text.Text;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

public final class InteractiveApp extends ToolkitApp {

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
    private static final String ERROR_REPOSITORY_SELECTION_REQUIRED = "Repository selection is required.";
    private static final String STATUS_DELETE_CANCELLED_REPOSITORY_MISMATCH = "Delete cancelled: repository name mismatch.";
    private static final String STATUS_DELETE_CANCELLED_PROFILE_MISMATCH = "Delete cancelled: profile name mismatch.";
    private static final List<TreeShortcutHints.Shortcut> GLOBAL_SHORTCUTS = List.of(
        TreeShortcutHints.Shortcut.TAB_SWITCH_PANE,
        TreeShortcutHints.Shortcut.ADD_EXISTING_REPOSITORY,
        TreeShortcutHints.Shortcut.CREATE_NEW_REPOSITORY,
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
    private final ObjectMapper objectMapper;
    private final BatPreviewRenderer batPreviewRenderer = new BatPreviewRenderer();

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
    private Map<String, String> profileParentByName = Map.of();

    private Pane activePane = Pane.TREE;
    private String status = "Ready. Select a node in the hierarchy.";
    private PromptState prompt;
    private boolean splashVisible = true;
    private boolean splashMinimumElapsed;
    private boolean initialDataLoaded;
    private boolean busy;
    private String busyMessage;
    private int spinnerIndex;

    private RefreshConflictState refreshConflict;
    private RefreshOperation pendingRefreshOperation;
    private String refreshAllCompletionMessage = "Refreshed all repositories.";

    private NodeRef selectedNode;
    private String selectedFileContent = "";
    private Text selectedFilePreview = DetailPaneRenderer.plainText("");
    private List<String> splashLogoLines = DEFAULT_SPLASH_LOGO_LINES;
    private boolean editMode;
    private boolean batAvailable;
    private long previewRequestSequence;
    private int previewScrollOffset;

    public InteractiveApp(ProfileService profileService, RepositoryService repositoryService, ObjectMapper objectMapper) {
        this.profileService = profileService;
        this.repositoryService = repositoryService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void onStart() {
        loadSplashLogoLines();

        if (runner() == null) {
            reloadState();
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

        syncActivePaneFromFocus();
        syncSelectionAndPreview();
        TreeShortcutHints.ShortcutHints treeShortcutHints = TreeShortcutHints.forSelection(
            selectedNode,
            isSelectedRepositoryRefreshable(),
            selectedProfileHasParent()
        );

        List<Element> treePaneContent = new ArrayList<>();
        treePaneContent.add(hierarchyTree.fill());
        treePaneContent.add(ShortcutHintRenderer.line(treeShortcutHints.navigation()));
        if (!treeShortcutHints.actions().isEmpty()) {
            treePaneContent.add(ShortcutHintRenderer.line(treeShortcutHints.actions()));
        }

        Element root = column(
            panel(
                row(
                    text("OCP - OpenCode Configuration Profiles").bold().fg(Color.CYAN),
                    spacer(),
                    ShortcutHintRenderer.line(GLOBAL_SHORTCUTS)
                )
            ).rounded().borderColor(Color.CYAN).length(3),
            row(
                panel(
                    treePaneContent.toArray(Element[]::new)
                ).rounded().borderColor(activePane == Pane.TREE ? Color.GREEN : Color.GRAY).fill(),
                panel(renderDetailPane())
                    .rounded()
                    .borderColor(activePane == Pane.DETAIL ? Color.GREEN : Color.GRAY)
                    .fill()
            ).fill(),
            panel(text(statusLine())).rounded().borderColor(busy ? Color.GREEN : Color.YELLOW).length(3)
        );

        if (prompt != null) {
            return column(root, renderPromptDialog());
        }
        if (refreshConflict != null) {
            return column(root, renderRefreshConflictDialog());
        }
        return root;
    }

    private EventResult handleKeyEvent(KeyEvent event) {
        syncActivePaneFromFocus();

        if (splashVisible) {
            if (initialDataLoaded) {
                splashVisible = false;
            }
            return EventResult.HANDLED;
        }

        if (prompt != null) {
            return handlePromptKey(event);
        }

        if (refreshConflict != null) {
            return handleRefreshConflictKey(event);
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
                editMode = false;
                if (runner() != null) {
                    runner().focusManager().setFocus(DETAIL_ID);
                    activePane = Pane.DETAIL;
                }
                status = "Exited edit mode.";
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
        if (event.isChar('d')) {
            if (selectedNode == null) {
                status = STATUS_SELECT_NODE_FIRST;
                return EventResult.HANDLED;
            }

            if (selectedNode.kind() == NodeKind.REPOSITORY) {
                String repositoryName = selectedRepositoryName();
                if (repositoryName == null) {
                    status = STATUS_SELECT_NODE_FIRST;
                    return EventResult.HANDLED;
                }

                try {
                    RepositoryService.RepositoryDeletePreview deletePreview = repositoryService.inspectDelete(repositoryName);
                    if (!deletePreview.gitBacked()) {
                        prompt = PromptState.multiWithOptions(
                            PromptAction.DELETE_REPOSITORY_FILE_BASED,
                            "Delete file-based repository",
                            List.of(
                                "Type repository name to confirm: " + repositoryName,
                                "Delete local folder as well?"
                            ),
                            List.of(List.of(), List.of("no", "yes"))
                        );
                        prompt.expectedConfirmation = repositoryName;
                        return EventResult.HANDLED;
                    }

                    if (deletePreview.hasLocalChanges()) {
                        prompt = PromptState.single(
                            PromptAction.DELETE_REPOSITORY_FORCE,
                            "Delete repository (local changes detected)",
                            "Type repository name to force delete: " + repositoryName
                        );
                        prompt.expectedConfirmation = repositoryName;
                        return EventResult.HANDLED;
                    }

                    prompt = PromptState.single(
                        PromptAction.DELETE_REPOSITORY,
                        "Delete repository",
                        "Type repository name to confirm: " + repositoryName
                    );
                    prompt.expectedConfirmation = repositoryName;
                } catch (RuntimeException e) {
                    status = "Error: " + e.getMessage();
                }
                return EventResult.HANDLED;
            }

            String profileName = selectedProfileName();
            String repositoryName = selectedRepositoryName();
            if (profileName == null) {
                status = STATUS_SELECT_NODE_FIRST;
                return EventResult.HANDLED;
            }
            if (repositoryName == null) {
                status = STATUS_SELECT_NODE_FIRST;
                return EventResult.HANDLED;
            }
            prompt = PromptState.single(
                PromptAction.DELETE_PROFILE,
                "Delete profile",
                "Type profile name to confirm: " + profileName
            );
            prompt.expectedConfirmation = profileName;
            prompt.contextRepositoryName = repositoryName;
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
        if (event.isChar('c')) {
            String repositoryName = selectedRepositoryName();
            if (repositoryName == null) {
                status = STATUS_SELECT_NODE_FIRST;
                return EventResult.HANDLED;
            }
            try {
                List<String> parentOptions = new ArrayList<>();
                parentOptions.add("");
                parentOptions.addAll(profileService.listResolvableProfileNames());
                prompt = PromptState.multiWithOptions(
                    PromptAction.CREATE_PROFILE,
                    "Create profile",
                    List.of("Profile name", "Extends from profile (optional)"),
                    List.of(List.of(), parentOptions)
                );
                prompt.expectedConfirmation = repositoryName;
            } catch (RuntimeException e) {
                status = "Error: " + e.getMessage();
            }
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
        if (event.isChar('e')) {
            if (selectedNode != null && selectedNode.kind() == NodeKind.FILE) {
                if (selectedNode.inherited()) {
                    status = STATUS_INHERITED_FILE_READ_ONLY;
                    return EventResult.HANDLED;
                }
                editMode = true;
                resetEditorCursorToTop();
                if (runner() != null) {
                    runner().focusManager().setFocus(EDITOR_ID);
                    activePane = Pane.DETAIL;
                }
                status = "Editing `" + selectedNode.path().getFileName() + "`. Ctrl+S to save, Esc to cancel.";
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
                case CREATE_PROFILE -> {
                    String repositoryName = currentPrompt.expectedConfirmation;
                    if (repositoryName == null || repositoryName.isBlank()) {
                        throw new IllegalStateException(ERROR_REPOSITORY_SELECTION_REQUIRED);
                    }
                    String parentProfileName = currentPrompt.values.get(1);
                    profileService.createProfile(currentPrompt.values.getFirst(), repositoryName, parentProfileName);
                    if (parentProfileName == null || parentProfileName.isBlank()) {
                        statusMessage = "Created profile `" + currentPrompt.values.getFirst() + "` in repository `" + repositoryName + "`.";
                    } else {
                        statusMessage = "Created profile `"
                            + currentPrompt.values.getFirst()
                            + "` in repository `"
                            + repositoryName
                            + "` extending from `"
                            + parentProfileName
                            + "`.";
                    }
                }
                case ADD_REPOSITORY -> {
                    repositoryService.add(currentPrompt.values.getFirst(), currentPrompt.values.get(1));
                    statusMessage = "Added repository `" + currentPrompt.values.get(1) + "`.";
                }
                case DELETE_REPOSITORY -> {
                    String confirmation = currentPrompt.values.getFirst();
                    if (!confirmation.equals(currentPrompt.expectedConfirmation)) {
                        statusMessage = STATUS_DELETE_CANCELLED_REPOSITORY_MISMATCH;
                        reloadState();
                        status = statusMessage;
                        return;
                    }
                    repositoryService.delete(confirmation, false, false);
                    statusMessage = "Deleted repository `" + confirmation + "`.";
                }
                case DELETE_REPOSITORY_FORCE -> {
                    String confirmation = currentPrompt.values.getFirst();
                    if (!confirmation.equals(currentPrompt.expectedConfirmation)) {
                        statusMessage = STATUS_DELETE_CANCELLED_REPOSITORY_MISMATCH;
                        reloadState();
                        status = statusMessage;
                        return;
                    }
                    repositoryService.delete(confirmation, true, false);
                    statusMessage = "Deleted repository `" + confirmation + "` with local changes.";
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
                    repositoryService.delete(confirmation, false, deleteLocalFolder);
                    if (deleteLocalFolder) {
                        statusMessage = "Deleted repository `" + confirmation + "` and local folder.";
                    } else {
                        statusMessage = "Deleted repository `" + confirmation + "` (local folder kept).";
                    }
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
                    profileService.deleteProfile(profileName, repositoryName);
                    statusMessage = "Deleted profile `" + profileName + "` from repository `" + repositoryName + "`.";
                }
                case CREATE_REPOSITORY -> {
                    String repositoryName = currentPrompt.values.getFirst();
                    String locationPath = currentPrompt.values.get(1);
                    String profileName = currentPrompt.values.get(2).isBlank() ? null : currentPrompt.values.get(2);
                    repositoryService.createAndAdd(repositoryName, profileName, locationPath);
                    statusMessage = "Created and added repository `" + repositoryName + "`.";
                }
            }
            reloadState();
            status = statusMessage;
        } catch (RuntimeException e) {
            reloadState();
            status = "Error: " + e.getMessage();
        }
    }

    private void useSelectedProfile() {
        String profileName = selectedProfileName();
        if (profileName == null) {
            status = "Select a profile (or a file inside a profile) first.";
            return;
        }
        runBusyOperation(
            "Applying profile `" + profileName + "`...",
            () -> profileService.useProfile(profileName),
            "Switched to profile `" + profileName + "`.",
            null
        );
    }

    private void refreshSelectedRepository() {
        String repositoryName = selectedRepositoryName();
        if (repositoryName == null) {
            status = STATUS_SELECT_NODE_FIRST;
            return;
        }
        if (!isRepositoryRefreshable(repositoryName)) {
            status = "Repository `" + repositoryName + "` is file-based; nothing to refresh.";
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
            ? "Refreshed git-backed repositories. Skipped " + skippedFileBased + " file-based repositories."
            : "Refreshed all repositories.";
        pendingRefreshOperation = RefreshOperation.allRepositories();
        attemptPendingRefresh();
    }

    private void attemptPendingRefresh() {
        if (pendingRefreshOperation == null) {
            return;
        }
        if (pendingRefreshOperation.scope() == RefreshScope.SINGLE_REPOSITORY) {
            String repositoryName = pendingRefreshOperation.repositoryName();
            runBusyOperation(
                "Refreshing repository `" + repositoryName + "`...",
                () -> profileService.refreshRepository(repositoryName),
                "Refreshed repository `" + repositoryName + "`.",
                () -> pendingRefreshOperation = null
            );
            return;
        }
        runBusyOperation(
            "Refreshing all repositories...",
            profileService::refreshAllRepositories,
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
            if (repository.uri() != null && !repository.uri().isBlank()) {
                refreshable++;
            }
        }
        return refreshable;
    }

    private boolean isSelectedRepositoryRefreshable() {
        String repositoryName = selectedRepositoryName();
        return repositoryName != null && isRepositoryRefreshable(repositoryName);
    }

    private boolean isRepositoryRefreshable(String repositoryName) {
        for (ConfiguredRepository repository : repositories) {
            if (repository.name().equals(repositoryName)) {
                return repository.uri() != null && !repository.uri().isBlank();
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
        syncSelectionAndPreview();
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

    private Map<String, String> loadProfileParentByName() {
        Map<String, String> parentByName = new HashMap<>();
        for (ConfiguredRepository repository : repositories) {
            Path metadataFile = Path.of(repository.localPath()).resolve(REPOSITORY_METADATA_FILE);
            if (!Files.exists(metadataFile) || !Files.isRegularFile(metadataFile)) {
                continue;
            }
            try {
                RepositoryConfigFile repositoryConfig = objectMapper.readValue(Files.readString(metadataFile), RepositoryConfigFile.class);
                for (RepositoryConfigFile.ProfileEntry profileEntry : repositoryConfig.profiles()) {
                    if (profileEntry.extendsFrom() != null && !profileEntry.extendsFrom().isBlank()) {
                        parentByName.put(
                            profileKey(repository.name(), profileEntry.name()),
                            profileEntry.extendsFrom()
                        );
                    }
                }
            } catch (IOException | RuntimeException e) {
                status = "Error loading metadata from `" + metadataFile + "`: " + e.getMessage();
            }
        }
        return Map.copyOf(parentByName);
    }

    private StyledElement<?> renderTreeNode(TreeNode<NodeRef> node) {
        return HierarchyTreeBuilder.renderTreeNode(node, profilesByName, profileParentByName);
    }

    private void syncSelectionAndPreview() {
        TreeNode<NodeRef> selectedTreeNode = hierarchyTree.selectedNode();
        NodeRef nextSelectedNode = selectedTreeNode == null ? null : selectedTreeNode.data();
        if (Objects.equals(nextSelectedNode, selectedNode)) {
            return;
        }

        selectedNode = nextSelectedNode;
        editMode = false;
        previewScrollOffset = 0;

        if (selectedNode == null || selectedNode.kind() != NodeKind.FILE || selectedNode.path() == null) {
            selectedFileContent = "";
            selectedFilePreview = DetailPaneRenderer.plainText("");
            editorState.setText("");
            return;
        }

        try {
            selectedFileContent = Files.readString(selectedNode.path());
            selectedFilePreview = DetailPaneRenderer.plainText(selectedFileContent);
            requestBatPreview(selectedNode.path(), selectedFileContent);
            editorState.setText(selectedFileContent);
            resetEditorCursorToTop();
        } catch (IOException e) {
            selectedFileContent = "";
            selectedFilePreview = DetailPaneRenderer.plainText("");
            editorState.setText("");
            status = "Error loading file: " + e.getMessage();
        }
    }

    private Element renderDetailPane() {
        return DetailPaneRenderer.renderDetailPane(
            selectedNode,
            isSelectedRepositoryRefreshable(),
            selectedProfileHasParent(),
            editMode,
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

    private void requestBatPreview(Path filePath, String contentSnapshot) {
        if (filePath == null || runner() == null || !batAvailable) {
            return;
        }
        long requestId = ++previewRequestSequence;

        Thread.ofVirtual().start(() -> {
            Text parsed = batPreviewRenderer.highlight(filePath);
            if (parsed == null) {
                return;
            }

            runner().runOnRenderThread(() -> {
                if (requestId != previewRequestSequence) {
                    return;
                }
                if (selectedNode == null || selectedNode.kind() != NodeKind.FILE || selectedNode.path() == null) {
                    return;
                }
                if (!filePath.equals(selectedNode.path())) {
                    return;
                }
                if (!Objects.equals(contentSnapshot, selectedFileContent)) {
                    return;
                }
                selectedFilePreview = parsed;
            });
        });
    }

    private void saveSelectedFile() {
        if (selectedNode == null || selectedNode.kind() != NodeKind.FILE || selectedNode.path() == null) {
            return;
        }
        if (selectedNode.inherited()) {
            status = STATUS_INHERITED_FILE_READ_ONLY;
            return;
        }
        Path filePath = selectedNode.path();
        runBusyOperation(
            "Saving `" + filePath.getFileName() + "`...",
            () -> {
                try {
                    Files.writeString(filePath, editorState.text());
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to save file `" + filePath + "`.", e);
                }
            },
            "Saved `" + filePath.getFileName() + "`.",
            () -> {
                editMode = false;
                selectedFileContent = editorState.text();
                selectedFilePreview = DetailPaneRenderer.plainText(selectedFileContent);
                requestBatPreview(filePath, selectedFileContent);
                if (runner() != null) {
                    runner().focusManager().setFocus(DETAIL_ID);
                    activePane = Pane.DETAIL;
                }
            }
        );
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
            && profileParentByName.containsKey(profileKey(repositoryName, profileName));
    }

    private void navigateToParentProfile() {
        String repositoryName = selectedRepositoryName();
        String profileName = selectedProfileName();
        if (repositoryName == null || profileName == null) {
            status = "Select a profile or file first.";
            return;
        }

        String parentProfileName = profileParentByName.get(profileKey(repositoryName, profileName));
        if (parentProfileName == null || parentProfileName.isBlank()) {
            status = "Profile `" + profileName + "` does not extend another profile.";
            return;
        }

        String parentRepositoryName = repositoryNameForProfile(parentProfileName);
        if (parentRepositoryName == null || !selectProfileNode(parentRepositoryName, parentProfileName)) {
            status = "Parent profile `" + parentProfileName + "` was not found in the tree.";
            return;
        }

        syncSelectionAndPreview();
        if (runner() != null) {
            runner().focusManager().setFocus(TREE_ID);
            activePane = Pane.TREE;
        }
        status = "Selected parent profile `" + parentProfileName + "`.";
    }

    private boolean selectProfileNode(String repositoryName, String profileName) {
        int originalSelection = hierarchyTree.selected();
        int maxNodes = Math.max(1, countTreeNodes(hierarchyRoots));
        for (int index = 0; index < maxNodes; index++) {
            hierarchyTree.selected(index);
            TreeNode<NodeRef> selectedTreeNode = hierarchyTree.selectedNode();
            if (selectedTreeNode == null || selectedTreeNode.data() == null) {
                continue;
            }
            NodeRef selectedNodeRef = selectedTreeNode.data();
            if (selectedNodeRef.kind() == NodeKind.PROFILE
                && repositoryName.equals(selectedNodeRef.repositoryName())
                && profileName.equals(selectedNodeRef.profileName())) {
                return true;
            }
        }
        hierarchyTree.selected(originalSelection);
        return false;
    }

    private String repositoryNameForProfile(String profileName) {
        for (Profile profile : profiles) {
            if (profileName.equals(profile.name())) {
                return profile.repositoryName();
            }
        }
        return null;
    }

    private String profileKey(String repositoryName, String profileName) {
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
                    requestBatPreview(selectedNode.path(), selectedFileContent);
                }
            });
        } else {
            batAvailable = available;
        }
    }

    private void loadInitialDataInBackground() {
        try {
            reloadState();
        } finally {
            if (runner() != null) {
                runner().runOnRenderThread(() -> {
                    initialDataLoaded = true;
                    maybeHideSplash();
                });
            }
        }
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
        if (busy || runner() == null) {
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
            String finalMessage = successMessage;
            ProfileService.ProfileRefreshConflictException repositoryConflict = null;
            ProfileService.ProfileRefreshUserConfigConflictException mergedFilesConflict = null;
            boolean operationSucceeded = true;
            try {
                operation.run();
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
                "Discarding local changes in repository `" + conflict.repositoryName() + "`...",
                () -> profileService.resolveRefreshConflict(conflict, resolution),
                "Local changes discarded in repository `" + conflict.repositoryName() + "`.",
                this::attemptPendingRefresh
            );
            return;
        }

        runBusyOperation(
            "Committing local changes and force-pushing repository `" + conflict.repositoryName() + "`...",
            () -> profileService.resolveRefreshConflict(conflict, resolution),
            "Local changes committed and force-pushed for repository `" + conflict.repositoryName() + "`.",
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
            "Discarding local merged-file changes for profile `" + conflict.profileName() + "`...",
            () -> profileService.resolveRefreshConflict(conflict, ProfileService.RefreshConflictResolution.DISCARD_AND_REFRESH),
            "Local changes discarded in merged user config files for profile `" + conflict.profileName() + "`.",
            this::attemptPendingRefresh
        );
    }

    private Element renderRefreshConflictDialog() {
        return RefreshConflictDialogRenderer.render(refreshConflict, this::handleKeyEvent);
    }

    private Element renderPromptDialog() {
        int dialogWidth = promptDialogWidth(prompt);
        List<Element> promptContent = new ArrayList<>();
        promptContent.add(text(prompt.label()).bold());
        promptContent.add(text(promptDisplayValue(prompt)));
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
                status = "Splash logo file not found (`splash-logo.txt`), using default.";
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

}
