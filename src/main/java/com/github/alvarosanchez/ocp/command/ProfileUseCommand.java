package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.service.ProfileService;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "use", description = "Switch to a profile by name.")
class ProfileUseCommand implements Callable<Integer> {

    private final ProfileService profileService;

    @Inject
    ProfileUseCommand(ProfileService profileService) {
        this.profileService = profileService;
    }

    @CommandLine.Parameters(index = "0", description = "Profile name.")
    private String profileName;

    /**
     * Switches the active profile.
     *
     * @return command exit code
     */
    @Override
    public Integer call() {
        try {
            profileService.useProfile(profileName);
            Cli.success("Switched to profile `" + profileName + "`.");
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
