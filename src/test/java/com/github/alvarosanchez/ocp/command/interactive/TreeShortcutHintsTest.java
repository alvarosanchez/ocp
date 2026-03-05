package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeShortcutHintsTest {

    @Test
    void returnsGlobalHintsWhenSelectionIsNull() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(null, false, false);

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("Up/Down", "select"),
                new TreeShortcutHints.Shortcut("Left/Right", "collapse/expand")
            ),
            hints.navigation()
        );
        assertEquals(List.of(), hints.actions());
    }

    @Test
    void returnsRepositorySpecificHints() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.repository("repo", Path.of("/tmp/repo")),
            true,
            false
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("c", "create profile"),
                new TreeShortcutHints.Shortcut("d", "delete repo"),
                new TreeShortcutHints.Shortcut("r", "refresh repo")
            ),
            hints.actions()
        );
    }

    @Test
    void omitsRefreshHintWhenRepositoryIsNotRefreshable() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.repository("repo", Path.of("/tmp/repo")),
            false,
            false
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("c", "create profile"),
                new TreeShortcutHints.Shortcut("d", "delete repo")
            ),
            hints.actions()
        );
    }

    @Test
    void returnsProfileSpecificHints() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.profile("repo", "profile", Path.of("/tmp/repo/profile")),
            true,
            true
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("u", "use profile"),
                new TreeShortcutHints.Shortcut("c", "create profile"),
                new TreeShortcutHints.Shortcut("d", "delete profile"),
                new TreeShortcutHints.Shortcut("p", "go parent"),
                new TreeShortcutHints.Shortcut("r", "refresh repo")
            ),
            hints.actions()
        );
    }

    @Test
    void returnsDirectorySpecificHints() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.directory("repo", "profile", Path.of("/tmp/repo/profile/folder")),
            true,
            true
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("u", "use profile"),
                new TreeShortcutHints.Shortcut("c", "create profile"),
                new TreeShortcutHints.Shortcut("d", "delete profile"),
                new TreeShortcutHints.Shortcut("p", "go parent"),
                new TreeShortcutHints.Shortcut("r", "refresh repo")
            ),
            hints.actions()
        );
    }

    @Test
    void returnsFileSpecificHints() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.file("repo", "profile", Path.of("/tmp/repo/profile/file.json")),
            true,
            false
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("e", "edit file"),
                new TreeShortcutHints.Shortcut("u", "use profile"),
                new TreeShortcutHints.Shortcut("d", "delete profile"),
                new TreeShortcutHints.Shortcut("r", "refresh repo")
            ),
            hints.actions()
        );
    }

    @Test
    void omitsEditShortcutForInheritedFile() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.inheritedFile("repo", "profile", Path.of("/tmp/repo/base.json"), "parent"),
            true,
            true
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("u", "use profile"),
                new TreeShortcutHints.Shortcut("d", "delete profile"),
                new TreeShortcutHints.Shortcut("p", "go parent"),
                new TreeShortcutHints.Shortcut("r", "refresh repo")
            ),
            hints.actions()
        );
    }
}
