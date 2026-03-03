package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.model.Profile;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import com.github.alvarosanchez.ocp.service.RepositoryService.ConfiguredRepository;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.RichTextAreaElement;
import dev.tamboui.toolkit.elements.TextAreaElement;
import dev.tamboui.toolkit.elements.TreeElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.tree.TreeNode;
import io.micronaut.serde.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.richTextArea;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textArea;

final class OcpInteractiveApp extends ToolkitApp {

    private static final String TREE_ID = "hierarchy-tree";
    private static final String DETAIL_ID = "detail-pane";
    private static final String EDITOR_ID = "file-editor";
    private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};
    private static final Duration SPLASH_MIN_DURATION = Duration.ofMillis(120);
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[;?0-9]*[ -/]*[@-~]");
    private static final int CONFLICT_DIFF_PREVIEW_LINES = 20;
    private static final int CONFLICT_FILES_PREVIEW_LINES = 12;
    private static final int TREE_MAX_DEPTH = 6;
    private static final int TREE_MAX_CHILDREN = 200;
    private static final String REPOSITORY_METADATA_FILE = "repository.json";

    private final ProfileService profileService;
    private final RepositoryService repositoryService;
    private final ObjectMapper objectMapper;

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
    private Text selectedFilePreview = Text.from(Line.from(Span.raw("")));
    private boolean editMode;
    private Boolean batAvailable;
    private long previewRequestSequence;
    private int previewScrollOffset;

    OcpInteractiveApp(ProfileService profileService, RepositoryService repositoryService, ObjectMapper objectMapper) {
        this.profileService = profileService;
        this.repositoryService = repositoryService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void onStart() {
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
                    text(detailHint()).dim().fg(Color.YELLOW)
                )
                    .rounded()
                    .borderColor(activePane == Pane.DETAIL ? Color.GREEN : Color.GRAY)
                    .fill()
            ).fill(),
            panel(text(statusLine())).rounded().borderColor(busy ? Color.GREEN : Color.YELLOW).length(3),
            panel(
                text(helpVisible ? helpText() : "Press ? for interactive keymap").dim()
            ).rounded().length(helpVisible ? 8 : 3)
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
        String activeRepositoryName = activeRepositoryName();
        String activeProfileName = activeProfileName();

        List<TreeNode<NodeRef>> roots = new ArrayList<>();
        List<ConfiguredRepository> sortedRepositories = repositories.stream()
            .sorted(Comparator
                .comparing((ConfiguredRepository repository) -> !repository.name().equals(activeRepositoryName))
                .thenComparing(repository -> repository.name().toLowerCase()))
            .toList();

        for (ConfiguredRepository repository : sortedRepositories) {
            Path repositoryPath = Path.of(repository.localPath());
            TreeNode<NodeRef> repositoryNode = TreeNode.of(
                repository.name(),
                NodeRef.repository(repository.name(), repositoryPath)
            ).expanded(true);

            List<String> sortedProfiles = repository.resolvedProfiles().stream()
                .sorted(Comparator
                    .comparing((String profileName) -> !profileName.equals(activeProfileName))
                    .thenComparing(profileName -> profileName.toLowerCase()))
                .toList();

            for (String profileName : sortedProfiles) {
                Path profilePath = repositoryPath.resolve(profileName);
                Profile profile = profilesByName.get(profileName);
                TreeNode<NodeRef> profileNode = TreeNode.of(
                    profileLabel(profileName, profile),
                    NodeRef.profile(repository.name(), profileName, profilePath)
                );

                if (Files.isDirectory(profilePath)) {
                    for (TreeNode<NodeRef> child : buildDirectoryNodes(repository.name(), profileName, profilePath, 0)) {
                        profileNode.add(child);
                    }
                    profileNode.expanded(true);
                } else {
                    profileNode.leaf();
                }

                repositoryNode.add(profileNode);
            }

            roots.add(repositoryNode);
        }
        return roots;
    }

    private List<TreeNode<NodeRef>> buildDirectoryNodes(
        String repositoryName,
        String profileName,
        Path directory,
        int depth
    ) {
        if (depth > TREE_MAX_DEPTH) {
            return List.of(TreeNode.of("...", NodeRef.directory(repositoryName, profileName, directory)).leaf());
        }

        List<TreeNode<NodeRef>> children = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            List<Path> sorted = paths
                .sorted(Comparator
                    .comparing((Path path) -> Files.isDirectory(path) ? 0 : 1)
                    .thenComparing(path -> path.getFileName().toString().toLowerCase()))
                .limit(TREE_MAX_CHILDREN)
                .toList();

            for (Path child : sorted) {
                if (Files.isDirectory(child)) {
                    TreeNode<NodeRef> directoryNode = TreeNode.of(
                        child.getFileName().toString() + "/",
                        NodeRef.directory(repositoryName, profileName, child)
                    );
                    for (TreeNode<NodeRef> nested : buildDirectoryNodes(repositoryName, profileName, child, depth + 1)) {
                        directoryNode.add(nested);
                    }
                    children.add(directoryNode);
                } else if (Files.isRegularFile(child)) {
                    children.add(
                        TreeNode.of(
                            child.getFileName().toString(),
                            NodeRef.file(repositoryName, profileName, child)
                        ).leaf()
                    );
                }
            }
        } catch (IOException e) {
            children.add(TreeNode.of("[error reading directory]", NodeRef.directory(repositoryName, profileName, directory)).leaf());
        }

        return children;
    }

    private String profileLabel(String profileName, Profile profile) {
        return profileName;
    }

    private String activeProfileName() {
        return profiles.stream()
            .filter(Profile::active)
            .map(Profile::name)
            .findFirst()
            .orElse(null);
    }

    private String activeRepositoryName() {
        return profiles.stream()
            .filter(Profile::active)
            .map(Profile::repositoryName)
            .findFirst()
            .orElse(null);
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
        NodeRef data = node.data();
        if (data == null) {
            return text(node.label());
        }

        String icon = switch (data.kind()) {
            case REPOSITORY -> "◆ ";
            case PROFILE -> "● ";
            case DIRECTORY -> "📁 ";
            case FILE -> "📄 ";
        };

        Color iconColor = switch (data.kind()) {
            case REPOSITORY -> Color.LIGHT_YELLOW;
            case PROFILE -> Color.MAGENTA;
            case DIRECTORY -> Color.BLUE;
            case FILE -> Color.GRAY;
        };

        Style labelStyle = Style.EMPTY;
        boolean isCurrentProfile = false;
        boolean hasUpdates = false;
        if (data.kind() == NodeKind.PROFILE) {
            Profile profile = profilesByName.get(data.profileName());
            if (profile != null && profile.active()) {
                labelStyle = labelStyle.bold().fg(Color.BRIGHT_WHITE);
                isCurrentProfile = true;
            }
            if (profile != null && profile.updateAvailable()) {
                hasUpdates = true;
            }
        }

        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled(icon, Style.EMPTY.bold().fg(iconColor)));
        spans.add(Span.styled(node.label(), labelStyle));
        if (isCurrentProfile) {
            spans.add(Span.styled(" (current)", Style.EMPTY.bold().fg(Color.GREEN)));
        }
        if (hasUpdates) {
            spans.add(Span.styled(" (updates)", Style.EMPTY.bold().fg(Color.YELLOW)));
        }

        return richText(
            Text.from(
                Line.from(spans)
            )
        );
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
            selectedFilePreview = plainText("");
            editorState.setText("");
            return;
        }

        try {
            selectedFileContent = Files.readString(selectedNode.path());
            selectedFilePreview = plainText(selectedFileContent);
            requestBatPreview(selectedNode.path(), selectedFileContent);
            editorState.setText(selectedFileContent);
            resetEditorCursorToTop();
            status = "Loaded `" + selectedNode.path().getFileName() + "`. Press e to edit.";
        } catch (IOException e) {
            selectedFileContent = "";
            selectedFilePreview = plainText("");
            editorState.setText("");
            status = "Error loading file: " + e.getMessage();
        }
    }

    private Element renderDetailPane() {
        if (selectedNode == null) {
            return column(
                text("Select a repository, profile, or file on the left.").dim(),
                text("Files open here with syntax-colored preview.").dim()
            )
                .id(DETAIL_ID)
                .focusable()
                .onKeyEvent(this::handleKeyEvent);
        }

        if (selectedNode.kind() == NodeKind.REPOSITORY) {
            return column(
                text("Repository").bold().fg(Color.CYAN),
                detailField("Name", selectedNode.repositoryName()),
                detailField("Path", String.valueOf(selectedNode.path())),
                text("Enter to refresh this repository.").dim()
            )
                .id(DETAIL_ID)
                .focusable()
                .onKeyEvent(this::handleKeyEvent);
        }

        if (selectedNode.kind() == NodeKind.PROFILE) {
            Profile profile = profilesByName.get(selectedNode.profileName());
            String parentProfileName = profileParentByName.get(selectedNode.profileName());
            return column(
                text("Profile").bold().fg(Color.CYAN),
                detailField("Name", selectedNode.profileName()),
                detailField("Repository", selectedNode.repositoryName()),
                detailField("Path", String.valueOf(selectedNode.path())),
                detailField("Inherits from", parentProfileName == null ? "none" : parentProfileName),
                detailField("Status", profile != null && profile.active() ? "active" : "inactive"),
                detailField("Updates", profile != null && profile.updateAvailable() ? "available" : "up to date"),
                text("Enter or u to activate this profile.").dim()
            )
                .id(DETAIL_ID)
                .focusable()
                .onKeyEvent(this::handleKeyEvent);
        }

        if (selectedNode.kind() == NodeKind.DIRECTORY) {
            return column(
                text("Directory").bold().fg(Color.CYAN),
                detailField("Path", String.valueOf(selectedNode.path())),
                text("Space/Enter to expand or collapse in tree.").dim()
            )
                .id(DETAIL_ID)
                .focusable()
                .onKeyEvent(this::handleKeyEvent);
        }

        if (editMode) {
            TextAreaElement editor = textArea(editorState)
                .title("Editing: " + selectedNode.path().getFileName())
                .rounded()
                .borderColor(Color.GREEN)
                .focusedBorderColor(Color.GREEN)
                .showLineNumbers()
                .id(EDITOR_ID)
                .focusable()
                .onKeyEvent(this::handleKeyEvent)
                .fill();
            return editor;
        }

        RichTextAreaElement preview = richTextArea(selectedFilePreview)
            .text(scrolledPreviewText())
            .title("Preview: " + selectedNode.path().getFileName())
            .rounded()
            .borderColor(Color.CYAN)
            .focusedBorderColor(Color.GREEN)
            .showLineNumbers()
            .scrollbar()
            .id(DETAIL_ID)
            .focusable()
            .onKeyEvent(this::handlePreviewKeyEvent)
            .fill();
        return preview;
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
        int nextOffset = previewScrollOffset;
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

    private Text scrolledPreviewText() {
        List<Line> lines = selectedFilePreview.lines();
        if (lines.isEmpty()) {
            return Text.empty();
        }
        int safeOffset = Math.max(0, Math.min(previewScrollOffset, lines.size() - 1));
        if (safeOffset == 0) {
            return selectedFilePreview;
        }
        return Text.from(lines.subList(safeOffset, lines.size()));
    }

    private String detailHint() {
        if (selectedNode == null) {
            return "Detail pane";
        }
        if (selectedNode.kind() == NodeKind.FILE) {
            if (editMode) {
                return "Editing mode: Ctrl+S save, Esc exit";
            }
            return "Press e or Enter to edit selected file | Up/Down/PgUp/PgDn/Home/End scroll preview";
        }
        return "Detail pane";
    }

    private Element detailField(String label, String value) {
        return richText(
            Text.from(
                Line.from(
                    Span.styled(label + ": ", Style.EMPTY.bold().fg(Color.LIGHT_YELLOW)),
                    Span.styled(value == null ? "" : value, Style.EMPTY.fg(Color.BRIGHT_WHITE))
                )
            )
        );
    }

    private Text plainText(String content) {
        String normalized = content == null ? "" : content;
        String[] splitLines = normalized.split("\\R", -1);
        List<Line> lines = new ArrayList<>(splitLines.length);
        for (String line : splitLines) {
            lines.add(Line.from(Span.raw(line)));
        }
        return Text.from(lines);
    }

    private void requestBatPreview(Path filePath, String contentSnapshot) {
        if (filePath == null || runner() == null || !isBatAvailable()) {
            return;
        }
        long requestId = ++previewRequestSequence;

        Thread.ofVirtual().start(() -> {
            String highlighted = highlightWithBat(filePath);
            if (highlighted == null) {
                return;
            }
            Text parsed = parseAnsiHighlightedText(highlighted);

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

    private String highlightWithBat(Path filePath) {
        if (!isBatAvailable()) {
            return null;
        }
        List<String> command = new ArrayList<>();
        command.add("bat");
        command.add("--color=always");
        command.add("--style=plain");
        command.add("--paging=never");

        String language = batLanguage(extension(filePath));
        if (language != null) {
            command.add("--language");
            command.add(language);
        }
        command.add(filePath.toString());

        Process process;
        try {
            process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        } catch (IOException e) {
            return null;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                process.getInputStream().transferTo(output);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        try {
            boolean completed = process.waitFor(Duration.ofSeconds(8).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                reader.join(Duration.ofMillis(200).toMillis());
                return null;
            }
            reader.join(Duration.ofSeconds(1).toMillis());
            if (process.exitValue() != 0) {
                return null;
            }
            return output.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (UncheckedIOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private boolean isBatAvailable() {
        if (batAvailable != null) {
            return batAvailable;
        }
        Process process;
        try {
            process = new ProcessBuilder("bat", "--version").start();
        } catch (IOException e) {
            batAvailable = false;
            return false;
        }

        try {
            boolean completed = process.waitFor(Duration.ofSeconds(1).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            batAvailable = completed && process.exitValue() == 0;
            return batAvailable;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            batAvailable = false;
            return false;
        }
    }

    private String batLanguage(String extension) {
        return switch (extension) {
            case "jsonc" -> "json";
            case "json" -> "json";
            case "yaml", "yml" -> "yaml";
            case "toml" -> "toml";
            case "properties" -> "properties";
            case "java" -> "java";
            case "kts", "kt" -> "kotlin";
            case "groovy" -> "groovy";
            case "sh", "bash" -> "bash";
            case "js" -> "javascript";
            case "ts" -> "typescript";
            case "py" -> "python";
            default -> null;
        };
    }

    private Text parseAnsiHighlightedText(String ansi) {
        if (ansi == null || ansi.isEmpty()) {
            return Text.from(Line.from(Span.raw("")));
        }

        List<Line> lines = new ArrayList<>();
        List<Span> currentLine = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        Style currentStyle = Style.EMPTY;

        int index = 0;
        while (index < ansi.length()) {
            char character = ansi.charAt(index);
            if (character == '\u001B') {
                SgrParseResult sgrResult = consumeSgrSequence(ansi, index, run, currentLine, currentStyle);
                if (sgrResult != null) {
                    currentStyle = sgrResult.style();
                    index += sgrResult.consumed();
                    continue;
                }
            }

            if (character == '\r') {
                index++;
                continue;
            }
            if (character == '\n') {
                flushStyledSpan(run, currentStyle, currentLine);
                lines.add(Line.from(currentLine));
                currentLine = new ArrayList<>();
                index++;
                continue;
            }

            run.append(character);
            index++;
        }

        flushStyledSpan(run, currentStyle, currentLine);
        if (lines.isEmpty() || !currentLine.isEmpty()) {
            lines.add(Line.from(currentLine));
        }
        return Text.from(lines);
    }

    private SgrParseResult consumeSgrSequence(
        String ansi,
        int startIndex,
        StringBuilder run,
        List<Span> currentLine,
        Style styleBeforeSequence
    ) {
        if (startIndex + 1 >= ansi.length() || ansi.charAt(startIndex + 1) != '[') {
            return null;
        }

        int mIndex = ansi.indexOf('m', startIndex + 2);
        if (mIndex < 0) {
            return null;
        }

        flushStyledSpan(run, styleBeforeSequence, currentLine);
        String codes = ansi.substring(startIndex + 2, mIndex);
        Style nextStyle = applySgrCodes(styleBeforeSequence, codes);
        return new SgrParseResult((mIndex - startIndex) + 1, nextStyle);
    }

    private record SgrParseResult(int consumed, Style style) {
    }

    private void flushStyledSpan(StringBuilder run, Style style, List<Span> target) {
        if (run.isEmpty()) {
            return;
        }
        String value = run.toString();
        run.setLength(0);
        target.add(Span.styled(value, style == null ? Style.EMPTY : style));
    }

    private Style applySgrCodes(Style baseStyle, String codeBlock) {
        SgrState state = SgrState.from(baseStyle);
        if (codeBlock == null || codeBlock.isBlank()) {
            return SgrState.empty().toStyle();
        }

        String[] parts = codeBlock.split(";", -1);
        for (int index = 0; index < parts.length; index++) {
            int code = parseSgrCode(parts[index]);
            if (code < 0) {
                continue;
            }

            if (code == 0) {
                state = SgrState.empty();
                continue;
            }

            if (code == 1) {
                state.bold = true;
                continue;
            }
            if (code == 2) {
                state.dim = true;
                continue;
            }
            if (code == 3) {
                state.italic = true;
                continue;
            }
            if (code == 4) {
                state.underlined = true;
                continue;
            }
            if (code == 5) {
                state.slowBlink = true;
                continue;
            }
            if (code == 7) {
                state.reversed = true;
                continue;
            }
            if (code == 8) {
                state.hidden = true;
                continue;
            }
            if (code == 9) {
                state.crossedOut = true;
                continue;
            }
            if (code == 22) {
                state.bold = false;
                state.dim = false;
                continue;
            }
            if (code == 23) {
                state.italic = false;
                continue;
            }
            if (code == 24) {
                state.underlined = false;
                continue;
            }
            if (code == 25) {
                state.slowBlink = false;
                continue;
            }
            if (code == 27) {
                state.reversed = false;
                continue;
            }
            if (code == 28) {
                state.hidden = false;
                continue;
            }
            if (code == 29) {
                state.crossedOut = false;
                continue;
            }

            if (code == 39) {
                state.fg = null;
                continue;
            }
            if (code == 49) {
                state.bg = null;
                continue;
            }

            if (code >= 30 && code <= 37) {
                state.fg = Color.indexed(code - 30);
                continue;
            }
            if (code >= 90 && code <= 97) {
                state.fg = Color.indexed((code - 90) + 8);
                continue;
            }
            if (code >= 40 && code <= 47) {
                state.bg = Color.indexed(code - 40);
                continue;
            }
            if (code >= 100 && code <= 107) {
                state.bg = Color.indexed((code - 100) + 8);
                continue;
            }

            if ((code == 38 || code == 48) && index + 1 < parts.length) {
                int mode = parseSgrCode(parts[index + 1]);
                if (mode == 5 && index + 2 < parts.length) {
                    int colorIndex = parseSgrCode(parts[index + 2]);
                    if (colorIndex >= 0) {
                        if (code == 38) {
                            state.fg = Color.indexed(colorIndex);
                        } else {
                            state.bg = Color.indexed(colorIndex);
                        }
                    }
                    index += 2;
                    continue;
                }
                if (mode == 2 && index + 4 < parts.length) {
                    int red = parseSgrCode(parts[index + 2]);
                    int green = parseSgrCode(parts[index + 3]);
                    int blue = parseSgrCode(parts[index + 4]);
                    if (red >= 0 && green >= 0 && blue >= 0) {
                        Color rgb = Color.rgb(clampColor(red), clampColor(green), clampColor(blue));
                        if (code == 38) {
                            state.fg = rgb;
                        } else {
                            state.bg = rgb;
                        }
                    }
                    index += 4;
                }
            }
        }

        return state.toStyle();
    }

    private int parseSgrCode(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private int clampColor(int component) {
        if (component < 0) {
            return 0;
        }
        return Math.min(component, 255);
    }

    private static final class SgrState {
        private Color fg;
        private Color bg;
        private boolean bold;
        private boolean dim;
        private boolean italic;
        private boolean underlined;
        private boolean slowBlink;
        private boolean reversed;
        private boolean hidden;
        private boolean crossedOut;

        private static SgrState empty() {
            return new SgrState();
        }

        private static SgrState from(Style style) {
            SgrState state = empty();
            if (style == null) {
                return state;
            }
            style.fg().ifPresent(color -> state.fg = color);
            style.bg().ifPresent(color -> state.bg = color);
            var modifiers = style.effectiveModifiers();
            state.bold = modifiers.contains(dev.tamboui.style.Modifier.BOLD);
            state.dim = modifiers.contains(dev.tamboui.style.Modifier.DIM);
            state.italic = modifiers.contains(dev.tamboui.style.Modifier.ITALIC);
            state.underlined = modifiers.contains(dev.tamboui.style.Modifier.UNDERLINED);
            state.slowBlink = modifiers.contains(dev.tamboui.style.Modifier.SLOW_BLINK);
            state.reversed = modifiers.contains(dev.tamboui.style.Modifier.REVERSED);
            state.hidden = modifiers.contains(dev.tamboui.style.Modifier.HIDDEN);
            state.crossedOut = modifiers.contains(dev.tamboui.style.Modifier.CROSSED_OUT);
            return state;
        }

        private Style toStyle() {
            Style style = Style.EMPTY;
            if (fg != null) {
                style = style.fg(fg);
            }
            if (bg != null) {
                style = style.bg(bg);
            }
            if (bold) {
                style = style.bold();
            }
            if (dim) {
                style = style.dim();
            }
            if (italic) {
                style = style.italic();
            }
            if (underlined) {
                style = style.underlined();
            }
            if (slowBlink) {
                style = style.slowBlink();
            }
            if (reversed) {
                style = style.reversed();
            }
            if (hidden) {
                style = style.hidden();
            }
            if (crossedOut) {
                style = style.crossedOut();
            }
            return style;
        }
    }

    private String extension(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        String fileName = path.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1).toLowerCase();
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
                selectedFilePreview = plainText(selectedFileContent);
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
            status = "Refresh cancelled. Local changes were left untouched.";
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
                status = "Refresh cancelled. Local changes were left untouched.";
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
            status = "Refresh cancelled. Local changes were left untouched.";
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
        if (refreshConflict.kind() == RefreshConflictKind.REPOSITORY) {
            ProfileService.ProfileRefreshConflictException conflict = refreshConflict.repositoryConflict();
            List<Element> content = new ArrayList<>();
            content.add(text("Local uncommitted changes detected in repository `" + conflict.repositoryName() + "`.").fg(Color.YELLOW));
            content.add(text("Diff:").bold().fg(Color.CYAN));
            content.addAll(buildColoredDiffPreview(conflict.diff()));
            content.add(text("Choose how to proceed:").bold());
            content.add(text("1) Discard local changes and refresh from repository.").fg(Color.YELLOW));
            content.add(text("2) Commit local changes and force push to remote.").fg(Color.YELLOW));
            content.add(text("3) Do nothing and fix manually.").fg(Color.YELLOW));
            content.add(text("Press 1/2/3 to choose").dim());

            return dialog("Refresh conflict", content.toArray(Element[]::new))
                .rounded()
                .borderColor(Color.YELLOW)
                .width(132)
                .padding(1)
                .focusable()
                .onKeyEvent(this::handleKeyEvent);
        }

        ProfileService.ProfileRefreshUserConfigConflictException conflict = refreshConflict.mergedFilesConflict();
        List<Element> content = new ArrayList<>();
        content.add(
            text("Local changes detected in merged active profile files for profile `" + conflict.profileName() + "`.")
                .fg(Color.YELLOW)
        );
        content.add(text("Modified files in `" + conflict.targetDirectory() + "`: ").fg(Color.CYAN));
        int displayed = 0;
        for (Path driftedFile : conflict.driftedFiles()) {
            if (displayed >= CONFLICT_FILES_PREVIEW_LINES) {
                break;
            }
            content.add(text("- " + driftedFile));
            displayed++;
        }
        if (conflict.driftedFiles().size() > displayed) {
            content.add(text("... and " + (conflict.driftedFiles().size() - displayed) + " more files").dim());
        }
        content.add(text("Choose how to proceed:").bold());
        content.add(text("1) Discard local merged-file changes and refresh.").fg(Color.YELLOW));
        content.add(text("2) Do nothing and fix manually.").fg(Color.YELLOW));
        content.add(text("Press 1/2 to choose").dim());

        return dialog("Refresh conflict", content.toArray(Element[]::new))
            .rounded()
            .borderColor(Color.YELLOW)
            .width(132)
            .padding(1)
            .focusable()
            .onKeyEvent(this::handleKeyEvent);
    }

    private List<Element> buildColoredDiffPreview(String diff) {
        String normalized = stripAnsi(diff);
        if (normalized.isBlank()) {
            return List.of(text("No diff output available.").dim());
        }

        List<Element> lines = new ArrayList<>();
        String[] splitLines = normalized.split("\\R", -1);
        int maxLines = Math.min(CONFLICT_DIFF_PREVIEW_LINES, splitLines.length);
        for (int index = 0; index < maxLines; index++) {
            lines.add(colorizeDiffLine(splitLines[index]));
        }
        if (splitLines.length > maxLines) {
            lines.add(text("... diff truncated (" + (splitLines.length - maxLines) + " more lines)").dim());
        }
        return lines;
    }

    private Element colorizeDiffLine(String line) {
        String safeLine = line.length() > 124 ? line.substring(0, 124) + "..." : line;
        if (safeLine.startsWith("+++ ") || safeLine.startsWith("--- ") || safeLine.startsWith("@@")) {
            return text(safeLine).fg(Color.CYAN);
        }
        if (safeLine.startsWith("+")) {
            return text(safeLine).fg(Color.GREEN);
        }
        if (safeLine.startsWith("-")) {
            return text(safeLine).fg(Color.RED);
        }
        return text(safeLine);
    }

    private String stripAnsi(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return ANSI_ESCAPE_PATTERN.matcher(input).replaceAll("");
    }

    private Element renderPromptDialog() {
        return dialog(
            prompt.title,
            text(prompt.label()).bold(),
            text(prompt.currentValue()),
            text("Enter next/apply | Tab next field | Backspace delete | Esc cancel").dim()
        ).rounded().borderColor(Color.MAGENTA);
    }

    private String helpText() {
        return "Tree navigation: Up/Down select, Left/Right collapse/expand, Enter activate action, Space toggle folder\n"
            + "Actions: u use profile | R refresh all | a add repo | d delete repo | n create repo | c create profile\n"
            + "Preview: Up/Down/PgUp/PgDn/Home/End scroll (mouse wheel supported by terminal)\n"
            + "File editing: e edit selected file | Ctrl+S save | Esc exit edit mode\n"
            + "Global: Tab switch pane | r reload | q quit";
    }

    private Element splashScreen() {
        return dialog(
            column(
                row(spacer(), text("░█▀█░█▀▀░█▀█").fg(Color.CYAN), spacer()),
                row(spacer(), text("░█░█░█░░░█▀▀").fg(Color.BLUE), spacer()),
                row(spacer(), text("░▀▀▀░▀▀▀░▀░░").fg(Color.GREEN), spacer()),
                spacer(),
                row(spacer(), text("OpenCode Configuration Profiles").bold().fg(Color.CYAN), spacer()),
                row(spacer(), text("Interactive Tree Workspace").fg(Color.YELLOW), spacer()),
                spacer(),
                row(spacer(), text("Loading repositories and profile trees...").dim(), spacer()),
                row(spacer(), text("Press any key to continue").dim().fg(Color.MAGENTA), spacer())
            )
        )
            .rounded()
            .borderColor(Color.CYAN)
            .width(72)
            .padding(1)
            .id("splash")
            .focusable()
            .onKeyEvent(this::handleKeyEvent);
    }

    private enum Pane {
        TREE,
        DETAIL
    }

    private enum NodeKind {
        REPOSITORY,
        PROFILE,
        DIRECTORY,
        FILE
    }

    private enum PromptAction {
        CREATE_PROFILE,
        ADD_REPOSITORY,
        DELETE_REPOSITORY,
        CREATE_REPOSITORY
    }

    private enum RefreshScope {
        SINGLE_REPOSITORY,
        ALL_REPOSITORIES
    }

    private enum RefreshConflictKind {
        REPOSITORY,
        MERGED_FILES
    }

    private record NodeRef(NodeKind kind, String repositoryName, String profileName, Path path) {
        private static NodeRef repository(String repositoryName, Path path) {
            return new NodeRef(NodeKind.REPOSITORY, repositoryName, null, path);
        }

        private static NodeRef profile(String repositoryName, String profileName, Path path) {
            return new NodeRef(NodeKind.PROFILE, repositoryName, profileName, path);
        }

        private static NodeRef directory(String repositoryName, String profileName, Path path) {
            return new NodeRef(NodeKind.DIRECTORY, repositoryName, profileName, path);
        }

        private static NodeRef file(String repositoryName, String profileName, Path path) {
            return new NodeRef(NodeKind.FILE, repositoryName, profileName, path);
        }
    }

    private record RefreshOperation(RefreshScope scope, String repositoryName) {
        private static RefreshOperation singleRepository(String repositoryName) {
            return new RefreshOperation(RefreshScope.SINGLE_REPOSITORY, repositoryName);
        }

        private static RefreshOperation allRepositories() {
            return new RefreshOperation(RefreshScope.ALL_REPOSITORIES, null);
        }
    }

    private record RefreshConflictState(
        RefreshConflictKind kind,
        ProfileService.ProfileRefreshConflictException repositoryConflict,
        ProfileService.ProfileRefreshUserConfigConflictException mergedFilesConflict
    ) {
        private static RefreshConflictState forRepository(ProfileService.ProfileRefreshConflictException conflict) {
            return new RefreshConflictState(RefreshConflictKind.REPOSITORY, conflict, null);
        }

        private static RefreshConflictState forMergedFiles(ProfileService.ProfileRefreshUserConfigConflictException conflict) {
            return new RefreshConflictState(RefreshConflictKind.MERGED_FILES, null, conflict);
        }
    }

    private static final class PromptState {
        private final PromptAction action;
        private final String title;
        private final List<String> labels;
        private final List<String> values;
        private int currentField;
        private String expectedConfirmation;

        private PromptState(PromptAction action, String title, List<String> labels) {
            this.action = action;
            this.title = title;
            this.labels = List.copyOf(labels);
            this.values = new ArrayList<>();
            for (int index = 0; index < labels.size(); index++) {
                this.values.add("");
            }
        }

        private static PromptState single(PromptAction action, String title, String label) {
            return new PromptState(action, title, List.of(label));
        }

        private static PromptState multi(PromptAction action, String title, List<String> labels) {
            return new PromptState(action, title, labels);
        }

        private String label() {
            return labels.get(currentField);
        }

        private String currentValue() {
            return values.get(currentField);
        }

        private void append(char value) {
            values.set(currentField, values.get(currentField) + value);
        }

        private void deleteLast() {
            String current = values.get(currentField);
            if (current.isEmpty()) {
                return;
            }
            values.set(currentField, current.substring(0, current.length() - 1));
        }

        private boolean nextField() {
            if (currentField >= labels.size() - 1) {
                return false;
            }
            currentField++;
            return true;
        }
    }
}
