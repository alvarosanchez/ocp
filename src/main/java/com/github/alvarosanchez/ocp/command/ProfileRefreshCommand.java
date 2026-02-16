package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.service.ProfileService;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.io.IOException;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "refresh", description = "Pull latest changes for profile repository.")
class ProfileRefreshCommand implements Callable<Integer> {

    private final ProfileService profileService;

    @Inject
    ProfileRefreshCommand(ProfileService profileService) {
        this.profileService = profileService;
    }

    @CommandLine.Parameters(index = "0", arity = "0..1", description = "Profile name.")
    private String profileName;

    /**
     * Refreshes the repository for the selected profile.
     *
     * @return command exit code
     */
    @Override
    public Integer call() {
        try {
            if (profileName == null || profileName.isBlank()) {
                RefreshOutcome refreshOutcome = refreshAllWithConflictResolution();
                ProfileConfigChangeNotifier.notifyUserConfigChanges(refreshOutcome.refreshResult());
                Cli.success(refreshOutcome.message());
                return 0;
            }

            RefreshOutcome refreshOutcome = refreshSingleWithConflictResolution(profileName);
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
        ProfileService.RefreshConflictResolution appliedResolution = null;
        while (true) {
            try {
                ProfileService.ProfileRefreshResult refreshResult = profileService.refreshProfileWithDetails(name);
                if (appliedResolution == ProfileService.RefreshConflictResolution.DISCARD_AND_REFRESH) {
                    return new RefreshOutcome(
                        "Discarded local changes and refreshed profile `" + name + "`.",
                        refreshResult
                    );
                }
                if (appliedResolution == ProfileService.RefreshConflictResolution.COMMIT_AND_FORCE_PUSH) {
                    return new RefreshOutcome(
                        "Committed local changes, force-pushed, and refreshed profile `" + name + "`.",
                        refreshResult
                    );
                }
                return new RefreshOutcome("Refreshed profile `" + name + "`.", refreshResult);
            } catch (ProfileService.ProfileRefreshConflictException conflict) {
                ProfileService.RefreshConflictResolution resolution = promptRefreshConflictResolution(conflict);
                if (resolution == ProfileService.RefreshConflictResolution.DO_NOTHING) {
                    throw new IllegalStateException("Refresh cancelled. Local changes were left untouched.");
                }
                applyRefreshConflictResolution(conflict, resolution);
                appliedResolution = resolution;
            }
        }
    }

    private RefreshOutcome refreshAllWithConflictResolution() {
        boolean discardedLocalChanges = false;
        boolean forcePushedLocalChanges = false;
        while (true) {
            try {
                ProfileService.ProfileRefreshResult refreshResult = profileService.refreshAllProfilesWithDetails();
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
            }
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

    private record RefreshOutcome(String message, ProfileService.ProfileRefreshResult refreshResult) {
    }
}
