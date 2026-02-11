package com.github.alvarosanchez.ocp.command;

import com.github.kusoroadeolu.clique.Clique;
import com.github.alvarosanchez.ocp.service.ProfileService.ProfileListResult;
import com.github.alvarosanchez.ocp.service.ProfileService.ProfileListRow;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.kusoroadeolu.clique.tables.Table;
import com.github.kusoroadeolu.clique.tables.TableType;
import java.nio.file.Path;
import java.util.List;
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
        ProfileCommand.UseCommand.class,
        ProfileCommand.RefreshCommand.class
    }
)
public class ProfileCommand implements Callable<Integer> {

    private static final String ACTIVE_MARKER = "✓";
    private static final String UPDATE_MARKER = "❄";

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
                Clique.enableCliqueColors(CommandLine.Help.Ansi.AUTO.enabled());
                ProfileListResult listResult = profileService.listProfilesTable();
                if (listResult.rows().isEmpty()) {
                    Clique.parser().print("[yellow]No profiles available yet. Add a repository with `ocp repository add`.[/]");
                    return 0;
                }

                String activeProfile = profileService.activeProfileName().orElse(null);
                printTable(listResult.rows(), activeProfile);
                if (listResult.hasUpdates()) {
                    Clique.parser()
                        .print(
                            "\n[*yellow]"
                                + UPDATE_MARKER
                                + "[/][yellow] Newer commits are available in remote repositories. Run `ocp profile refresh`.[/]"
                        );
                }
                if (!listResult.failedVersionChecks().isEmpty()) {
                    Clique.parser()
                        .print(
                            "[yellow]! Skipped remote update checks for repositories: "
                                + String.join(", ", listResult.failedVersionChecks())
                                + ".[/]"
                        );
                }
                return 0;
            } catch (ProfileService.DuplicateProfilesException e) {
                System.err.println("Error: duplicate profile names found: " + String.join(", ", e.duplicateProfileNames()));
                return 1;
            }
        }

        private void printTable(List<ProfileListRow> rows, String activeProfile) {
            Table table = Clique.table(TableType.ROUNDED_BOX_DRAW);
            table.addHeaders(
                "[cyan, bold]NAME[/]",
                "[cyan, bold]ACTIVE[/]",
                "[cyan, bold]REPOSITORY[/]",
                "[cyan, bold]VERSION[/]",
                "[cyan, bold]LAST UPDATED[/]",
                "[cyan, bold]MESSAGE[/]"
            );
            for (ProfileListRow row : rows) {
                table.addRows(
                    row.name(),
                    activeMarker(row.name(), activeProfile),
                    row.repository(),
                    renderedVersion(row),
                    row.lastUpdated(),
                    row.message()
                );
            }
            table.render();
        }

        private String renderedVersion(ProfileListRow row) {
            if (!row.updateAvailable()) {
                return row.version();
            }
            return row.version() + " [*yellow]" + UPDATE_MARKER + "[/]";
        }

        private String activeMarker(String profileName, String activeProfile) {
            if (activeProfile == null || !activeProfile.equals(profileName)) {
                return "";
            }
            return "[green]" + ACTIVE_MARKER + "[/]";
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

        @Parameters(index = "0", arity = "0..1", description = "Profile name.")
        private String profileName;

        /**
         * Refreshes the repository for the selected profile.
         *
         * @return command exit code
         */
        @Override
        public Integer call() {
            try {
                Clique.enableCliqueColors(CommandLine.Help.Ansi.AUTO.enabled());
                if (profileName == null || profileName.isBlank()) {
                    profileService.refreshAllProfiles();
                    Clique.parser().print("[green, bold]Refreshed all repositories.[/]");
                    return 0;
                }

                profileService.refreshProfile(profileName);
                Clique.parser().print("[green, bold]Refreshed profile `" + profileName + "`.[/]");
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
