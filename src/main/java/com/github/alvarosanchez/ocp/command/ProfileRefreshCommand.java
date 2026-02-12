package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.service.ProfileService;
import jakarta.inject.Inject;
import picocli.CommandLine;

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
                profileService.refreshAllProfiles();
                Cli.success("Refreshed all repositories.");
                return 0;
            }

            profileService.refreshProfile(profileName);
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
}
