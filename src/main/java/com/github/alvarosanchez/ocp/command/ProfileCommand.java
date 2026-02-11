package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.model.RepositoryConfigFile.ProfileEntry;
import com.github.alvarosanchez.ocp.service.ProfileService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import jakarta.inject.Inject;
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
        ProfileCommand.UseCommand.class,
        ProfileCommand.RefreshCommand.class
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
            var activeProfile = profileService.activeProfileName();
            if (activeProfile.isPresent()) {
                System.out.println(activeProfile.get());
            } else {
                System.out.println("No active profile selected yet.");
            }
            return 0;
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    @Command(name = "list", description = "List discovered profiles.")
    static class ListCommand implements Callable<Integer> {

        private final ProfileService profileService;

        @Inject
        ListCommand(ProfileService profileService) {
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
    static class CreateCommand implements Callable<Integer> {

        private final ProfileService profileService;

        @Inject
        CreateCommand(ProfileService profileService) {
            this.profileService = profileService;
        }

        @Parameters(index = "0", arity = "0..1", defaultValue = "default", description = "Profile name.")
        private String profileName;

        /**
         * Creates a profile in the current repository.
         *
         * @return command exit code
         */
        @Override
        public Integer call() {
            try {
                Path repositoryPath = workingDirectory();
                profileService.createProfile(repositoryPath, profileName);
                System.out.println("Created profile `" + profileName + "`.");
                return 0;
            } catch (RuntimeException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "use", description = "Switch to a profile by name.")
    static class UseCommand implements Callable<Integer> {

        private final ProfileService profileService;

        @Inject
        UseCommand(ProfileService profileService) {
            this.profileService = profileService;
        }

        @Parameters(index = "0", description = "Profile name.")
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
                System.out.println("Switched to profile `" + profileName + "`.");
                return 0;
            } catch (ProfileService.DuplicateProfilesException e) {
                System.err.println("Error: duplicate profile names found: " + String.join(", ", e.duplicateProfileNames()));
                return 1;
            } catch (RuntimeException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "refresh", description = "Pull latest changes for profile repository.")
    static class RefreshCommand implements Callable<Integer> {

        private final ProfileService profileService;

        @Inject
        RefreshCommand(ProfileService profileService) {
            this.profileService = profileService;
        }

        @Parameters(index = "0", description = "Profile name.")
        private String profileName;

        /**
         * Refreshes the repository for the selected profile.
         *
         * @return command exit code
         */
        @Override
        public Integer call() {
            try {
                profileService.refreshProfile(profileName);
                System.out.println("Refreshed profile `" + profileName + "`.");
                return 0;
            } catch (ProfileService.DuplicateProfilesException e) {
                System.err.println("Error: duplicate profile names found: " + String.join(", ", e.duplicateProfileNames()));
                return 1;
            } catch (RuntimeException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    private static Path workingDirectory() {
        String configuredPath = System.getProperty("ocp.working.dir");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }
}
