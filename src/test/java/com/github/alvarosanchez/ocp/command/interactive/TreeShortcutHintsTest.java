package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeShortcutHintsTest {

    @Test
    void returnsGlobalHintsWhenSelectionIsNull() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(null, false, false, false, false, false);

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
    void editOcpConfigShortcutConstantMatchesAdvertisedAction() {
        assertEquals(
            new TreeShortcutHints.Shortcut("o", "edit OCP config"),
            TreeShortcutHints.Shortcut.EDIT_OCP_CONFIG
        );
    }

    @Test
    void returnsRepositorySpecificHints() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.repository("repo", Path.of("/tmp/repo")),
            true,
            false,
            true,
            false,
            false
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("c", "create profile"),
                new TreeShortcutHints.Shortcut("m", "migrate to git/github"),
                new TreeShortcutHints.Shortcut("r", "refresh repository"),
                new TreeShortcutHints.Shortcut("d", "delete repo")
            ),
            hints.actions()
        );
    }

    @Test
    void omitsRefreshHintWhenRepositoryIsNotRefreshable() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.repository("repo", Path.of("/tmp/repo")),
            false,
            false,
            false,
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
            true,
            false,
            true,
            false
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("u", "use profile"),
                new TreeShortcutHints.Shortcut("c", "create profile"),
                new TreeShortcutHints.Shortcut("f", "create file"),
                new TreeShortcutHints.Shortcut("p", "go parent"),
                new TreeShortcutHints.Shortcut("g", "commit and push"),
                new TreeShortcutHints.Shortcut("r", "refresh repository"),
                new TreeShortcutHints.Shortcut("d", "delete profile")
            ),
            hints.actions()
        );
    }

    @Test
    void returnsDirectorySpecificHints() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.directory("repo", "profile", Path.of("/tmp/repo/profile/folder")),
            true,
            true,
            false,
            false,
            false
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("u", "use profile"),
                new TreeShortcutHints.Shortcut("c", "create profile"),
                new TreeShortcutHints.Shortcut("f", "create file"),
                new TreeShortcutHints.Shortcut("p", "go parent"),
                new TreeShortcutHints.Shortcut("r", "refresh repository"),
                new TreeShortcutHints.Shortcut("d", "delete profile")
            ),
            hints.actions()
        );
    }

    @Test
    void returnsFileSpecificHints() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.file("repo", "profile", Path.of("/tmp/repo/profile/file.json")),
            true,
            false,
            false,
            true,
            false
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("e", "edit file"),
                new TreeShortcutHints.Shortcut("f", "create file"),
                new TreeShortcutHints.Shortcut("d", "delete file"),
                new TreeShortcutHints.Shortcut("y", "copy path"),
                new TreeShortcutHints.Shortcut("u", "use profile"),
                new TreeShortcutHints.Shortcut("g", "commit and push"),
                new TreeShortcutHints.Shortcut("r", "refresh repository")
            ),
            hints.actions()
        );
    }

    @Test
    void omitsEditShortcutForInheritedFile() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.inheritedFile("repo", "profile", Path.of("/tmp/repo/base.json"), "parent"),
            true,
            true,
            false,
            false,
            false
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("y", "copy path"),
                new TreeShortcutHints.Shortcut("u", "use profile"),
                new TreeShortcutHints.Shortcut("p", "go parent"),
                new TreeShortcutHints.Shortcut("r", "refresh repository")
            ),
            hints.actions()
        );
    }

    @Test
    void shortcutHintsOmitEditForReadOnlyMergedNode() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.mergedReadOnlyFile(
                "repo",
                "profile",
                Path.of("/tmp/repo/parent.json"),
                "parent",
                List.of("base", "parent"),
                List.of(Path.of("/tmp/repo/parent.json")),
                true
            ),
            true,
            true,
            false,
            false,
            false
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("y", "copy path"),
                new TreeShortcutHints.Shortcut("u", "use profile"),
                new TreeShortcutHints.Shortcut("p", "go parent"),
                new TreeShortcutHints.Shortcut("r", "refresh repository")
            ),
            hints.actions()
        );
    }

    @Test
    void shortcutHintsKeepEditForChildLocalDeepMergedNode() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.deepMergedFile("repo", "profile", Path.of("/tmp/repo/profile/file.json")),
            true,
            false,
            false,
            false,
            false
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("e", "edit file"),
                new TreeShortcutHints.Shortcut("f", "create file"),
                new TreeShortcutHints.Shortcut("d", "delete file"),
                new TreeShortcutHints.Shortcut("y", "copy path"),
                new TreeShortcutHints.Shortcut("u", "use profile"),
                new TreeShortcutHints.Shortcut("r", "refresh repository")
            ),
            hints.actions()
        );
    }

    @Test
    void omitsMigrationHintForGitBackedRepository() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.repository("repo", Path.of("/tmp/repo")),
            true,
            false,
            false,
            false,
            false
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("c", "create profile"),
                new TreeShortcutHints.Shortcut("r", "refresh repository"),
                new TreeShortcutHints.Shortcut("d", "delete repo")
            ),
            hints.actions()
        );
    }

    @Test
    void showsCommitAndPushHintForDirtyGitBackedRepository() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.repository("repo", Path.of("/tmp/repo")),
            true,
            false,
            false,
            true,
            false
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("c", "create profile"),
                new TreeShortcutHints.Shortcut("g", "commit and push"),
                new TreeShortcutHints.Shortcut("r", "refresh repository"),
                new TreeShortcutHints.Shortcut("d", "delete repo")
            ),
            hints.actions()
        );
    }

    @Test
    void returnsEditModeShortcutsForEditableFile() {
        TreeShortcutHints.ShortcutHints hints = TreeShortcutHints.forSelection(
            NodeRef.file("repo", "profile", Path.of("/tmp/repo/profile/file.json")),
            true,
            false,
            false,
            true,
            true
        );

        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("Up/Down", "select"),
                new TreeShortcutHints.Shortcut("Left/Right", "collapse/expand")
            ),
            hints.navigation()
        );
        assertEquals(
            List.of(
                new TreeShortcutHints.Shortcut("Ctrl+S", "save file"),
                new TreeShortcutHints.Shortcut("Esc", "exit edit mode")
            ),
            hints.actions()
        );
    }

}
