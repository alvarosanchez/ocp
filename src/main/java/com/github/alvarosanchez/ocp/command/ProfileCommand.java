package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.model.ProfileEntry;
import com.github.alvarosanchez.ocp.service.ProfileService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Command group for profile-related operations.
 */
@Command(
    name = "profile",
    description = "Manage OpenCode configuration profiles.",
    mixinStandardHelpOptions = true,
    subcommands = {
        ProfileCommand.ListCommand.class,
        ProfileCommand.CreateCommand.class,
        ProfileCommand.UseCommand.class
    }
)
public class ProfileCommand implements Runnable {

    /**
     * Prints profile command usage when no subcommand is provided.
     */
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "list", description = "List discovered profiles.")
    static class ListCommand implements Callable<Integer> {

        private final ProfileService profileService;

        @Inject
        ListCommand(ProfileService profileService) {
            this.profileService = profileService;
        }

        @Override
        public Integer call() {
            try {
                var profiles = profileService.listProfiles();
                if (profiles.isEmpty()) {
                    System.out.println("No profiles available yet. Add a repository with `ocp repository add`.");
                    return 0;
                }
                for (ProfileEntry profile : profiles) {
                    System.out.println(profile.name());
                }
                return 0;
            } catch (ProfileService.DuplicateProfilesException e) {
                System.err.println("Error: duplicate profile names found: " + String.join(", ", e.duplicateProfileNames()));
                return 1;
            }
        }
    }

    @Command(name = "create", description = "Create a new profile scaffold in the current directory.")
    static class CreateCommand implements Runnable {

        @Parameters(index = "0", arity = "0..1", defaultValue = "default", description = "Profile name.")
        private String profileName;

        @Override
        public void run() {
            Path targetDirectory = Path.of(profileName);
            System.out.println("Profile scaffold placeholder created at " + targetDirectory.toAbsolutePath());
        }
    }

    @Command(name = "use", description = "Switch to a profile by name.")
    static class UseCommand implements Runnable {

        @Parameters(index = "0", description = "Profile name.")
        private String profileName;

        @Override
        public void run() {
            System.out.println("Profile switch placeholder for profile `" + profileName + "`.");
        }
    }
}
