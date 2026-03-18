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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.text;

final class HierarchyTreeBuilder {

    private HierarchyTreeBuilder() {
    }

    static List<TreeNode<NodeRef>> buildHierarchyTree(
        List<ConfiguredRepository> repositories,
        List<Profile> profiles,
        Map<String, List<String>> profileParentByName,
        int treeMaxDepth,
        int treeMaxChildren
    ) {
        String activeRepositoryName = activeRepositoryName(profiles);
        String activeProfileName = activeProfileName(profiles);
        Map<String, Path> profilePathByName = profilePathByName(repositories);

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

                List<TreeNode<NodeRef>> profileChildren = buildProfileChildren(
                    repository.name(),
                    profileName,
                    profilePath,
                    profilePathByName,
                    profileParentByName,
                    treeMaxDepth,
                    treeMaxChildren
                );
                if (!profileChildren.isEmpty()) {
                    for (TreeNode<NodeRef> child : profileChildren) {
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

    static StyledElement<?> renderTreeNode(
        TreeNode<NodeRef> node,
        Map<String, Profile> profilesByName,
        Map<String, List<String>> profileParentByName,
        Map<String, RepositoryDirtyState> repositoryDirtyStateByName
    ) {
        NodeRef data = node.data();
        if (data == null) {
            return text(node.label());
        }

        String icon = switch (data.kind()) {
            case REPOSITORY -> "📦 ";
            case PROFILE -> "👤 ";
            case DIRECTORY -> "📁 ";
            case FILE -> data.readOnly() ? "🔒 " : data.deepMerged() ? "⛙ " : "📄 ";
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
        RepositoryDirtyState repositoryDirtyState = data.kind() == NodeKind.REPOSITORY
            ? repositoryDirtyStateByName.getOrDefault(data.repositoryName(), RepositoryDirtyState.clean())
            : RepositoryDirtyState.clean();
        boolean hasLocalChanges = data.kind() == NodeKind.REPOSITORY && repositoryDirtyState.hasLocalChanges();
        boolean inspectionFailed = data.kind() == NodeKind.REPOSITORY && repositoryDirtyState.inspectionFailed();
        if (data.kind() == NodeKind.PROFILE) {
            Profile profile = profilesByName.get(profileKey(data.repositoryName(), data.profileName()));
            if (profile != null && profile.active()) {
                labelStyle = labelStyle.bold().fg(Color.BRIGHT_WHITE);
                isCurrentProfile = true;
            }
            if (profile != null && profile.updateAvailable()) {
                hasUpdates = true;
            }
            List<String> parentProfileNames = profileParentByName.get(profileKey(data.repositoryName(), data.profileName()));
            if (parentProfileNames != null && !parentProfileNames.isEmpty()) {
                labelStyle = labelStyle.fg(Color.BRIGHT_WHITE);
            }
        }
        if (data.kind() == NodeKind.FILE && (data.readOnly() || data.deepMerged())) {
            labelStyle = labelStyle.fg(Color.GRAY).dim();
        }

        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled(icon, Style.EMPTY.bold().fg(iconColor)));
        if (data.kind() == NodeKind.PROFILE) {
            List<String> parentProfileNames = profileParentByName.get(profileKey(data.repositoryName(), data.profileName()));
            if (parentProfileNames != null && !parentProfileNames.isEmpty()) {
                spans.add(Span.styled(node.label() + " ⇢ 👤 " + String.join(", ", parentProfileNames), labelStyle));
            } else {
                spans.add(Span.styled(node.label(), labelStyle));
            }
        } else {
            spans.add(Span.styled(node.label(), labelStyle));
        }
        if (isCurrentProfile) {
            spans.add(Span.styled(" ✓", Style.EMPTY.bold().fg(Color.GREEN)));
        }
        if (hasUpdates) {
            spans.add(Span.styled(" ❄", Style.EMPTY.bold().fg(Color.YELLOW)));
        }
        if (hasLocalChanges) {
            spans.add(Span.styled(" ✏", Style.EMPTY.bold().fg(Color.YELLOW)));
        }
        if (inspectionFailed) {
            spans.add(Span.styled(" !", Style.EMPTY.bold().fg(Color.RED)));
        }

        return richText(
            Text.from(
                Line.from(spans)
            )
        );
    }

    private static List<TreeNode<NodeRef>> buildProfileChildren(
        String repositoryName,
        String profileName,
        Path profilePath,
        Map<String, Path> profilePathByName,
        Map<String, List<String>> profileParentByName,
        int treeMaxDepth,
        int treeMaxChildren
    ) {
        List<ResolvedProfileFile> resolvedFiles = resolveProfileFiles(
            repositoryName,
            profileName,
            profilePathByName,
            profileParentByName
        );
        if (resolvedFiles.isEmpty()) {
            return List.of();
        }

        VirtualDirectory root = new VirtualDirectory();
        for (ResolvedProfileFile file : resolvedFiles) {
            if (file.relativePath().getNameCount() == 0) {
                continue;
            }
            VirtualDirectory current = root;
            for (int index = 0; index < file.relativePath().getNameCount() - 1; index++) {
                String segment = file.relativePath().getName(index).toString();
                current = current.directories.computeIfAbsent(segment, ignored -> new VirtualDirectory());
            }
            current.files.put(file.relativePath().getFileName().toString(), file);
        }

        return buildVirtualDirectoryNodes(
            repositoryName,
            profileName,
            profilePath,
            Path.of(""),
            root,
            0,
            treeMaxDepth,
            treeMaxChildren
        );
    }

    private static List<TreeNode<NodeRef>> buildVirtualDirectoryNodes(
        String repositoryName,
        String profileName,
        Path profilePath,
        Path relativeDirectory,
        VirtualDirectory directory,
        int depth,
        int treeMaxDepth,
        int treeMaxChildren
    ) {
        if (depth > treeMaxDepth) {
            return List.of(TreeNode.of("...", NodeRef.directory(repositoryName, profileName, profilePath.resolve(relativeDirectory))).leaf());
        }

        List<TreeNode<NodeRef>> children = new ArrayList<>();
        int emitted = 0;

        List<String> directoryNames = directory.directories.keySet().stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        for (String childDirectoryName : directoryNames) {
            if (emitted >= treeMaxChildren) {
                children.add(TreeNode.of("...", NodeRef.directory(repositoryName, profileName, profilePath.resolve(relativeDirectory))).leaf());
                return children;
            }
            Path childRelativePath = relativeDirectory.resolve(childDirectoryName);
            TreeNode<NodeRef> directoryNode = TreeNode.of(
                childDirectoryName + "/",
                NodeRef.directory(repositoryName, profileName, profilePath.resolve(childRelativePath))
            );
            for (TreeNode<NodeRef> nested : buildVirtualDirectoryNodes(
                repositoryName,
                profileName,
                profilePath,
                childRelativePath,
                directory.directories.get(childDirectoryName),
                depth + 1,
                treeMaxDepth,
                treeMaxChildren
            )) {
                directoryNode.add(nested);
            }
            children.add(directoryNode);
            emitted++;
        }

        List<String> fileNames = directory.files.keySet().stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        for (String fileName : fileNames) {
            if (emitted >= treeMaxChildren) {
                children.add(TreeNode.of("...", NodeRef.directory(repositoryName, profileName, profilePath.resolve(relativeDirectory))).leaf());
                return children;
            }
            ResolvedProfileFile file = directory.files.get(fileName);
            NodeRef fileNodeRef = file.readOnly() && file.deepMerged()
                ? NodeRef.mergedReadOnlyFile(
                    repositoryName,
                    profileName,
                    file.sourcePath(),
                    file.inheritedFromProfile(),
                    file.contributorProfileNames(),
                    file.contributorSourcePaths(),
                    file.parentOnlyMerged()
                )
                : file.inherited()
                    ? NodeRef.inheritedFile(repositoryName, profileName, file.sourcePath(), file.inheritedFromProfile())
                    : file.deepMerged()
                        ? NodeRef.deepMergedFile(repositoryName, profileName, file.sourcePath())
                        : NodeRef.file(repositoryName, profileName, file.sourcePath());
            children.add(
                TreeNode.of(
                    fileName,
                    fileNodeRef
                ).leaf()
            );
            emitted++;
        }

        return children;
    }

    private static List<ResolvedProfileFile> resolveProfileFiles(
        String repositoryName,
        String profileName,
        Map<String, Path> profilePathByName,
        Map<String, List<String>> profileParentByName
    ) {
        Map<Path, ResolvedProfileFile> filesByRelativePath = new LinkedHashMap<>();
        Map<String, String> profileKeyByProfileName = profileKeyByProfileName(profilePathByName);

        Set<String> visitedProfiles = new HashSet<>();
        List<String> parentProfileNames = profileParentByName.getOrDefault(profileKey(repositoryName, profileName), List.of());
        for (int parentIndex = parentProfileNames.size() - 1; parentIndex >= 0; parentIndex--) {
            collectParentFiles(
                parentProfileNames.get(parentIndex),
                profilePathByName,
                profileParentByName,
                profileKeyByProfileName,
                visitedProfiles,
                filesByRelativePath
            );
        }

        Path profilePath = profilePathByName.get(profileKey(repositoryName, profileName));
        collectOwnFiles(profilePath, profileName, false, filesByRelativePath);

        List<String> declaredParentOrder = profileParentByName.getOrDefault(
            profileKey(repositoryName, profileName),
            List.of()
        );
        Map<String, Integer> contributorBranchPrecedence = contributorBranchPrecedence(
            declaredParentOrder,
            profileParentByName,
            profileKeyByProfileName
        );
        return filesByRelativePath.values().stream()
            .map(file -> reorderContributors(file, declaredParentOrder, contributorBranchPrecedence))
            .sorted(Comparator.comparing(file -> file.relativePath().toString().toLowerCase()))
            .toList();
    }

    private static Map<String, Integer> contributorBranchPrecedence(
        List<String> declaredParentOrder,
        Map<String, List<String>> profileParentByName,
        Map<String, String> profileKeyByProfileName
    ) {
        if (declaredParentOrder == null || declaredParentOrder.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> precedenceByProfileName = new HashMap<>();
        for (int parentIndex = 0; parentIndex < declaredParentOrder.size(); parentIndex++) {
            String parentProfileName = declaredParentOrder.get(parentIndex);
            collectBranchPrecedence(
                parentProfileName,
                parentIndex,
                profileParentByName,
                profileKeyByProfileName,
                new HashSet<>(),
                precedenceByProfileName
            );
        }
        return Map.copyOf(precedenceByProfileName);
    }

    private static void collectBranchPrecedence(
        String profileName,
        int parentIndex,
        Map<String, List<String>> profileParentByName,
        Map<String, String> profileKeyByProfileName,
        Set<String> visitedProfiles,
        Map<String, Integer> precedenceByProfileName
    ) {
        if (profileName == null || profileName.isBlank() || !visitedProfiles.add(profileName)) {
            return;
        }
        precedenceByProfileName.merge(profileName, parentIndex, Math::max);

        String profileKey = profileKeyByProfileName.get(profileName);
        if (profileKey == null) {
            return;
        }
        for (String parentProfileName : profileParentByName.getOrDefault(profileKey, List.of())) {
            collectBranchPrecedence(
                parentProfileName,
                parentIndex,
                profileParentByName,
                profileKeyByProfileName,
                visitedProfiles,
                precedenceByProfileName
            );
        }
    }

    private static void collectParentFiles(
        String profileName,
        Map<String, Path> profilePathByName,
        Map<String, List<String>> profileParentByName,
        Map<String, String> profileKeyByProfileName,
        Set<String> visitedProfiles,
        Map<Path, ResolvedProfileFile> filesByRelativePath
    ) {
        if (profileName == null || profileName.isBlank() || !visitedProfiles.add(profileName)) {
            return;
        }

        String parentProfileKey = profileKeyByProfileName.get(profileName);
        if (parentProfileKey != null) {
            List<String> parentProfileNames = profileParentByName.getOrDefault(parentProfileKey, List.of());
            for (int parentIndex = parentProfileNames.size() - 1; parentIndex >= 0; parentIndex--) {
                collectParentFiles(
                    parentProfileNames.get(parentIndex),
                    profilePathByName,
                    profileParentByName,
                    profileKeyByProfileName,
                    visitedProfiles,
                    filesByRelativePath
                );
            }
        }

        collectOwnFiles(
            profilePathByName.get(parentProfileKey),
            profileName,
            true,
            filesByRelativePath
        );
    }

    private static void collectOwnFiles(
        Path profilePath,
        String profileName,
        boolean inherited,
        Map<Path, ResolvedProfileFile> filesByRelativePath
    ) {
        if (profilePath == null || !Files.isDirectory(profilePath)) {
            return;
        }
        try (var paths = Files.walk(profilePath)) {
            List<Path> files = paths
                .filter(path -> Files.isRegularFile(path))
                .sorted()
                .toList();

            for (Path file : files) {
                Path relativePath = profilePath.relativize(file);
                ResolvedProfileFile existing = filesByRelativePath.get(relativePath);
                if (existing == null) {
                    filesByRelativePath.put(
                        relativePath,
                        new ResolvedProfileFile(
                            relativePath,
                            file,
                            inherited,
                            inherited ? profileName : null,
                            false,
                            inherited,
                            false,
                            java.util.List.of(profileName),
                            java.util.List.of(file)
                        )
                    );
                    continue;
                }
                if (inherited && existing.inherited()) {
                    boolean deepMerged = isMergeableJsonFile(relativePath);
                    filesByRelativePath.put(
                        relativePath,
                        new ResolvedProfileFile(
                            relativePath,
                            file,
                            true,
                            existing.inheritedFromProfile(),
                            deepMerged,
                            true,
                            true,
                            mergeContributorProfileNames(existing.contributorProfileNames(), profileName),
                            mergeContributorSourcePaths(existing.contributorSourcePaths(), file)
                        )
                    );
                    continue;
                }
                if (!inherited && existing.inherited()) {
                    boolean deepMerged = isMergeableJsonFile(relativePath);
                    filesByRelativePath.put(
                        relativePath,
                        new ResolvedProfileFile(
                            relativePath,
                            file,
                            false,
                            existing.inheritedFromProfile(),
                            deepMerged,
                            false,
                            false,
                            mergeContributorProfileNames(existing.contributorProfileNames(), profileName),
                            mergeContributorSourcePaths(existing.contributorSourcePaths(), file)
                        )
                    );
                }
            }
        } catch (IOException e) {
            return;
        }
    }

    private static boolean isMergeableJsonFile(Path relativePath) {
        String fileName = relativePath.getFileName().toString();
        return fileName.endsWith(".json") || fileName.endsWith(".jsonc");
    }

    private static Map<String, Path> profilePathByName(List<ConfiguredRepository> repositories) {
        Map<String, Path> profilePathByName = new HashMap<>();
        for (ConfiguredRepository repository : repositories) {
            Path repositoryPath = Path.of(repository.localPath());
            for (String profileName : repository.resolvedProfiles()) {
                profilePathByName.put(profileKey(repository.name(), profileName), repositoryPath.resolve(profileName));
            }
        }
        return profilePathByName;
    }

    private static Map<String, String> profileKeyByProfileName(Map<String, Path> profilePathByName) {
        Map<String, String> byName = new HashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        for (String qualifiedKey : profilePathByName.keySet()) {
            int separatorIndex = qualifiedKey.indexOf('/');
            String profileName = separatorIndex < 0 ? qualifiedKey : qualifiedKey.substring(separatorIndex + 1);
            if (ambiguousNames.contains(profileName)) {
                continue;
            }
            String previous = byName.putIfAbsent(profileName, qualifiedKey);
            if (previous != null && !previous.equals(qualifiedKey)) {
                byName.remove(profileName);
                ambiguousNames.add(profileName);
            }
        }
        return byName;
    }

    private static String profileKey(String repositoryName, String profileName) {
        return repositoryName + "/" + profileName;
    }

    private static String profileLabel(String profileName) {
        return profileName;
    }

    private static List<String> mergeContributorProfileNames(List<String> existingContributorProfileNames, String profileName) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existingContributorProfileNames != null) {
            merged.addAll(existingContributorProfileNames);
        }
        if (profileName != null && !profileName.isBlank()) {
            merged.add(profileName);
        }
        return List.copyOf(merged);
    }

    private static List<Path> mergeContributorSourcePaths(List<Path> existingContributorSourcePaths, Path contributorPath) {
        ArrayList<Path> merged = new ArrayList<>();
        if (existingContributorSourcePaths != null) {
            merged.addAll(existingContributorSourcePaths);
        }
        if (contributorPath != null) {
            merged.add(contributorPath);
        }
        return List.copyOf(merged);
    }

    private static ResolvedProfileFile reorderContributors(
        ResolvedProfileFile file,
        List<String> declaredParentOrder,
        Map<String, Integer> contributorBranchPrecedence
    ) {
        if (!file.readOnly()) {
            return file;
        }
        if ((declaredParentOrder == null || declaredParentOrder.isEmpty())
            && (contributorBranchPrecedence == null || contributorBranchPrecedence.isEmpty())) {
            return file;
        }
        List<String> names = file.contributorProfileNames();
        List<Path> paths = file.contributorSourcePaths();
        if (names == null || names.isEmpty() || paths == null || paths.isEmpty()) {
            return file;
        }

        LinkedHashMap<String, Path> contributorMap = new LinkedHashMap<>();
        for (int index = 0; index < names.size(); index++) {
            contributorMap.put(names.get(index), paths.get(index));
        }

        Map<String, Integer> precedenceByContributor = new HashMap<>();
        if (declaredParentOrder != null) {
            for (int parentIndex = 0; parentIndex < declaredParentOrder.size(); parentIndex++) {
                precedenceByContributor.putIfAbsent(declaredParentOrder.get(parentIndex), parentIndex);
            }
        }
        if (contributorBranchPrecedence != null) {
            for (Map.Entry<String, Integer> entry : contributorBranchPrecedence.entrySet()) {
                precedenceByContributor.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }

        LinkedHashMap<String, Path> ordered = new LinkedHashMap<>();
        for (String name : names) {
            if (!precedenceByContributor.containsKey(name)) {
                ordered.put(name, contributorMap.get(name));
            }
        }

        int maxPrecedence = precedenceByContributor.values().stream()
            .max(Integer::compareTo)
            .orElse(-1);
        for (int precedence = 0; precedence <= maxPrecedence; precedence++) {
            for (String name : names) {
                if (Integer.valueOf(precedence).equals(precedenceByContributor.get(name))) {
                    ordered.put(name, contributorMap.get(name));
                }
            }
        }

        for (String name : names) {
            ordered.putIfAbsent(name, contributorMap.get(name));
        }

        List<String> reorderedNames = List.copyOf(ordered.keySet());
        if (reorderedNames.equals(names)) {
            return file;
        }
        List<Path> reorderedPaths = List.copyOf(ordered.values());
        Path newSourcePath = reorderedPaths.isEmpty() ? file.sourcePath() : reorderedPaths.get(reorderedPaths.size() - 1);
        return new ResolvedProfileFile(
            file.relativePath(),
            newSourcePath,
            file.inherited(),
            file.inheritedFromProfile(),
            file.deepMerged(),
            file.readOnly(),
            file.parentOnlyMerged(),
            reorderedNames,
            reorderedPaths
        );
    }

    private record ResolvedProfileFile(
        Path relativePath,
        Path sourcePath,
        boolean inherited,
        String inheritedFromProfile,
        boolean deepMerged,
        boolean readOnly,
        boolean parentOnlyMerged,
        List<String> contributorProfileNames,
        List<Path> contributorSourcePaths
    ) {
    }

    private static final class VirtualDirectory {
        private final Map<String, VirtualDirectory> directories = new HashMap<>();
        private final Map<String, ResolvedProfileFile> files = new HashMap<>();
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
