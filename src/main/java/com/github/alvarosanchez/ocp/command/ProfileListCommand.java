package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.model.Profile;
import com.github.alvarosanchez.ocp.service.ProfileService;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "list", description = "List discovered profiles.")
class ProfileListCommand implements Callable<Integer> {

    private final ProfileService profileService;

    @Inject
    ProfileListCommand(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * Prints all discovered profiles.
     *
     * @return command exit code
     */
    @Override
    public Integer call() {
        try {
            List<Profile> profiles = profileService.getAllProfiles();
            if (profiles.isEmpty()) {
                Cli.warning("No profiles available yet. Add a repository with `ocp repository add`.");
                return 0;
            }

            ProfileTableRenderer.printWithWarnings(profiles);
            return 0;
        } catch (ProfileService.DuplicateProfilesException e) {
            Cli.error("duplicate profile names found: " + String.join(", ", e.duplicateProfileNames()));
            return 1;
        }
    }
}
