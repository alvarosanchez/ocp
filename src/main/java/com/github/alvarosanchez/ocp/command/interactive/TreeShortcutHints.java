package com.github.alvarosanchez.ocp.command.interactive;

import java.util.List;

final class TreeShortcutHints {

    private static final List<Shortcut> NAVIGATION_SHORTCUTS = List.of(
        Shortcut.UP_DOWN_SELECT,
        Shortcut.LEFT_RIGHT_COLLAPSE_EXPAND
    );

    static ShortcutHints forSelection(
        NodeRef selectedNode,
        boolean repositoryRefreshable,
        boolean selectedProfileHasParent,
        boolean repositoryMigratable,
        boolean repositoryCommitPushAvailable,
        boolean editMode
    ) {
        if (editMode && selectedNode != null && selectedNode.kind() == NodeKind.FILE && !selectedNode.inherited()) {
            return new ShortcutHints(
                NAVIGATION_SHORTCUTS,
                List.of(Shortcut.SAVE_FILE, Shortcut.EXIT_EDIT_MODE)
            );
        }
        if (selectedNode == null) {
            return new ShortcutHints(NAVIGATION_SHORTCUTS, List.of());
        }

        return new ShortcutHints(
            NAVIGATION_SHORTCUTS,
            actionsForNode(
                selectedNode,
                repositoryRefreshable,
                selectedProfileHasParent,
                repositoryMigratable,
                repositoryCommitPushAvailable
            )
        );
    }

    private static List<Shortcut> actionsForNode(
        NodeRef selectedNode,
        boolean repositoryRefreshable,
        boolean selectedProfileHasParent,
        boolean repositoryMigratable,
        boolean repositoryCommitPushAvailable
    ) {
        return switch (selectedNode.kind()) {
            case REPOSITORY -> repositoryActions(repositoryRefreshable, repositoryMigratable, repositoryCommitPushAvailable);
            case PROFILE, DIRECTORY -> withParentRefreshAndCommitPush(
                List.of(
                    Shortcut.USE_PROFILE,
                    Shortcut.CREATE_PROFILE,
                    Shortcut.CREATE_FILE,
                    Shortcut.DELETE_PROFILE
                ),
                selectedProfileHasParent,
                repositoryRefreshable,
                repositoryCommitPushAvailable
            );
            case FILE -> withParentRefreshAndCommitPush(
                fileActions(selectedNode),
                selectedProfileHasParent,
                repositoryRefreshable,
                repositoryCommitPushAvailable
            );
        };
    }

    private static List<Shortcut> repositoryActions(
        boolean repositoryRefreshable,
        boolean repositoryMigratable,
        boolean repositoryCommitPushAvailable
    ) {
        List<Shortcut> actions = new java.util.ArrayList<>();
        actions.add(Shortcut.CREATE_PROFILE);
        if (repositoryCommitPushAvailable) {
            actions.add(Shortcut.COMMIT_AND_PUSH_REPOSITORY);
        }
        if (repositoryMigratable) {
            actions.add(Shortcut.MIGRATE_REPOSITORY);
        }
        if (repositoryRefreshable) {
            actions.add(Shortcut.REFRESH_REPOSITORY);
        }
        actions.add(Shortcut.DELETE_REPOSITORY);
        return List.copyOf(actions);
    }

    private static List<Shortcut> fileActions(NodeRef selectedNode) {
        List<Shortcut> actions = new java.util.ArrayList<>();
        if (!selectedNode.inherited()) {
            actions.add(Shortcut.EDIT_FILE);
            actions.add(Shortcut.CREATE_FILE);
            actions.add(Shortcut.DELETE_FILE);
        }
        actions.add(Shortcut.COPY_PATH);
        actions.add(Shortcut.USE_PROFILE);
        return List.copyOf(actions);
    }

    private static List<Shortcut> withParentRefreshAndCommitPush(
        List<Shortcut> baseActions,
        boolean selectedProfileHasParent,
        boolean repositoryRefreshable,
        boolean repositoryCommitPushAvailable
    ) {
        List<Shortcut> actions = new java.util.ArrayList<>(baseActions);
        if (selectedProfileHasParent) {
            actions.add(Shortcut.GO_PARENT);
        }
        if (repositoryCommitPushAvailable) {
            actions.add(Shortcut.COMMIT_AND_PUSH_REPOSITORY);
        }
        if (repositoryRefreshable) {
            actions.add(Shortcut.REFRESH_REPOSITORY);
        }
        if (baseActions.contains(Shortcut.DELETE_PROFILE)) {
            actions.remove(Shortcut.DELETE_PROFILE);
            actions.add(Shortcut.DELETE_PROFILE);
        }
        return List.copyOf(actions);
    }

    record ShortcutHints(List<Shortcut> navigation, List<Shortcut> actions) {
    }

    record Shortcut(String key, String description) {
        static final Shortcut TAB_SWITCH_PANE = new Shortcut("Tab", "switch pane");
        static final Shortcut ADD_EXISTING_REPOSITORY = new Shortcut("a", "add existing repo");
        static final Shortcut CREATE_NEW_REPOSITORY = new Shortcut("n", "create new repo");
        static final Shortcut EDIT_OCP_CONFIG = new Shortcut("o", "edit OCP config");
        static final Shortcut REFRESH_ALL_REPOSITORIES = new Shortcut("R", "refresh all");
        static final Shortcut QUIT = new Shortcut("q", "quit");

        static final Shortcut ENTER_NEXT_APPLY = new Shortcut("Enter", "next/apply");
        static final Shortcut BACKSPACE_DELETE = new Shortcut("Backspace", "delete");
        static final Shortcut BACKSPACE_CLEAR = new Shortcut("Backspace", "clear");
        static final Shortcut ESC_CANCEL = new Shortcut("Esc", "cancel");
        static final Shortcut EXIT_EDIT_MODE = new Shortcut("Esc", "exit edit mode");
        static final Shortcut UP_DOWN_SELECT = new Shortcut("Up/Down", "select");
        static final Shortcut SAVE_FILE = new Shortcut("Ctrl+S", "save file");

        static final Shortcut LEFT_RIGHT_COLLAPSE_EXPAND = new Shortcut("Left/Right", "collapse/expand");
        static final Shortcut REFRESH_REPOSITORY = new Shortcut("r", "refresh repository");
        static final Shortcut CREATE_PROFILE = new Shortcut("c", "create profile");
        static final Shortcut CREATE_FILE = new Shortcut("f", "create file");
        static final Shortcut COMMIT_AND_PUSH_REPOSITORY = new Shortcut("g", "commit and push");
        static final Shortcut MIGRATE_REPOSITORY = new Shortcut("m", "migrate to git/github");
        static final Shortcut DELETE_REPOSITORY = new Shortcut("d", "delete repo");
        static final Shortcut USE_PROFILE = new Shortcut("u", "use profile");
        static final Shortcut DELETE_PROFILE = new Shortcut("d", "delete profile");
        static final Shortcut EDIT_FILE = new Shortcut("e", "edit file");
        static final Shortcut DELETE_FILE = new Shortcut("d", "delete file");
        static final Shortcut COPY_PATH = new Shortcut("y", "copy path");
        static final Shortcut GO_PARENT = new Shortcut("p", "go parent");
    }
}
