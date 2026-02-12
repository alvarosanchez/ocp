package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.kusoroadeolu.clique.Clique;
import com.github.kusoroadeolu.clique.tables.Table;
import com.github.kusoroadeolu.clique.tables.TableType;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

import static com.github.alvarosanchez.ocp.command.Cli.TABLE_CFG;
import static com.github.alvarosanchez.ocp.command.Cli.th;
import static com.github.alvarosanchez.ocp.command.ProfileCommand.UPDATE_MARKER;

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
            ProfileService.ProfileListResult listResult = profileService.listProfilesTable();
            if (listResult.rows().isEmpty()) {
                Cli.warning("No profiles available yet. Add a repository with `ocp repository add`.");
                return 0;
            }

            String activeProfile = profileService.activeProfileName().orElse(null);
            printTable(listResult.rows(), activeProfile);
            if (listResult.hasUpdates()) {
                Cli.warning(UPDATE_MARKER + " Newer commits are available in remote repositories. Run `ocp profile refresh`.");
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
        }
    }

    private void printTable(List<ProfileService.ProfileListRow> rows, String activeProfile) {
        Table table = Clique.table(TableType.ROUNDED_BOX_DRAW, TABLE_CFG);
        table.addHeaders(
                th("NAME"),
                th("ACTIVE"),
                th("REPOSITORY"),
                th("VERSION"),
                th("LAST UPDATED"),
                th("MESSAGE")
        );
        for (ProfileService.ProfileListRow row : rows) {
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

    private String renderedVersion(ProfileService.ProfileListRow row) {
        if (!row.updateAvailable()) {
            return row.version();
        }
        return row.version() + " [*tokyo_yellow]" + UPDATE_MARKER + "[/]";
    }

    private String activeMarker(String profileName, String activeProfile) {
        if (activeProfile == null || !activeProfile.equals(profileName)) {
            return "";
        }
        return "[*tokyo_green]" + ProfileCommand.ACTIVE_MARKER + "[/]";
    }
}
