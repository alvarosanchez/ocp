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
    private Map<String, Profile> profilesByName = Map.of();
    private Map<String, String> profileParentByName = Map.of();

    private Pane activePane = Pane.TREE;
    private String status = "Ready. Select a node in the hierarchy.";
    private PromptState prompt;
    private boolean helpVisible;
    private boolean splashVisible = true;
    private boolean splashMinimumElapsed;
    private boolean initialDataLoaded;
    private boolean busy;
    private String busyMessage;
    private int spinnerIndex;

    private RefreshConflictState refreshConflict;
    private RefreshOperation pendingRefreshOperation;

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

        Element root = column(
            panel(
                row(
                    text("OCP - OpenCode Configuration Profiles").bold().fg(Color.CYAN),
                    spacer(),
                    text("Tab switch pane | q quit").dim()
                )
            ).rounded().borderColor(Color.CYAN).length(3),
            row(
                panel(
                    hierarchyTree.fill(),
                    text("Enter: refresh repo / use profile / edit file | Space: expand/collapse").dim(),
                    text("u use profile | R refresh all | a add repo | d delete repo | n create repo | c create profile").dim()
                ).rounded().borderColor(activePane == Pane.TREE ? Color.GREEN : Color.GRAY).fill(),
                panel(
                    renderDetailPane(),
                    text(DetailPaneRenderer.detailHint(selectedNode, editMode)).dim().fg(Color.YELLOW)
                )
                    .rounded()
                    .borderColor(activePane == Pane.DETAIL ? Color.GREEN : Color.GRAY)
                    .fill()
            ).fill(),
            panel(text(statusLine())).rounded().borderColor(busy ? Color.GREEN : Color.YELLOW).length(3),
            panel(
                helpVisible
                    ? column(
                        text("Tree: Up/Down select | Left/Right collapse-expand").dim(),
                        text("      Enter action | Space toggle folder").dim(),
                        text("Actions: u use profile | R refresh all | a add repo").dim(),
                        text("         d delete repo | n create repo | c create profile").dim(),
                        text("File: e edit | Ctrl+S save | Esc exit edit").dim(),
                        text("      Up/Down/PgUp/PgDn/Home/End scroll preview").dim(),
                        text("Global: Tab switch pane | r reload | q quit").dim()
                    )
                    : text("Press ? for interactive keymap").dim()
            ).rounded().length(helpVisible ? 10 : 3)
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
        if (event.isChar('?')) {
            helpVisible = !helpVisible;
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
            reloadState();
            status = "Reloaded repositories and profiles.";
            return EventResult.HANDLED;
        }
        if (event.isChar('R')) {
            refreshAllRepositories();
            return EventResult.HANDLED;
        }
        if (event.isChar('a')) {
            prompt = PromptState.multi(PromptAction.ADD_REPOSITORY, "Add repository", List.of("Repository URI", "Repository name"));
            return EventResult.HANDLED;
        }
        if (event.isChar('d')) {
            String repositoryName = selectedRepositoryName();
            if (repositoryName == null) {
                status = "Select a repository node first.";
                return EventResult.HANDLED;
            }
            prompt = PromptState.single(PromptAction.DELETE_REPOSITORY, "Delete repository", "Type repository name to confirm: " + repositoryName);
            prompt.expectedConfirmation = repositoryName;
            return EventResult.HANDLED;
        }
        if (event.isChar('n')) {
            prompt = PromptState.multi(
                PromptAction.CREATE_REPOSITORY,
                "Create repository scaffold",
                List.of("Repository directory name", "Initial profile name (optional)")
            );
            return EventResult.HANDLED;
        }
        if (event.isChar('c')) {
            prompt = PromptState.single(PromptAction.CREATE_PROFILE, "Create profile", "Profile name");
            return EventResult.HANDLED;
        }
        if (event.isChar('u')) {
            useSelectedProfile();
            return EventResult.HANDLED;
        }
        if (event.isChar('e')) {
            if (selectedNode != null && selectedNode.kind() == NodeKind.FILE) {
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
        if (event.isChar(' ')) {
            hierarchyTree.toggleSelected();
            return EventResult.HANDLED;
        }
        if (event.isConfirm()) {
            return handleTreeConfirm();
        }
        return EventResult.UNHANDLED;
    }

    private EventResult handleTreeConfirm() {
        TreeNode<NodeRef> selected = hierarchyTree.selectedNode();
        if (selected == null || selected.data() == null) {
            return EventResult.HANDLED;
        }

        NodeRef nodeRef = selected.data();
        if (nodeRef.kind() == NodeKind.REPOSITORY) {
            refreshSelectedRepository();
            return EventResult.HANDLED;
        }
        if (nodeRef.kind() == NodeKind.PROFILE) {
            useSelectedProfile();
            return EventResult.HANDLED;
        }
        if (nodeRef.kind() == NodeKind.DIRECTORY) {
            hierarchyTree.toggleSelected();
            return EventResult.HANDLED;
        }
        if (nodeRef.kind() == NodeKind.FILE) {
            editMode = true;
            resetEditorCursorToTop();
            if (runner() != null) {
                runner().focusManager().setFocus(EDITOR_ID);
                activePane = Pane.DETAIL;
            }
            status = "Editing `" + nodeRef.path().getFileName() + "`. Ctrl+S to save, Esc to cancel.";
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    private EventResult handlePromptKey(KeyEvent event) {
        if (event.isCancel()) {
            prompt = null;
            status = "Cancelled.";
            return EventResult.HANDLED;
        }
        if (event.isDeleteBackward()) {
            prompt.deleteLast();
            return EventResult.HANDLED;
        }
        if (event.isFocusNext() || event.isKey(dev.tamboui.tui.event.KeyCode.TAB)) {
            prompt.nextField();
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
            switch (currentPrompt.action) {
                case CREATE_PROFILE -> {
                    profileService.createProfile(currentPrompt.values.getFirst());
                    status = "Created profile `" + currentPrompt.values.getFirst() + "`.";
                }
                case ADD_REPOSITORY -> {
                    repositoryService.add(currentPrompt.values.getFirst(), currentPrompt.values.get(1));
                    status = "Added repository `" + currentPrompt.values.get(1) + "`.";
                }
                case DELETE_REPOSITORY -> {
                    String confirmation = currentPrompt.values.getFirst();
                    if (!confirmation.equals(currentPrompt.expectedConfirmation)) {
                        status = "Delete cancelled: repository name mismatch.";
                        reloadState();
                        return;
                    }
                    repositoryService.delete(confirmation);
                    status = "Deleted repository `" + confirmation + "`.";
                }
                case CREATE_REPOSITORY -> {
                    String profileName = currentPrompt.values.get(1).isBlank() ? null : currentPrompt.values.get(1);
                    repositoryService.create(currentPrompt.values.getFirst(), profileName);
                    status = "Created repository scaffold `" + currentPrompt.values.getFirst() + "`.";
                }
            }
            reloadState();
        } catch (RuntimeException e) {
            status = "Error: " + e.getMessage();
            reloadState();
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
            status = "Select a repository, profile, or file first.";
            return;
        }
        pendingRefreshOperation = RefreshOperation.singleRepository(repositoryName);
        attemptPendingRefresh();
    }

    private void refreshAllRepositories() {
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
            "Refreshed all repositories.",
            () -> pendingRefreshOperation = null
        );
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
            byName.put(profile.name(), profile);
        }
        profilesByName = Map.copyOf(byName);
        profileParentByName = loadProfileParentByName();

        List<TreeNode<NodeRef>> roots = buildHierarchyTree();
        hierarchyTree.roots(roots.toArray(TreeNode[]::new));
        hierarchyTree.selected(Math.max(0, previousSelection));
        syncSelectionAndPreview();
    }

    private List<TreeNode<NodeRef>> buildHierarchyTree() {
        return HierarchyTreeBuilder.buildHierarchyTree(repositories, profiles, TREE_MAX_DEPTH, TREE_MAX_CHILDREN);
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
                        parentByName.put(profileEntry.name(), profileEntry.extendsFrom());
                    }
                }
            } catch (IOException | RuntimeException e) {
                status = "Error loading metadata from `" + metadataFile + "`: " + e.getMessage();
            }
        }
        return Map.copyOf(parentByName);
    }

    private StyledElement<?> renderTreeNode(TreeNode<NodeRef> node) {
        return HierarchyTreeBuilder.renderTreeNode(node, profilesByName);
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
            status = "Loaded `" + selectedNode.path().getFileName() + "`. Press e to edit.";
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
        return dialog(
            prompt.title,
            text(prompt.label()).bold(),
            text(prompt.currentValue()),
            text("Enter next/apply | Tab next field | Backspace delete | Esc cancel").dim()
        ).rounded().borderColor(Color.MAGENTA);
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
