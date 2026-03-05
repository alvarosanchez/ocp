package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.model.Profile;
import com.github.alvarosanchez.ocp.service.RepositoryService.ConfiguredRepository;
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.RichTextElement;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.widgets.tree.TreeNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HierarchyTreeBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void buildHierarchyTreeSortsActiveProfileFirstAndBuildsFileTree() throws IOException {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path activeProfilePath = repositoryPath.resolve("active");
        Path inactiveProfilePath = repositoryPath.resolve("other");
        Files.createDirectories(activeProfilePath.resolve("subdir"));
        Files.createDirectories(inactiveProfilePath);
        Files.writeString(activeProfilePath.resolve("subdir/settings.json"), "{}");
        Files.writeString(activeProfilePath.resolve("notes.txt"), "text");

        ConfiguredRepository repository = new ConfiguredRepository(
            "repo-a",
            "git@example/repo-a.git",
            repositoryPath.toString(),
            List.of("other", "active")
        );

        Profile activeProfile = new Profile(
            "active",
            "active profile",
            "repo-a",
            "git@example/repo-a.git",
            "abc123",
            "1d",
            "msg",
            false,
            true,
            false
        );

        List<TreeNode<NodeRef>> roots = HierarchyTreeBuilder.buildHierarchyTree(
            List.of(repository),
            List.of(activeProfile),
            Map.of(),
            4,
            10
        );

        assertEquals(1, roots.size());
        TreeNode<NodeRef> repositoryNode = roots.getFirst();
        assertEquals("repo-a", repositoryNode.label());
        assertEquals(NodeKind.REPOSITORY, repositoryNode.data().kind());

        assertEquals(2, repositoryNode.children().size());
        TreeNode<NodeRef> firstProfile = repositoryNode.children().getFirst();
        assertEquals("active", firstProfile.label());
        assertEquals(NodeKind.PROFILE, firstProfile.data().kind());

        assertFalse(firstProfile.children().isEmpty());
        assertEquals("subdir/", firstProfile.children().getFirst().label());
        assertEquals("notes.txt", firstProfile.children().get(1).label());
    }

    @Test
    void buildHierarchyTreeAddsEllipsisWhenDepthIsExceeded() throws IOException {
        Path repositoryPath = tempDir.resolve("repo-depth");
        Path profilePath = repositoryPath.resolve("p");
        Files.createDirectories(profilePath.resolve("a/b"));
        Files.writeString(profilePath.resolve("a/b/value.txt"), "x");

        ConfiguredRepository repository = new ConfiguredRepository(
            "repo-depth",
            "git@example/repo-depth.git",
            repositoryPath.toString(),
            List.of("p")
        );

        List<TreeNode<NodeRef>> roots = HierarchyTreeBuilder.buildHierarchyTree(
            List.of(repository),
            List.of(),
            Map.of(),
            0,
            10
        );

        TreeNode<NodeRef> profileNode = roots.getFirst().children().getFirst();
        TreeNode<NodeRef> topDirectory = profileNode.children().getFirst();
        assertEquals("a/", topDirectory.label());
        assertEquals("...", topDirectory.children().getFirst().label());
        assertTrue(topDirectory.children().getFirst().isLeaf());
    }

    @Test
    void renderTreeNodeFallsBackToPlainTextWhenDataIsMissing() {
        TreeNode<NodeRef> node = TreeNode.of("group");

        StyledElement<?> rendered = HierarchyTreeBuilder.renderTreeNode(node, Map.of(), Map.of());

        TextElement text = assertInstanceOf(TextElement.class, rendered);
        assertEquals("group", text.content());
    }

    @Test
    void renderTreeNodeRendersProfileIconAndActiveUpdateMarkers() {
        TreeNode<NodeRef> node = TreeNode.of(
            "active-profile",
            NodeRef.profile("repo-a", "active-profile", Path.of("/tmp/repo-a/active-profile"))
        );
        Profile profile = new Profile(
            "active-profile",
            "",
            "repo-a",
            "git@example/repo-a.git",
            "abc123",
            "1d",
            "msg",
            true,
            true,
            false
        );

        StyledElement<?> rendered = HierarchyTreeBuilder.renderTreeNode(
            node,
            Map.of("active-profile", profile),
            Map.of("active-profile", "base-profile")
        );

        RichTextElement richText = assertInstanceOf(RichTextElement.class, rendered);
        var spans = richText.text().lines().getFirst().spans();
        assertEquals(4, spans.size());
        assertEquals("\uD83D\uDC64 ", spans.get(0).content());
        assertEquals(Color.MAGENTA, spans.get(0).style().fg().orElseThrow());
        assertTrue(spans.get(0).style().effectiveModifiers().contains(Modifier.BOLD));
        assertEquals("active-profile ⇢ 👤 base-profile", spans.get(1).content());
        assertEquals(Color.BRIGHT_WHITE, spans.get(1).style().fg().orElseThrow());
        assertTrue(spans.get(1).style().effectiveModifiers().contains(Modifier.BOLD));
        assertEquals(" \u2713", spans.get(2).content());
        assertEquals(Color.GREEN, spans.get(2).style().fg().orElseThrow());
        assertEquals(" \u2744", spans.get(3).content());
        assertEquals(Color.YELLOW, spans.get(3).style().fg().orElseThrow());
    }

    @Test
    void renderTreeNodeMakesInheritedFileNodesVisiblyDistinct() {
        TreeNode<NodeRef> node = TreeNode.of(
            "shared.json",
            NodeRef.inheritedFile("repo-a", "child", Path.of("/tmp/repo-a/base/shared.json"), "base")
        );

        StyledElement<?> rendered = HierarchyTreeBuilder.renderTreeNode(node, Map.of(), Map.of());

        RichTextElement richText = assertInstanceOf(RichTextElement.class, rendered);
        var spans = richText.text().lines().getFirst().spans();
        assertEquals(2, spans.size());
        assertEquals("🔒 ", spans.get(0).content());
        assertEquals(Color.GRAY, spans.get(0).style().fg().orElseThrow());
        assertEquals("shared.json", spans.get(1).content());
        assertEquals(Color.GRAY, spans.get(1).style().fg().orElseThrow());
        assertTrue(spans.get(1).style().effectiveModifiers().contains(Modifier.DIM));
    }

    @Test
    void buildHierarchyTreeIncludesInheritedParentFilesAsReadOnlyNodes() throws IOException {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path baseProfilePath = repositoryPath.resolve("base");
        Path childProfilePath = repositoryPath.resolve("child");
        Files.createDirectories(baseProfilePath.resolve("nested"));
        Files.createDirectories(childProfilePath);
        Path inheritedFile = baseProfilePath.resolve("nested/shared.json");
        Files.writeString(inheritedFile, "{}");
        Path localFile = childProfilePath.resolve("local.json");
        Files.writeString(localFile, "{}");

        ConfiguredRepository repository = new ConfiguredRepository(
            "repo-a",
            "git@example/repo-a.git",
            repositoryPath.toString(),
            List.of("base", "child")
        );

        List<TreeNode<NodeRef>> roots = HierarchyTreeBuilder.buildHierarchyTree(
            List.of(repository),
            List.of(),
            Map.of("child", "base"),
            4,
            20
        );

        TreeNode<NodeRef> childProfileNode = roots.getFirst().children().stream()
            .filter(node -> "child".equals(node.label()))
            .findFirst()
            .orElseThrow();
        TreeNode<NodeRef> nestedDirectory = childProfileNode.children().stream()
            .filter(node -> "nested/".equals(node.label()))
            .findFirst()
            .orElseThrow();
        TreeNode<NodeRef> inheritedFileNode = nestedDirectory.children().getFirst();

        assertEquals("shared.json", inheritedFileNode.label());
        assertTrue(inheritedFileNode.data().inherited());
        assertEquals("base", inheritedFileNode.data().inheritedFromProfile());
        assertEquals(inheritedFile, inheritedFileNode.data().path());
    }

    @Test
    void buildHierarchyTreePrefersChildFileOverInheritedParentFile() throws IOException {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path baseProfilePath = repositoryPath.resolve("base");
        Path childProfilePath = repositoryPath.resolve("child");
        Files.createDirectories(baseProfilePath.resolve("nested"));
        Files.createDirectories(childProfilePath.resolve("nested"));
        Path parentFile = baseProfilePath.resolve("nested/settings.json");
        Path childFile = childProfilePath.resolve("nested/settings.json");
        Files.writeString(parentFile, "{\"source\":\"parent\"}");
        Files.writeString(childFile, "{\"source\":\"child\"}");

        ConfiguredRepository repository = new ConfiguredRepository(
            "repo-a",
            "git@example/repo-a.git",
            repositoryPath.toString(),
            List.of("base", "child")
        );

        List<TreeNode<NodeRef>> roots = HierarchyTreeBuilder.buildHierarchyTree(
            List.of(repository),
            List.of(),
            Map.of("child", "base"),
            4,
            20
        );

        TreeNode<NodeRef> childProfileNode = roots.getFirst().children().stream()
            .filter(node -> "child".equals(node.label()))
            .findFirst()
            .orElseThrow();
        TreeNode<NodeRef> nestedDirectory = childProfileNode.children().stream()
            .filter(node -> "nested/".equals(node.label()))
            .findFirst()
            .orElseThrow();

        assertEquals(1, nestedDirectory.children().size());
        TreeNode<NodeRef> settingsNode = nestedDirectory.children().getFirst();
        assertEquals("settings.json", settingsNode.label());
        assertEquals(childFile, settingsNode.data().path());
        assertFalse(settingsNode.data().inherited());
    }
}
