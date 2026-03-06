package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.service.ProfileService;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "create", description = "Create a new profile scaffold in the current directory.")
class ProfileCreateCommand implements Callable<Integer> {

    private final ProfileService profileService;

    @Inject
    ProfileCreateCommand(ProfileService profileService) {
        this.profileService = profileService;
    }

    @CommandLine.Parameters(index = "0", arity = "0..1", defaultValue = "default", description = "Profile name.")
    private String profileName;

    @CommandLine.Option(
        names = {"--extends-from"},
        description = "Optional parent profile name to extend from."
    )
    private String parentProfileName;

    /**
     * Creates a profile in the current repository.
     *
     * @return command exit code
     */
    @Override
    public Integer call() {
        try {
            profileService.createProfileWithParent(profileName, parentProfileName);
            if (parentProfileName == null || parentProfileName.isBlank()) {
                Cli.success("Created profile `" + profileName + "`.");
            } else {
                Cli.success("Created profile `" + profileName + "` extending from `" + parentProfileName.trim() + "`.");
            }
            return 0;
        } catch (RuntimeException e) {
            Cli.error(e.getMessage());
            return 1;
        }
    }
}
