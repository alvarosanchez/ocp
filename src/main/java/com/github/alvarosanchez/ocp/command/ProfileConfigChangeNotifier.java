package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.service.ProfileService;

final class ProfileConfigChangeNotifier {

    private ProfileConfigChangeNotifier() {
    }

    static void notifyUserConfigChanges(ProfileService.ProfileSwitchResult switchResult) {
        if (switchResult.changedFiles()) {
            Cli.info("Updated user configuration files in `" + switchResult.targetDirectory() + "`.");
        } else {
            Cli.info("Processed user configuration files in `" + switchResult.targetDirectory() + "`.");
        }
        if (switchResult.hasBackups()) {
            String fileLabel = switchResult.backedUpFiles() == 1 ? "file" : "files";
            Cli.warning(
                "Backed up "
                    + switchResult.backedUpFiles()
                    + " existing "
                    + fileLabel
                    + " to `"
                    + switchResult.backupDirectory()
                    + "`."
            );
        }
    }

    static void notifyUserConfigChanges(ProfileService.ProfileRefreshResult refreshResult) {
        refreshResult.userConfigChanges().ifPresent(ProfileConfigChangeNotifier::notifyUserConfigChanges);
    }
}
