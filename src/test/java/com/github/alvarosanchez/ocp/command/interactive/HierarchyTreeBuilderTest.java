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

        StyledElement<?> rendered = HierarchyTreeBuilder.renderTreeNode(node, Map.of(), Map.of(), Map.of());

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
            Map.of("repo-a/active-profile", profile),
            Map.of("repo-a/active-profile", List.of("base-profile")),
            Map.of()
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
    void renderTreeNodeRendersCommaSeparatedParentsInOrder() {
        TreeNode<NodeRef> node = TreeNode.of(
            "child",
            NodeRef.profile("repo-a", "child", Path.of("/tmp/repo-a/child"))
        );

        StyledElement<?> rendered = HierarchyTreeBuilder.renderTreeNode(
            node,
            Map.of(),
            Map.of("repo-a/child", List.of("parent-a", "parent-b")),
            Map.of()
        );

        RichTextElement richText = assertInstanceOf(RichTextElement.class, rendered);
        var spans = richText.text().lines().getFirst().spans();
        assertEquals(2, spans.size());
        assertEquals("👤 ", spans.get(0).content());
        assertEquals("child ⇢ 👤 parent-a, parent-b", spans.get(1).content());
        assertEquals(Color.BRIGHT_WHITE, spans.get(1).style().fg().orElseThrow());
    }

    @Test
    void renderTreeNodeMakesInheritedFileNodesVisiblyDistinct() {
        TreeNode<NodeRef> node = TreeNode.of(
            "shared.json",
            NodeRef.inheritedFile("repo-a", "child", Path.of("/tmp/repo-a/base/shared.json"), "base")
        );

        StyledElement<?> rendered = HierarchyTreeBuilder.renderTreeNode(node, Map.of(), Map.of(), Map.of());

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
    void renderTreeNodeMakesDeepMergedFileNodesVisiblyDistinct() {
        TreeNode<NodeRef> node = TreeNode.of(
            "opencode.json",
            NodeRef.deepMergedFile("repo-a", "child", Path.of("/tmp/repo-a/child/opencode.json"))
        );

        StyledElement<?> rendered = HierarchyTreeBuilder.renderTreeNode(node, Map.of(), Map.of(), Map.of());

        RichTextElement richText = assertInstanceOf(RichTextElement.class, rendered);
        var spans = richText.text().lines().getFirst().spans();
        assertEquals(2, spans.size());
        assertEquals("⛙ ", spans.get(0).content());
        assertEquals(Color.GRAY, spans.get(0).style().fg().orElseThrow());
        assertEquals("opencode.json", spans.get(1).content());
        assertEquals(Color.GRAY, spans.get(1).style().fg().orElseThrow());
        assertTrue(spans.get(1).style().effectiveModifiers().contains(Modifier.DIM));
    }

    @Test
    void renderTreeNodeShowsDirtyRepositoryMarker() {
        TreeNode<NodeRef> node = TreeNode.of(
            "repo-a",
            NodeRef.repository("repo-a", Path.of("/tmp/repo-a"))
        );

        StyledElement<?> rendered = HierarchyTreeBuilder.renderTreeNode(
            node,
            Map.of(),
            Map.of(),
            Map.of("repo-a", new RepositoryDirtyState(true, false))
        );

        RichTextElement richText = assertInstanceOf(RichTextElement.class, rendered);
        var spans = richText.text().lines().getFirst().spans();
        assertEquals(3, spans.size());
        assertEquals("📦 ", spans.get(0).content());
        assertEquals("repo-a", spans.get(1).content());
        assertEquals(" ✏", spans.get(2).content());
        assertEquals(Color.YELLOW, spans.get(2).style().fg().orElseThrow());
    }

    @Test
    void renderTreeNodeShowsInspectionFailureMarker() {
        TreeNode<NodeRef> node = TreeNode.of(
            "repo-a",
            NodeRef.repository("repo-a", Path.of("/tmp/repo-a"))
        );

        StyledElement<?> rendered = HierarchyTreeBuilder.renderTreeNode(
            node,
            Map.of(),
            Map.of(),
            Map.of("repo-a", RepositoryDirtyState.inspectionError())
        );

        RichTextElement richText = assertInstanceOf(RichTextElement.class, rendered);
        var spans = richText.text().lines().getFirst().spans();
        assertEquals(3, spans.size());
        assertEquals(" !", spans.get(2).content());
        assertEquals(Color.RED, spans.get(2).style().fg().orElseThrow());
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
            Map.of("repo-a/child", List.of("base")),
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
            Map.of("repo-a/child", List.of("base")),
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

    @Test
    void buildHierarchyTreeMarksOverlappingJsonFilesAsDeepMerged() throws IOException {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path baseProfilePath = repositoryPath.resolve("base");
        Path childProfilePath = repositoryPath.resolve("child");
        Files.createDirectories(baseProfilePath);
        Files.createDirectories(childProfilePath);
        Path parentFile = baseProfilePath.resolve("opencode.json");
        Path childFile = childProfilePath.resolve("opencode.json");
        Files.writeString(parentFile, "{\"base\":true}");
        Files.writeString(childFile, "{\"child\":true}");

        ConfiguredRepository repository = new ConfiguredRepository(
            "repo-a",
            "git@example/repo-a.git",
            repositoryPath.toString(),
            List.of("base", "child")
        );

        List<TreeNode<NodeRef>> roots = HierarchyTreeBuilder.buildHierarchyTree(
            List.of(repository),
            List.of(),
            Map.of("repo-a/child", List.of("base")),
            4,
            20
        );

        TreeNode<NodeRef> childProfileNode = roots.getFirst().children().stream()
            .filter(node -> "child".equals(node.label()))
            .findFirst()
            .orElseThrow();
        TreeNode<NodeRef> mergedFileNode = childProfileNode.children().stream()
            .filter(node -> "opencode.json".equals(node.label()))
            .findFirst()
            .orElseThrow();

        assertEquals(childFile, mergedFileNode.data().path());
        assertFalse(mergedFileNode.data().inherited());
        assertTrue(mergedFileNode.data().deepMerged());
    }

    @Test
    void buildHierarchyTreeResolvesInheritedFilesFromParentInAnotherRepository() throws IOException {
        Path childRepositoryPath = tempDir.resolve("repo-child");
        Path parentRepositoryPath = tempDir.resolve("repo-parent");
        Path childProfilePath = childRepositoryPath.resolve("child");
        Path parentProfilePath = parentRepositoryPath.resolve("base");
        Files.createDirectories(childProfilePath);
        Files.createDirectories(parentProfilePath.resolve("nested"));
        Path inheritedFile = parentProfilePath.resolve("nested/shared.json");
        Files.writeString(inheritedFile, "{}");

        ConfiguredRepository childRepository = new ConfiguredRepository(
            "repo-child",
            "git@example/repo-child.git",
            childRepositoryPath.toString(),
            List.of("child")
        );
        ConfiguredRepository parentRepository = new ConfiguredRepository(
            "repo-parent",
            "git@example/repo-parent.git",
            parentRepositoryPath.toString(),
            List.of("base")
        );

        List<TreeNode<NodeRef>> roots = HierarchyTreeBuilder.buildHierarchyTree(
            List.of(childRepository, parentRepository),
            List.of(),
            Map.of("repo-child/child", List.of("base")),
            4,
            20
        );

        TreeNode<NodeRef> childRepositoryNode = roots.stream()
            .filter(node -> "repo-child".equals(node.label()))
            .findFirst()
            .orElseThrow();
        TreeNode<NodeRef> childProfileNode = childRepositoryNode.children().getFirst();
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
    void buildHierarchyTreeKeepsSingleParentInheritedJsonAsReadOnlyWithoutDeepMergeFlag() throws IOException {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path baseProfilePath = repositoryPath.resolve("base");
        Path childProfilePath = repositoryPath.resolve("child");
        Path parentFile = baseProfilePath.resolve("opencode.jsonc");
        Files.createDirectories(baseProfilePath);
        Files.createDirectories(childProfilePath);
        Files.writeString(parentFile, "{\"base\":true}");

        ConfiguredRepository repository = new ConfiguredRepository(
            "repo-a",
            "git@example/repo-a.git",
            repositoryPath.toString(),
            List.of("base", "child")
        );

        List<TreeNode<NodeRef>> roots = HierarchyTreeBuilder.buildHierarchyTree(
            List.of(repository),
            List.of(),
            Map.of("repo-a/child", List.of("base")),
            4,
            20
        );

        TreeNode<NodeRef> childProfileNode = roots.getFirst().children().stream()
            .filter(node -> "child".equals(node.label()))
            .findFirst()
            .orElseThrow();
        TreeNode<NodeRef> mergedFileNode = childProfileNode.children().stream()
            .filter(node -> "opencode.jsonc".equals(node.label()))
            .findFirst()
            .orElseThrow();

        assertEquals(parentFile, mergedFileNode.data().path());
        assertTrue(mergedFileNode.data().inherited());
        assertFalse(mergedFileNode.data().deepMerged());
        assertTrue(mergedFileNode.data().readOnly());
        assertEquals("base", mergedFileNode.data().inheritedFromProfile());
        assertEquals(List.of("base"), mergedFileNode.data().contributorProfileNames());
    }

    @Test
    void renderTreeNodeUsesLockIconForReadOnlyDeepMergedFileNodes() {
        TreeNode<NodeRef> node = TreeNode.of(
            "opencode.json",
            NodeRef.mergedReadOnlyFile(
                "repo-a",
                "child",
                Path.of("/tmp/repo-a/base/opencode.json"),
                "base",
                List.of("base"),
                List.of(Path.of("/tmp/repo-a/base/opencode.json")),
                true
            )
        );

        StyledElement<?> rendered = HierarchyTreeBuilder.renderTreeNode(node, Map.of(), Map.of(), Map.of());

        RichTextElement richText = assertInstanceOf(RichTextElement.class, rendered);
        var spans = richText.text().lines().getFirst().spans();
        assertEquals("🔒 ", spans.get(0).content());
    }

    @Test
    void buildHierarchyTreePreservesDeclaredParentOrderInContributorList() throws IOException {
        Path repositoryPath = tempDir.resolve("repo-order");
        Path baseOnePath = repositoryPath.resolve("base-one");
        Path baseTwoPath = repositoryPath.resolve("base-two");
        Path childProfilePath = repositoryPath.resolve("child");
        Path firstParentFile = baseOnePath.resolve("opencode.json");
        Path secondParentFile = baseTwoPath.resolve("opencode.json");
        Files.createDirectories(baseOnePath);
        Files.createDirectories(baseTwoPath);
        Files.createDirectories(childProfilePath);
        Files.writeString(firstParentFile, "{}\n");
        Files.writeString(secondParentFile, "{}\n");

        ConfiguredRepository repository = new ConfiguredRepository(
            "repo-order",
            "git@example/repo-order.git",
            repositoryPath.toString(),
            List.of("base-one", "base-two", "child")
        );

        List<TreeNode<NodeRef>> roots = HierarchyTreeBuilder.buildHierarchyTree(
            List.of(repository),
            List.of(),
            Map.of("repo-order/child", List.of("base-one", "base-two")),
            4,
            20
        );

        TreeNode<NodeRef> childProfileNode = roots.getFirst().children().stream()
            .filter(node -> "child".equals(node.label()))
            .findFirst()
            .orElseThrow();
        TreeNode<NodeRef> mergedFileNode = childProfileNode.children().stream()
            .filter(node -> "opencode.json".equals(node.label()))
            .findFirst()
            .orElseThrow();

        assertEquals(List.of("base-one", "base-two"), mergedFileNode.data().contributorProfileNames());
        assertEquals("base-two", mergedFileNode.data().contributorProfileNames().get(1));
    }

    @Test
    void buildHierarchyTreeOrdersNestedAncestorContributorsByDeclaredParentBranches() throws IOException {
        Path repositoryPath = tempDir.resolve("repo-nested-order");
        Path baseOneRootPath = repositoryPath.resolve("base-one-root");
        Path baseOnePath = repositoryPath.resolve("base-one");
        Path baseTwoRootPath = repositoryPath.resolve("base-two-root");
        Path baseTwoPath = repositoryPath.resolve("base-two");
        Path childPath = repositoryPath.resolve("child");
        Path firstBranchContributor = baseOneRootPath.resolve("opencode.json");
        Path secondBranchContributor = baseTwoRootPath.resolve("opencode.json");
        Files.createDirectories(baseOneRootPath);
        Files.createDirectories(baseOnePath);
        Files.createDirectories(baseTwoRootPath);
        Files.createDirectories(baseTwoPath);
        Files.createDirectories(childPath);
        Files.writeString(firstBranchContributor, "{}\n");
        Files.writeString(secondBranchContributor, "{}\n");

        ConfiguredRepository repository = new ConfiguredRepository(
            "repo-nested-order",
            "git@example/repo-nested-order.git",
            repositoryPath.toString(),
            List.of("base-one-root", "base-one", "base-two-root", "base-two", "child")
        );

        List<TreeNode<NodeRef>> roots = HierarchyTreeBuilder.buildHierarchyTree(
            List.of(repository),
            List.of(),
            Map.of(
                "repo-nested-order/base-one", List.of("base-one-root"),
                "repo-nested-order/base-two", List.of("base-two-root"),
                "repo-nested-order/child", List.of("base-one", "base-two")
            ),
            4,
            20
        );

        TreeNode<NodeRef> childProfileNode = roots.getFirst().children().stream()
            .filter(node -> "child".equals(node.label()))
            .findFirst()
            .orElseThrow();
        TreeNode<NodeRef> mergedFileNode = childProfileNode.children().stream()
            .filter(node -> "opencode.json".equals(node.label()))
            .findFirst()
            .orElseThrow();

        assertTrue(mergedFileNode.data().readOnly());
        assertTrue(mergedFileNode.data().parentOnlyMerged());
        assertEquals(List.of("base-one-root", "base-two-root"), mergedFileNode.data().contributorProfileNames());
        assertEquals(secondBranchContributor, mergedFileNode.data().path());
    }

}
