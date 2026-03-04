package com.github.alvarosanchez.ocp.command.interactive;

import com.github.alvarosanchez.ocp.model.Profile;
import com.github.alvarosanchez.ocp.service.RepositoryService.ConfiguredRepository;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.widgets.tree.TreeNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.text;

final class HierarchyTreeBuilder {

    private HierarchyTreeBuilder() {
    }

    static List<TreeNode<NodeRef>> buildHierarchyTree(
        List<ConfiguredRepository> repositories,
        List<Profile> profiles,
        int treeMaxDepth,
        int treeMaxChildren
    ) {
        String activeRepositoryName = activeRepositoryName(profiles);
        String activeProfileName = activeProfileName(profiles);

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
                TreeNode<NodeRef> profileNode = TreeNode.of(
                    profileLabel(profileName),
                    NodeRef.profile(repository.name(), profileName, profilePath)
                );

                if (Files.isDirectory(profilePath)) {
                    for (TreeNode<NodeRef> child : buildDirectoryNodes(
                        repository.name(),
                        profileName,
                        profilePath,
                        0,
                        treeMaxDepth,
                        treeMaxChildren
                    )) {
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

    static StyledElement<?> renderTreeNode(TreeNode<NodeRef> node, Map<String, Profile> profilesByName) {
        NodeRef data = node.data();
        if (data == null) {
            return text(node.label());
        }

        String icon = switch (data.kind()) {
            case REPOSITORY -> "📦 ";
            case PROFILE -> "👤 ";
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
            spans.add(Span.styled(" ✓", Style.EMPTY.bold().fg(Color.GREEN)));
        }
        if (hasUpdates) {
            spans.add(Span.styled(" ❄", Style.EMPTY.bold().fg(Color.YELLOW)));
        }

        return richText(
            Text.from(
                Line.from(spans)
            )
        );
    }

    private static List<TreeNode<NodeRef>> buildDirectoryNodes(
        String repositoryName,
        String profileName,
        Path directory,
        int depth,
        int treeMaxDepth,
        int treeMaxChildren
    ) {
        if (depth > treeMaxDepth) {
            return List.of(TreeNode.of("...", NodeRef.directory(repositoryName, profileName, directory)).leaf());
        }

        List<TreeNode<NodeRef>> children = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            List<Path> sorted = paths
                .sorted(Comparator
                    .comparing((Path path) -> Files.isDirectory(path) ? 0 : 1)
                    .thenComparing(path -> path.getFileName().toString().toLowerCase()))
                .limit(treeMaxChildren)
                .toList();

            for (Path child : sorted) {
                if (Files.isDirectory(child)) {
                    TreeNode<NodeRef> directoryNode = TreeNode.of(
                        child.getFileName().toString() + "/",
                        NodeRef.directory(repositoryName, profileName, child)
                    );
                    for (TreeNode<NodeRef> nested : buildDirectoryNodes(
                        repositoryName,
                        profileName,
                        child,
                        depth + 1,
                        treeMaxDepth,
                        treeMaxChildren
                    )) {
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
        } catch (IOException _) {
            children.add(TreeNode.of("[error reading directory]", NodeRef.directory(repositoryName, profileName, directory)).leaf());
        }

        return children;
    }

    private static String profileLabel(String profileName) {
        return profileName;
    }

    private static String activeProfileName(List<Profile> profiles) {
        return profiles.stream()
            .filter(Profile::active)
            .map(Profile::name)
            .findFirst()
            .orElse(null);
    }

    private static String activeRepositoryName(List<Profile> profiles) {
        return profiles.stream()
            .filter(Profile::active)
            .map(Profile::repositoryName)
            .findFirst()
            .orElse(null);
    }
}
