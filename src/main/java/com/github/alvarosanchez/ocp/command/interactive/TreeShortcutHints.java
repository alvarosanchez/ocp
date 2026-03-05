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
        boolean selectedProfileHasParent
    ) {
        if (selectedNode == null) {
            return new ShortcutHints(NAVIGATION_SHORTCUTS, List.of());
        }

        return new ShortcutHints(
            NAVIGATION_SHORTCUTS,
            actionsForNode(selectedNode, repositoryRefreshable, selectedProfileHasParent)
        );
    }

    private static List<Shortcut> actionsForNode(
        NodeRef selectedNode,
        boolean repositoryRefreshable,
        boolean selectedProfileHasParent
    ) {
        return switch (selectedNode.kind()) {
            case REPOSITORY -> appendRefreshActionIfAvailable(
                List.of(
                    Shortcut.CREATE_PROFILE,
                    Shortcut.DELETE_REPOSITORY
                ),
                repositoryRefreshable
            );
            case PROFILE, DIRECTORY -> withParentAndRefresh(
                List.of(
                    Shortcut.USE_PROFILE,
                    Shortcut.CREATE_PROFILE,
                    Shortcut.DELETE_PROFILE
                ),
                selectedProfileHasParent,
                repositoryRefreshable
            );
            case FILE -> withParentAndRefresh(
                fileActions(selectedNode),
                selectedProfileHasParent,
                repositoryRefreshable
            );
        };
    }

    private static List<Shortcut> fileActions(NodeRef selectedNode) {
        List<Shortcut> actions = new java.util.ArrayList<>();
        if (!selectedNode.inherited()) {
            actions.add(Shortcut.EDIT_FILE);
        }
        actions.add(Shortcut.USE_PROFILE);
        actions.add(Shortcut.DELETE_PROFILE);
        return List.copyOf(actions);
    }

    private static List<Shortcut> withParentAndRefresh(
        List<Shortcut> baseActions,
        boolean selectedProfileHasParent,
        boolean repositoryRefreshable
    ) {
        List<Shortcut> actions = new java.util.ArrayList<>(baseActions);
        if (selectedProfileHasParent) {
            actions.add(Shortcut.GO_PARENT);
        }
        if (repositoryRefreshable) {
            actions.add(Shortcut.REFRESH_REPOSITORY);
        }
        return List.copyOf(actions);
    }

    private static List<Shortcut> appendRefreshActionIfAvailable(List<Shortcut> baseActions, boolean repositoryRefreshable) {
        if (!repositoryRefreshable) {
            return baseActions;
        }
        List<Shortcut> actions = new java.util.ArrayList<>(baseActions);
        actions.add(Shortcut.REFRESH_REPOSITORY);
        return List.copyOf(actions);
    }

    record ShortcutHints(List<Shortcut> navigation, List<Shortcut> actions) {
    }

    record Shortcut(String key, String description) {
        static final Shortcut TAB_SWITCH_PANE = new Shortcut("Tab", "switch pane");
        static final Shortcut ADD_EXISTING_REPOSITORY = new Shortcut("a", "add existing repo");
        static final Shortcut CREATE_NEW_REPOSITORY = new Shortcut("n", "create new repo");
        static final Shortcut REFRESH_ALL_REPOSITORIES = new Shortcut("R", "refresh all");
        static final Shortcut QUIT = new Shortcut("q", "quit");

        static final Shortcut ENTER_NEXT_APPLY = new Shortcut("Enter", "next/apply");
        static final Shortcut BACKSPACE_DELETE = new Shortcut("Backspace", "delete");
        static final Shortcut BACKSPACE_CLEAR = new Shortcut("Backspace", "clear");
        static final Shortcut ESC_CANCEL = new Shortcut("Esc", "cancel");
        static final Shortcut UP_DOWN_SELECT = new Shortcut("Up/Down", "select");

        static final Shortcut LEFT_RIGHT_COLLAPSE_EXPAND = new Shortcut("Left/Right", "collapse/expand");
        static final Shortcut REFRESH_REPOSITORY = new Shortcut("r", "refresh repository");
        static final Shortcut CREATE_PROFILE = new Shortcut("c", "create profile");
        static final Shortcut DELETE_REPOSITORY = new Shortcut("d", "delete repo");
        static final Shortcut USE_PROFILE = new Shortcut("u", "use profile");
        static final Shortcut ACTIVATE_PROFILE = new Shortcut("u", "activate profile");
        static final Shortcut DELETE_PROFILE = new Shortcut("d", "delete profile");
        static final Shortcut EDIT_FILE = new Shortcut("e", "edit file");
        static final Shortcut GO_PARENT = new Shortcut("p", "go parent");
    }
}
