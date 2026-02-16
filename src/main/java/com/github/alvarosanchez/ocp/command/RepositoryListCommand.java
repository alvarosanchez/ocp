package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.service.RepositoryService;
import com.github.alvarosanchez.ocp.service.RepositoryService.ConfiguredRepository;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "list", description = "List configured repositories.")
class RepositoryListCommand implements Callable<Integer> {

    private final RepositoryService repositoryService;

    @Inject
    RepositoryListCommand(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @Override
    public Integer call() {
        try {
            List<ConfiguredRepository> repositories = repositoryService.listConfiguredRepositories();
            if (repositories.isEmpty()) {
                Cli.warning("No repositories available yet. Add one with `ocp repository add`.");
                return 0;
            }

            RepositoryBoxRenderer.print(repositories);
            return 0;
        } catch (RuntimeException e) {
            Cli.error(e.getMessage());
            return 1;
        }
    }
}
