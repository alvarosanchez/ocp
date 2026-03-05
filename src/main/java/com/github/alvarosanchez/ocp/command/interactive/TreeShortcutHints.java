package com.github.alvarosanchez.ocp.command.interactive;

import java.util.List;

final class TreeShortcutHints {

    private static final List<Shortcut> NAVIGATION_SHORTCUTS = List.of(
        new Shortcut("Up/Down", "select"),
        new Shortcut("Left/Right", "collapse/expand")
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
                    new Shortcut("c", "create profile"),
                    new Shortcut("d", "delete repo")
                ),
                repositoryRefreshable
            );
            case PROFILE, DIRECTORY -> withParentAndRefresh(
                List.of(
                    new Shortcut("u", "use profile"),
                    new Shortcut("c", "create profile"),
                    new Shortcut("d", "delete profile")
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
            actions.add(new Shortcut("e", "edit file"));
        }
        actions.add(new Shortcut("u", "use profile"));
        actions.add(new Shortcut("d", "delete profile"));
        return List.copyOf(actions);
    }

    private static List<Shortcut> withParentAndRefresh(
        List<Shortcut> baseActions,
        boolean selectedProfileHasParent,
        boolean repositoryRefreshable
    ) {
        List<Shortcut> actions = new java.util.ArrayList<>(baseActions);
        if (selectedProfileHasParent) {
            actions.add(new Shortcut("p", "go parent"));
        }
        if (repositoryRefreshable) {
            actions.add(new Shortcut("r", "refresh repo"));
        }
        return List.copyOf(actions);
    }

    private static List<Shortcut> appendRefreshActionIfAvailable(List<Shortcut> baseActions, boolean repositoryRefreshable) {
        if (!repositoryRefreshable) {
            return baseActions;
        }
        List<Shortcut> actions = new java.util.ArrayList<>(baseActions);
        actions.add(new Shortcut("r", "refresh repo"));
        return List.copyOf(actions);
    }

    record ShortcutHints(List<Shortcut> navigation, List<Shortcut> actions) {
    }

    record Shortcut(String key, String description) {
    }
}
