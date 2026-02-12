package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.service.ProfileService;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.nio.file.Path;
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
            Cli.success("Created profile `" + profileName + "`.");
            return 0;
        } catch (RuntimeException e) {
            Cli.error(e.getMessage());
            return 1;
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
