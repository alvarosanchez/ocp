package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.service.ProfileService;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.Scanner;
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
                refreshAllWithConflictResolution();
                Cli.success("Refreshed all repositories.");
                return 0;
            }

            refreshSingleWithConflictResolution(profileName);
            Cli.success("Refreshed profile `" + profileName + "`.");
            return 0;
        } catch (ProfileService.DuplicateProfilesException e) {
            Cli.error("duplicate profile names found: " + String.join(", ", e.duplicateProfileNames()));
            return 1;
        } catch (RuntimeException e) {
            Cli.error(e.getMessage());
            return 1;
        }
    }

    private void refreshSingleWithConflictResolution(String name) {
        while (true) {
            try {
                profileService.refreshProfile(name);
                return;
            } catch (ProfileService.ProfileRefreshConflictException conflict) {
                if (!handleRefreshConflict(conflict)) {
                    throw new IllegalStateException("Refresh cancelled. Local changes were left untouched.");
                }
            }
        }
    }

    private void refreshAllWithConflictResolution() {
        while (true) {
            try {
                profileService.refreshAllProfiles();
                return;
            } catch (ProfileService.ProfileRefreshConflictException conflict) {
                if (!handleRefreshConflict(conflict)) {
                    throw new IllegalStateException("Refresh cancelled. Local changes were left untouched.");
                }
            }
        }
    }

    private boolean handleRefreshConflict(ProfileService.ProfileRefreshConflictException conflict) {
        Cli.warning("Local uncommitted changes detected in repository `" + conflict.repositoryName() + "`.");
        if (!conflict.diff().isBlank()) {
            Cli.print(conflict.diff());
        }
        Cli.info("Choose how to proceed:");
        Cli.warning("1) Discard local changes and refresh from repository.");
        Cli.warning("2) Commit local changes and force push to remote.");
        Cli.warning("3) Do nothing and fix manually.");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            Cli.info("Enter option [1-3]:");
            String option = scanner.nextLine().trim();
            if ("1".equals(option)) {
                return profileService.resolveRefreshConflict(
                    conflict,
                    ProfileService.RefreshConflictResolution.DISCARD_AND_REFRESH
                );
            }
            if ("2".equals(option)) {
                return profileService.resolveRefreshConflict(
                    conflict,
                    ProfileService.RefreshConflictResolution.COMMIT_AND_FORCE_PUSH
                );
            }
            if ("3".equals(option)) {
                return profileService.resolveRefreshConflict(conflict, ProfileService.RefreshConflictResolution.DO_NOTHING);
            }
            Cli.error("Invalid option. Please enter 1, 2, or 3.");
        }
    }
}
