package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.alvarosanchez.ocp.command.Cli;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.service.OnboardingService;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.elements.TreeElement;
import dev.tamboui.widgets.tree.TreeNode;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveAppSelectionRefreshTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private ObjectMapper objectMapper;
    private String previousConfigDir;
    private String previousCacheDir;
    private String previousOpenCodeConfigDir;
    private String previousWorkingDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        objectMapper = applicationContext.getBean(ObjectMapper.class);
        Cli.init();
        previousConfigDir = System.getProperty("ocp.config.dir");
        previousCacheDir = System.getProperty("ocp.cache.dir");
        previousOpenCodeConfigDir = System.getProperty("ocp.opencode.config.dir");
        previousWorkingDir = System.getProperty("ocp.working.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("cache").toString());
        System.setProperty("ocp.opencode.config.dir", tempDir.resolve("opencode").toString());
        System.setProperty("ocp.working.dir", tempDir.resolve("workspace").toString());
    }

    @AfterEach
    void tearDown() {
        applicationContext.close();
        restoreProperty("ocp.config.dir", previousConfigDir);
        restoreProperty("ocp.cache.dir", previousCacheDir);
        restoreProperty("ocp.opencode.config.dir", previousOpenCodeConfigDir);
        restoreProperty("ocp.working.dir", previousWorkingDir);
    }

    @Test
    void reloadStateRefreshesSelectedFilePreviewWhenSameFileRemainsSelected() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path filePath = profilePath.resolve("opencode.json");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(filePath, "first");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);
        selectTreeNode(app, node -> node.kind() == NodeKind.FILE && filePath.equals(node.path()));
        invokeSyncSelectionAndPreview(app);
        assertEquals("first", readSelectedFileContent(app));

        Files.writeString(filePath, "second");

        invokeReloadState(app);

        assertEquals("second", readSelectedFileContent(app));
        assertEquals("second", readSelectedFilePreview(app).lines().getFirst().spans().getFirst().content());
    }

    private InteractiveApp createApp() {
        return new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            objectMapper
        );
    }

    private void writeConfig(RepositoryEntry repositoryEntry) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(
            configDir.resolve("config.json"),
            objectMapper.writeValueAsString(new OcpConfigFile(new OcpConfigOptions(), List.of(repositoryEntry)))
        );
    }

    @SuppressWarnings("unchecked")
    private static void selectTreeNode(InteractiveApp app, Predicate<NodeRef> predicate) throws Exception {
        Field rootsField = InteractiveApp.class.getDeclaredField("hierarchyRoots");
        rootsField.setAccessible(true);
        List<TreeNode<NodeRef>> roots = (List<TreeNode<NodeRef>>) rootsField.get(app);

        Field field = InteractiveApp.class.getDeclaredField("hierarchyTree");
        field.setAccessible(true);
        TreeElement<NodeRef> tree = (TreeElement<NodeRef>) field.get(app);

        Field lastFlatEntriesField = TreeElement.class.getDeclaredField("lastFlatEntries");
        lastFlatEntriesField.setAccessible(true);
        lastFlatEntriesField.set(tree, buildFlatEntries(roots));

        int selectedIndex = 0;
        for (TreeNode<NodeRef> root : roots) {
            int match = findNodeIndex(root, null, 0, predicate, selectedIndex);
            if (match >= 0) {
                tree.selected(match);
                TreeNode<NodeRef> selectedTreeNode = tree.selectedNode();
                if (selectedTreeNode != null && selectedTreeNode.data() != null && predicate.test(selectedTreeNode.data())) {
                    return;
                }
                break;
            }
            selectedIndex += countVisibleNodes(root);
        }
        fail("Expected tree node was not selected.");
    }

    private static List<Object> buildFlatEntries(List<TreeNode<NodeRef>> roots) throws Exception {
        List<Object> flatEntries = new ArrayList<>();
        Constructor<?> constructor = Class.forName("dev.tamboui.widgets.tree.TreeWidget$FlatEntry")
            .getDeclaredConstructor(Object.class, Object.class, int.class, List.class, boolean.class);
        constructor.setAccessible(true);
        for (TreeNode<NodeRef> root : roots) {
            addFlatEntries(flatEntries, constructor, root, null, 0);
        }
        return flatEntries;
    }

    private static void addFlatEntries(List<Object> flatEntries, Constructor<?> constructor, TreeNode<NodeRef> node, TreeNode<NodeRef> parent, int depth)
        throws Exception {
        flatEntries.add(constructor.newInstance(node, parent, depth, List.of(), false));
        if (!node.isLeaf() && node.isExpanded()) {
            for (TreeNode<NodeRef> child : node.children()) {
                addFlatEntries(flatEntries, constructor, child, node, depth + 1);
            }
        }
    }

    private static int findNodeIndex(
        TreeNode<NodeRef> node,
        TreeNode<NodeRef> parent,
        int depth,
        Predicate<NodeRef> predicate,
        int currentIndex
    ) {
        if (node.data() != null && predicate.test(node.data())) {
            return currentIndex;
        }
        int nextIndex = currentIndex + 1;
        if (!node.isLeaf() && node.isExpanded()) {
            for (TreeNode<NodeRef> child : node.children()) {
                int match = findNodeIndex(child, node, depth + 1, predicate, nextIndex);
                if (match >= 0) {
                    return match;
                }
                nextIndex += countVisibleNodes(child);
            }
        }
        return -1;
    }

    private static int countVisibleNodes(TreeNode<NodeRef> node) {
        int count = 1;
        if (!node.isLeaf() && node.isExpanded()) {
            for (TreeNode<NodeRef> child : node.children()) {
                count += countVisibleNodes(child);
            }
        }
        return count;
    }

    private static void invokeReloadState(InteractiveApp app) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("reloadState");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static void invokeSyncSelectionAndPreview(InteractiveApp app) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("syncSelectionAndPreview");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static String readSelectedFileContent(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("selectedFileContent");
        field.setAccessible(true);
        return (String) field.get(app);
    }

    private static Text readSelectedFilePreview(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("selectedFilePreview");
        field.setAccessible(true);
        return (Text) field.get(app);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
