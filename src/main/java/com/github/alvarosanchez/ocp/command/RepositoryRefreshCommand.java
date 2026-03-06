package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "refresh", description = "Pull latest changes for configured repositories.")
class RepositoryRefreshCommand implements Callable<Integer> {

    private final ProfileService profileService;
    private final RepositoryService repositoryService;

    @Inject
    RepositoryRefreshCommand(ProfileService profileService, RepositoryService repositoryService) {
        this.profileService = profileService;
        this.repositoryService = repositoryService;
    }

    @CommandLine.Parameters(index = "0", arity = "0..1", description = "Repository name.")
    private String repositoryName;

    @Override
    public Integer call() {
        try {
            if (repositoryName == null || repositoryName.isBlank()) {
                List<RepositoryEntry> repositories = repositoryService.load();
                long gitBackedCount = repositories.stream().filter(RepositoryEntry::isGitBacked).count();
                if (gitBackedCount == 0) {
                    Cli.info("All configured repositories are file-based; nothing to refresh.");
                    return 0;
                }
                long fileBasedCount = repositories.size() - gitBackedCount;
                RefreshOutcome refreshOutcome = refreshAllWithConflictResolution();
                ProfileConfigChangeNotifier.notifyUserConfigChanges(refreshOutcome.refreshResult());
                String message = refreshOutcome.message();
                if (fileBasedCount > 0) {
                    message = message + " " + skippedFileBasedRepositoriesMessage(fileBasedCount);
                }
                Cli.success(message);
                return 0;
            }

            String normalizedRepositoryName = repositoryName.trim();
            RepositoryEntry repositoryEntry = repositoryService
                .load()
                .stream()
                .filter(entry -> entry.name().equals(normalizedRepositoryName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Repository `" + normalizedRepositoryName + "` was not found."));
            if (!repositoryEntry.isGitBacked()) {
                Cli.info("Repository `" + repositoryEntry.name() + "` is file-based; nothing to refresh.");
                return 0;
            }

            RefreshOutcome refreshOutcome = refreshSingleWithConflictResolution(normalizedRepositoryName);
            ProfileConfigChangeNotifier.notifyUserConfigChanges(refreshOutcome.refreshResult());
            Cli.success(refreshOutcome.message());
            return 0;
        } catch (ProfileService.DuplicateProfilesException e) {
            Cli.error("duplicate profile names found: " + String.join(", ", e.duplicateProfileNames()));
            return 1;
        } catch (RuntimeException e) {
            Cli.error(e.getMessage());
            return 1;
        }
    }

    private RefreshOutcome refreshSingleWithConflictResolution(String name) {
        boolean discardedRepositoryLocalChanges = false;
        boolean forcePushedLocalChanges = false;
        boolean reappliedMergedProfileFiles = false;
        while (true) {
            try {
                ProfileService.ProfileRefreshResult refreshResult = profileService.refreshRepositoryWithDetails(name);
                if (forcePushedLocalChanges && reappliedMergedProfileFiles) {
                    return new RefreshOutcome(
                        "Reapplied active profile, committed local changes, force-pushed, and refreshed repository `" + name + "`.",
                        refreshResult
                    );
                }
                if (forcePushedLocalChanges) {
                    return new RefreshOutcome(
                        "Committed local changes, force-pushed, and refreshed repository `" + name + "`.",
                        refreshResult
                    );
                }
                if (discardedRepositoryLocalChanges && reappliedMergedProfileFiles) {
                    return new RefreshOutcome(
                        "Reapplied active profile, discarded repository local changes, and refreshed repository `" + name + "`.",
                        refreshResult
                    );
                }
                if (discardedRepositoryLocalChanges) {
                    return new RefreshOutcome(
                        "Discarded local changes and refreshed repository `" + name + "`.",
                        refreshResult
                    );
                }
                if (reappliedMergedProfileFiles) {
                    return new RefreshOutcome(
                        "Reapplied active profile and refreshed repository `" + name + "`.",
                        refreshResult
                    );
                }
                return new RefreshOutcome("Refreshed repository `" + name + "`.", refreshResult);
            } catch (ProfileService.ProfileRefreshConflictException conflict) {
                ProfileService.RefreshConflictResolution resolution = promptRefreshConflictResolution(conflict);
                if (resolution == ProfileService.RefreshConflictResolution.DO_NOTHING) {
                    throw new IllegalStateException("Refresh cancelled. Local changes were left untouched.");
                }
                applyRefreshConflictResolution(conflict, resolution);
                discardedRepositoryLocalChanges = discardedRepositoryLocalChanges
                    || resolution == ProfileService.RefreshConflictResolution.DISCARD_AND_REFRESH;
                forcePushedLocalChanges = forcePushedLocalChanges
                    || resolution == ProfileService.RefreshConflictResolution.COMMIT_AND_FORCE_PUSH;
            } catch (ProfileService.ProfileRefreshUserConfigConflictException conflict) {
                ProfileService.RefreshConflictResolution resolution = promptMergedProfileRefreshConflictResolution(conflict);
                if (resolution == ProfileService.RefreshConflictResolution.DO_NOTHING) {
                    throw new IllegalStateException("Refresh cancelled. Local changes were left untouched.");
                }
                applyMergedProfileRefreshConflictResolution(conflict, resolution);
                reappliedMergedProfileFiles = true;
            }
        }
    }

    private RefreshOutcome refreshAllWithConflictResolution() {
        boolean discardedLocalChanges = false;
        boolean forcePushedLocalChanges = false;
        while (true) {
            try {
                ProfileService.ProfileRefreshResult refreshResult = profileService.refreshAllRepositoriesWithDetails();
                if (forcePushedLocalChanges) {
                    return new RefreshOutcome(
                        "Resolved local changes (including commit + force push) and refreshed all repositories.",
                        refreshResult
                    );
                }
                if (discardedLocalChanges) {
                    return new RefreshOutcome(
                        "Discarded local changes where needed and refreshed all repositories.",
                        refreshResult
                    );
                }
                return new RefreshOutcome("Refreshed all repositories.", refreshResult);
            } catch (ProfileService.ProfileRefreshConflictException conflict) {
                ProfileService.RefreshConflictResolution resolution = promptRefreshConflictResolution(conflict);
                if (resolution == ProfileService.RefreshConflictResolution.DO_NOTHING) {
                    throw new IllegalStateException("Refresh cancelled. Local changes were left untouched.");
                }
                applyRefreshConflictResolution(conflict, resolution);
                discardedLocalChanges = discardedLocalChanges
                    || resolution == ProfileService.RefreshConflictResolution.DISCARD_AND_REFRESH;
                forcePushedLocalChanges = forcePushedLocalChanges
                    || resolution == ProfileService.RefreshConflictResolution.COMMIT_AND_FORCE_PUSH;
            } catch (ProfileService.ProfileRefreshUserConfigConflictException conflict) {
                ProfileService.RefreshConflictResolution resolution = promptMergedProfileRefreshConflictResolution(conflict);
                if (resolution == ProfileService.RefreshConflictResolution.DO_NOTHING) {
                    throw new IllegalStateException("Refresh cancelled. Local changes were left untouched.");
                }
                applyMergedProfileRefreshConflictResolution(conflict, resolution);
                discardedLocalChanges = true;
            }
        }
    }

    private ProfileService.RefreshConflictResolution promptMergedProfileRefreshConflictResolution(
        ProfileService.ProfileRefreshUserConfigConflictException conflict
    ) {
        Cli.warning("Local changes detected in merged active profile files for profile `" + conflict.profileName() + "`.");
        Cli.info("Modified files in `" + conflict.targetDirectory() + "`:");
        for (Path driftedFile : conflict.driftedFiles()) {
            Cli.print("- " + driftedFile);
        }
        Cli.info("Choose how to proceed:");
        Cli.warning("1) Discard local merged-file changes and refresh.");
        Cli.warning("2) Do nothing and fix manually.");

        while (true) {
            Cli.info("Enter option [1-2]:");
            String option = readInputLine().trim();
            if ("1".equals(option)) {
                return ProfileService.RefreshConflictResolution.DISCARD_AND_REFRESH;
            }
            if ("2".equals(option)) {
                return ProfileService.RefreshConflictResolution.DO_NOTHING;
            }
            Cli.error("Invalid option. Please enter 1 or 2.");
        }
    }

    private ProfileService.RefreshConflictResolution promptRefreshConflictResolution(
        ProfileService.ProfileRefreshConflictException conflict
    ) {
        Cli.warning("Local uncommitted changes detected in repository `" + conflict.repositoryName() + "`.");
        if (!conflict.diff().isBlank()) {
            Cli.info("Diff:");
            Cli.print(conflict.diff());
        }
        Cli.info("Choose how to proceed:");
        Cli.warning("1) Discard local changes and refresh from repository.");
        Cli.warning("2) Commit local changes and force push to remote.");
        Cli.warning("3) Do nothing and fix manually.");

        while (true) {
            Cli.info("Enter option [1-3]:");
            String option = readInputLine().trim();
            if ("1".equals(option)) {
                return ProfileService.RefreshConflictResolution.DISCARD_AND_REFRESH;
            }
            if ("2".equals(option)) {
                return ProfileService.RefreshConflictResolution.COMMIT_AND_FORCE_PUSH;
            }
            if ("3".equals(option)) {
                return ProfileService.RefreshConflictResolution.DO_NOTHING;
            }
            Cli.error("Invalid option. Please enter 1, 2, or 3.");
        }
    }

    private String readInputLine() {
        StringBuilder line = new StringBuilder();
        try {
            int next;
            while ((next = System.in.read()) != -1) {
                if (next == '\n') {
                    break;
                }
                if (next != '\r') {
                    line.append((char) next);
                }
            }
            return line.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read refresh conflict option from input.", e);
        }
    }

    private void applyRefreshConflictResolution(
        ProfileService.ProfileRefreshConflictException conflict,
        ProfileService.RefreshConflictResolution resolution
    ) {
        if (resolution == ProfileService.RefreshConflictResolution.DISCARD_AND_REFRESH) {
            Cli.info(
                "Discarding local changes in repository `"
                    + conflict.repositoryName()
                    + "` and retrying refresh. This may take a moment..."
            );
            profileService.resolveRefreshConflict(conflict, resolution);
            Cli.success("Local changes discarded in repository `" + conflict.repositoryName() + "`.");
            return;
        }
        if (resolution == ProfileService.RefreshConflictResolution.COMMIT_AND_FORCE_PUSH) {
            Cli.info(
                "Committing local changes and force-pushing repository `"
                    + conflict.repositoryName()
                    + "`. This may take a moment..."
            );
            profileService.resolveRefreshConflict(conflict, resolution);
            Cli.success("Local changes committed and force-pushed for repository `" + conflict.repositoryName() + "`.");
            return;
        }
        throw new IllegalStateException("Unsupported refresh conflict resolution: " + resolution);
    }

    private void applyMergedProfileRefreshConflictResolution(
        ProfileService.ProfileRefreshUserConfigConflictException conflict,
        ProfileService.RefreshConflictResolution resolution
    ) {
        if (resolution == ProfileService.RefreshConflictResolution.DISCARD_AND_REFRESH) {
            Cli.info("Discarding local changes in merged user config files and retrying refresh. This may take a moment...");
            profileService.resolveRefreshConflict(conflict, resolution);
            Cli.success("Local changes discarded in merged user config files for profile `" + conflict.profileName() + "`.");
            return;
        }
        throw new IllegalStateException("Unsupported merged-file refresh conflict resolution: " + resolution);
    }

    private record RefreshOutcome(String message, ProfileService.ProfileRefreshResult refreshResult) {
    }

    private static String skippedFileBasedRepositoriesMessage(long fileBasedCount) {
        String noun = fileBasedCount == 1 ? "repository" : "repositories";
        return "Skipped " + fileBasedCount + " file-based " + noun + ".";
    }

}
