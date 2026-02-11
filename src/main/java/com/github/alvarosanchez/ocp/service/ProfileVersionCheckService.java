package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import com.github.alvarosanchez.ocp.model.OcpConfigFile.RepositoryEntry;
import io.micronaut.context.annotation.Context;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Service that checks whether configured repositories are behind their remotes.
 */
@Context
public final class ProfileVersionCheckService {

    private final RepositoryService repositoryService;
    private final GitRepositoryClient gitRepositoryClient;

    ProfileVersionCheckService(RepositoryService repositoryService, GitRepositoryClient gitRepositoryClient) {
        this.repositoryService = repositoryService;
        this.gitRepositoryClient = gitRepositoryClient;
        printUpdateHints(System.out);
    }

    /**
     * Prints non-fatal update hints for repositories that are behind remote.
     *
     * @param out output stream used for hint messages
     */
    public void printUpdateHints(PrintStream out) {
        try {
            if (!repositoryService.loadConfigFile().config().profileVersionCheck()) {
                return;
            }

            List<String> failedChecks = new ArrayList<>();
            for (RepositoryEntry repositoryEntry : repositoryService.load()) {
                Path localRepositoryPath = Path.of(repositoryEntry.localPath());
                if (!Files.exists(localRepositoryPath.resolve(".git"))) {
                    continue;
                }

                try {
                    int commitsBehindRemote = gitRepositoryClient.commitsBehindRemote(localRepositoryPath);
                    if (commitsBehindRemote > 0) {
                        out.println(
                            "Hint: repository `"
                                + repositoryEntry.name()
                                + "` is behind remote by "
                                + commitsBehindRemote
                                + " commit(s). Run `ocp profile refresh <profile>` to update."
                        );
                    }
                } catch (RuntimeException e) {
                    failedChecks.add(repositoryEntry.name());
                }
            }

            if (!failedChecks.isEmpty()) {
                out.println("Hint: skipped version checks for repositories: " + String.join(", ", failedChecks));
            }
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null || e.getMessage().isBlank() ? "unknown error" : e.getMessage();
            out.println("Hint: skipped version checks: " + detail);
        }
    }
}
