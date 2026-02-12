package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.model.Profile;

import java.util.List;
import java.util.concurrent.Callable;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

/**
 * Command group for profile-related operations.
 */
@Command(
    name = "profile",
    description = "Manage OpenCode configuration profiles.",
    mixinStandardHelpOptions = true,
    subcommands = {
        ProfileListCommand.class,
        ProfileCreateCommand.class,
        ProfileUseCommand.class,
        ProfileRefreshCommand.class
    }
)
public class ProfileCommand implements Callable<Integer> {

    private final ProfileService profileService;

    @Inject
    ProfileCommand(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * Prints currently active profile when no subcommand is provided.
     */
    @Override
    public Integer call() {
        try {
            Profile activeProfile = profileService.getActiveProfile();
            List<Profile> profiles = List.of(activeProfile);
            ProfileTableRenderer.printWithWarnings(profiles);
            return 0;
        } catch (ProfileService.NoActiveProfileException e) {
            Cli.warning(e.getMessage());
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
