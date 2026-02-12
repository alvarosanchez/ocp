package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.model.Profile;
import com.github.kusoroadeolu.clique.Clique;
import com.github.kusoroadeolu.clique.tables.Table;
import com.github.kusoroadeolu.clique.tables.TableType;

import java.util.List;

import static com.github.alvarosanchez.ocp.command.Cli.TABLE_CFG;
import static com.github.alvarosanchez.ocp.command.Cli.th;

final class ProfileTableRenderer {

    static final String ACTIVE_MARKER = "✓";
    private static final String UPDATE_MARKER = "❄";

    private ProfileTableRenderer() {
    }

    static void print(List<Profile> profiles) {
        Table table = Clique.table(TableType.ROUNDED_BOX_DRAW, TABLE_CFG);
        table.addHeaders(
            th("NAME"),
            th("ACTIVE"),
            th("REPOSITORY"),
            th("VERSION"),
            th("LAST UPDATED"),
            th("MESSAGE")
        );
        for (Profile profile : profiles) {
            table.addRows(
                profile.name(),
                activeMarker(profile),
                profile.repository(),
                renderedVersion(profile),
                profile.lastUpdated(),
                profile.message()
            );
        }
        table.render();
    }

    static void printWithWarnings(List<Profile> profiles) {
        print(profiles);

        if (hasUpdates(profiles)) {
            Cli.warning(UPDATE_MARKER + " Newer commits are available in remote repositories. Run `ocp profile refresh`.");
        }
        List<String> failedVersionChecks = failedVersionChecks(profiles);
        if (!failedVersionChecks.isEmpty()) {
            Cli.warning(
                "! Skipped remote update checks for repositories: "
                    + String.join(", ", failedVersionChecks)
                    + "."
            );
        }
    }

    static boolean hasUpdates(List<Profile> profiles) {
        return profiles.stream().anyMatch(Profile::updateAvailable);
    }

    static List<String> failedVersionChecks(List<Profile> profiles) {
        return profiles
            .stream()
            .filter(Profile::versionCheckFailed)
            .map(Profile::repositoryName)
            .distinct()
            .sorted()
            .toList();
    }

    private static String activeMarker(Profile profile) {
        if (!profile.active()) {
            return "";
        }
        return "[*tokyo_green]" + ACTIVE_MARKER + "[/]";
    }

    private static String renderedVersion(Profile profile) {
        if (!profile.updateAvailable()) {
            return profile.version();
        }
        return profile.version() + " [*tokyo_yellow]" + UPDATE_MARKER + "[/]";
    }
}
