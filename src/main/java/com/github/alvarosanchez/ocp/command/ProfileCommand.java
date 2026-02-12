package com.github.alvarosanchez.ocp.command;

import com.github.kusoroadeolu.clique.Clique;
import com.github.alvarosanchez.ocp.service.ProfileService.ProfileListResult;
import com.github.alvarosanchez.ocp.service.ProfileService.ProfileListRow;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.kusoroadeolu.clique.boxes.Box;
import com.github.kusoroadeolu.clique.boxes.BoxType;
import com.github.kusoroadeolu.clique.config.BoxConfiguration;
import com.github.kusoroadeolu.clique.config.TextAlign;

import java.util.Optional;
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

    static final String ACTIVE_MARKER = "✓";
    static final String UPDATE_MARKER = "❄";

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
            Optional<String> activeProfile = profileService.activeProfileName();
            if (activeProfile.isEmpty()) {
                Cli.warning("No active profile selected yet.");
                return 0;
            }

            ProfileListResult listResult = profileService.listProfilesTable();
            Optional<ProfileListRow> activeRow = listResult
                .rows()
                .stream()
                .filter(row -> row.name().equals(activeProfile.get()))
                .findFirst();
            if (activeRow.isEmpty()) {
                Cli.error(
                    "Active profile `"
                        + activeProfile.get()
                        + "` is not available in configured repositories."
                );
                return 1;
            }

            printActiveProfileBox(activeRow.get());
            if (activeRow.get().updateAvailable()) {
                Cli.warning(
                    UPDATE_MARKER + " Newer commits are available in remote repositories. Run `ocp profile refresh`."
                );
            }
            if (!listResult.failedVersionChecks().isEmpty()) {
                Cli.warning(
                    "! Skipped remote update checks for repositories: "
                        + String.join(", ", listResult.failedVersionChecks())
                        + "."
                );
            }
            return 0;
        } catch (ProfileService.DuplicateProfilesException e) {
            Cli.error("duplicate profile names found: " + String.join(", ", e.duplicateProfileNames()));
            return 1;
        } catch (RuntimeException e) {
            Cli.error(e.getMessage());
            return 1;
        }
    }

    private void printActiveProfileBox(ProfileListRow row) {
        BoxConfiguration boxConfiguration = BoxConfiguration
            .immutableBuilder()
            .autoSize()
            .textAlign(TextAlign.CENTER_LEFT)
            .build();
        Box box = Clique.box(BoxType.ROUNDED, boxConfiguration);
        box
            .content(
                "[cyan, bold]NAME:[/] "
                    + row.name()
                    + "\n"
                    + "[cyan, bold]REPOSITORY:[/] "
                    + row.repository()
                    + "\n"
                    + "[cyan, bold]VERSION:[/] "
                    + renderedVersion(row)
                    + "\n"
                    + "[cyan, bold]LAST UPDATED:[/] "
                    + row.lastUpdated()
                    + "\n"
                    + "[cyan, bold]MESSAGE:[/] "
                    + row.message()
            )
            .render();
    }

    private String renderedVersion(ProfileListRow row) {
        if (!row.updateAvailable()) {
            return row.version();
        }
        return row.version() + " [*yellow]" + UPDATE_MARKER + "[/]";
    }

}
