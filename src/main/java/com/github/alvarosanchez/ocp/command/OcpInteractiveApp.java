package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.model.Profile;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import com.github.alvarosanchez.ocp.service.RepositoryService.ConfiguredRepository;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

final class OcpInteractiveApp extends ToolkitApp {

    private static final String PROFILES_LIST_ID = "profiles-list";
    private static final String REPOSITORIES_LIST_ID = "repositories-list";
    private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};
    private static final Duration SPLASH_MIN_DURATION = Duration.ofMillis(120);
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[;?0-9]*[ -/]*[@-~]");
    private static final int CONFLICT_DIFF_PREVIEW_LINES = 20;
    private static final int CONFLICT_FILES_PREVIEW_LINES = 12;

    private final ProfileService profileService;
    private final RepositoryService repositoryService;

    private final ListElement<?> profilesList = list()
        .rounded()
        .title("Profiles")
        .highlightColor(Color.CYAN)
        .autoScroll()
        .id(PROFILES_LIST_ID)
        .focusable()
        .onKeyEvent(this::handleKeyEvent);
    private final ListElement<?> repositoriesList = list()
        .rounded()
        .title("Repositories")
        .highlightColor(Color.GREEN)
        .autoScroll()
        .id(REPOSITORIES_LIST_ID)
        .focusable()
        .onKeyEvent(this::handleKeyEvent);

    private List<Profile> profiles = List.of();
    private List<ConfiguredRepository> repositories = List.of();

    private Pane activePane = Pane.PROFILES;
    private String status = "Ready. Press ? for keymap.";
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

    OcpInteractiveApp(ProfileService profileService, RepositoryService repositoryService) {
        this.profileService = profileService;
        this.repositoryService = repositoryService;
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
        syncLists();

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
                    profilesList.fill(),
                    text("Enter/use selected profile, c=create profile").dim(),
                    text("Legend: ▶ active | ⇪ updates").dim().fg(Color.YELLOW)
                ).rounded().borderColor(activePane == Pane.PROFILES ? Color.GREEN : Color.GRAY).fill(),
                panel(
                    repositoriesList.fill(),
                    text("Enter=refresh selected, R=refresh all, a=add, d=delete, n=create").dim()
                ).rounded().borderColor(activePane == Pane.REPOSITORIES ? Color.GREEN : Color.GRAY).fill()
            ).fill(),
            panel(text(statusLine())).rounded().borderColor(busy ? Color.GREEN : Color.YELLOW).length(3),
            panel(
                text(helpVisible ? helpText() : "Press ? for interactive keymap").dim()
            ).rounded().length(helpVisible ? 8 : 3)
        );

        if (prompt != null) {
            return column(
                root,
                dialog(
                    prompt.title,
                    text(prompt.label()).bold(),
                    text(prompt.currentValue()),
                    text("Enter next/apply | Tab next field | Backspace delete | Esc cancel").dim()
                ).rounded().borderColor(Color.MAGENTA)
            );
        }

        if (refreshConflict != null) {
            return column(root, renderRefreshConflictDialog());
        }

        return root;
    }

    private EventResult handleKeyEvent(KeyEvent event) {
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
                return EventResult.HANDLED;
            }
            return EventResult.HANDLED;
        }

        if (event.isQuit()) {
            quit();
            return EventResult.HANDLED;
        }
        if (event.isChar('?')) {
            helpVisible = !helpVisible;
            return EventResult.HANDLED;
        }
        if (event.isFocusNext() || event.isFocusPrevious() || event.isKey(dev.tamboui.tui.event.KeyCode.TAB)
            || event.isLeft() || event.isRight()) {
            activePane = activePane == Pane.PROFILES ? Pane.REPOSITORIES : Pane.PROFILES;
            return EventResult.HANDLED;
        }
        if (event.isUp()) {
            selectedList().selectPrevious();
            return EventResult.HANDLED;
        }
        if (event.isDown()) {
            selectedList().selectNext(selectedCount());
            return EventResult.HANDLED;
        }
        if (event.isConfirm()) {
            if (activePane == Pane.PROFILES) {
                useSelectedProfile();
            } else {
                refreshSelectedRepository();
            }
            return EventResult.HANDLED;
        }

        if (event.isChar('u')) {
            useSelectedProfile();
            return EventResult.HANDLED;
        }
        if (event.isChar('c')) {
            prompt = PromptState.single(PromptAction.CREATE_PROFILE, "Create profile", "Profile name");
            return EventResult.HANDLED;
        }
        if (event.isChar('a')) {
            prompt = PromptState.multi(PromptAction.ADD_REPOSITORY, "Add repository", List.of("Repository URI", "Repository name"));
            return EventResult.HANDLED;
        }
        if (event.isChar('d')) {
            String name = selectedRepositoryName();
            if (name == null) {
                status = "No repository selected.";
                return EventResult.HANDLED;
            }
            prompt = PromptState.single(
                PromptAction.DELETE_REPOSITORY,
                "Delete repository",
                "Type repository name to confirm: " + name
            );
            prompt.expectedConfirmation = name;
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
        if (event.isChar('f')) {
            refreshSelectedRepository();
            return EventResult.HANDLED;
        }
        if (event.isChar('R')) {
            refreshAllRepositories();
            return EventResult.HANDLED;
        }
        if (event.isChar('r')) {
            reloadState();
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
        String selectedProfile = selectedProfileName();
        if (selectedProfile == null) {
            status = "No profile selected.";
            return;
        }
        runBusyOperation(
            "Applying profile `" + selectedProfile + "`...",
            () -> profileService.useProfile(selectedProfile),
            "Switched to profile `" + selectedProfile + "`.",
            null
        );
    }

    private void refreshSelectedRepository() {
        String selectedRepository = selectedRepositoryName();
        if (selectedRepository == null) {
            status = "No repository selected.";
            return;
        }
        pendingRefreshOperation = RefreshOperation.singleRepository(selectedRepository);
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
        int profileSelection = profilesList.selected();
        int repositorySelection = repositoriesList.selected();

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

        syncLists();
        if (!profiles.isEmpty()) {
            profilesList.selected(Math.min(profileSelection, profiles.size() - 1));
        }
        if (!repositories.isEmpty()) {
            repositoriesList.selected(Math.min(repositorySelection, repositories.size() - 1));
        }
    }

    private void syncLists() {
        List<String> profileItems = new ArrayList<>(profiles.size());
        for (Profile profile : profiles) {
            String marker = profile.active() ? "▶" : " ";
            String updateMarker = profile.updateAvailable() ? "  ⇪" : "";
            profileItems.add(marker + " " + profile.name() + " [" + profile.repositoryName() + "]" + updateMarker);
        }
        profilesList.items(profileItems);

        List<String> repositoryItems = new ArrayList<>(repositories.size());
        for (ConfiguredRepository repository : repositories) {
            repositoryItems.add(
                repository.name() + " (" + repository.resolvedProfiles().size() + " profile"
                    + (repository.resolvedProfiles().size() == 1 ? "" : "s") + ")"
            );
        }
        repositoriesList.items(repositoryItems);
    }

    private ListElement<?> selectedList() {
        return activePane == Pane.PROFILES ? profilesList : repositoriesList;
    }

    private int selectedCount() {
        return activePane == Pane.PROFILES ? profiles.size() : repositories.size();
    }

    private String selectedProfileName() {
        if (profiles.isEmpty()) {
            return null;
        }
        int selected = Math.min(profilesList.selected(), profiles.size() - 1);
        return profiles.get(selected).name();
    }

    private String selectedRepositoryName() {
        if (repositories.isEmpty()) {
            return null;
        }
        int selected = Math.min(repositoriesList.selected(), repositories.size() - 1);
        return repositories.get(selected).name();
    }

    private String helpText() {
        return "Arrows move selection | Tab or <-/-> switches pane | Enter uses profile/refreshes repository\n"
            + "u use profile | c create profile | a add repository | d delete repository\n"
            + "n create repository scaffold | f refresh selected repository | R refresh all repositories\n"
            + "r reload data | q quit";
    }

    private void loadInitialDataInBackground() {
        List<Profile> loadedProfiles = List.of();
        List<ConfiguredRepository> loadedRepositories = List.of();
        String bootstrapStatus = status;

        try {
            loadedProfiles = profileService.getAllProfiles();
        } catch (RuntimeException e) {
            bootstrapStatus = "Error loading profiles: " + e.getMessage();
        }

        try {
            loadedRepositories = repositoryService.listConfiguredRepositories();
        } catch (RuntimeException e) {
            if (bootstrapStatus.startsWith("Error")) {
                bootstrapStatus = bootstrapStatus + " | Error loading repositories: " + e.getMessage();
            } else {
                bootstrapStatus = "Error loading repositories: " + e.getMessage();
            }
        }

        List<Profile> finalLoadedProfiles = loadedProfiles;
        List<ConfiguredRepository> finalLoadedRepositories = loadedRepositories;
        String finalBootstrapStatus = bootstrapStatus;

        if (runner() != null) {
            runner().runOnRenderThread(() -> {
                profiles = finalLoadedProfiles;
                repositories = finalLoadedRepositories;
                syncLists();
                initialDataLoaded = true;
                if (!finalBootstrapStatus.equals(status)) {
                    status = finalBootstrapStatus;
                }
                maybeHideSplash();
            });
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
        if (REPOSITORIES_LIST_ID.equals(focusedId)) {
            activePane = Pane.REPOSITORIES;
            return;
        }
        if (PROFILES_LIST_ID.equals(focusedId)) {
            activePane = Pane.PROFILES;
        }
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
            try {
                operation.run();
            } catch (ProfileService.ProfileRefreshConflictException e) {
                repositoryConflict = e;
            } catch (ProfileService.ProfileRefreshUserConfigConflictException e) {
                mergedFilesConflict = e;
            } catch (RuntimeException e) {
                finalMessage = "Error: " + e.getMessage();
            }

            String messageToShow = finalMessage;
            ProfileService.ProfileRefreshConflictException finalRepositoryConflict = repositoryConflict;
            ProfileService.ProfileRefreshUserConfigConflictException finalMergedFilesConflict = mergedFilesConflict;
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
                if (onSuccess != null) {
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
        for (java.nio.file.Path driftedFile : conflict.driftedFiles()) {
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

    private Element splashScreen() {
        return dialog(
            column(
                row(spacer(), text("░█▀█░█▀▀░█▀█").fg(Color.CYAN), spacer()),
                row(spacer(), text("░█░█░█░░░█▀▀").fg(Color.BLUE), spacer()),
                row(spacer(), text("░▀▀▀░▀▀▀░▀░░").fg(Color.GREEN), spacer()),
                spacer(),
                row(spacer(), text("OpenCode Configuration Profiles").bold().fg(Color.CYAN), spacer()),
                row(spacer(), text("Interactive Terminal Experience").fg(Color.YELLOW), spacer()),
                spacer(),
                row(spacer(), text("Loading profiles and repositories...").dim(), spacer()),
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
        PROFILES,
        REPOSITORIES
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
