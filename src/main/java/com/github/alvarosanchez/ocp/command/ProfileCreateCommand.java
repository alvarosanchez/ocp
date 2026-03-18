package com.github.alvarosanchez.ocp.command;

import com.github.alvarosanchez.ocp.service.ProfileService;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        description = "Optional parent profile name(s) to extend from, comma-separated (parent-1,parent-2,...)."
    )
    private String extendsFromProfilesCsv;

    /**
     * Creates a profile in the current repository.
     *
     * @return command exit code
     */
    @Override
    public Integer call() {
        try {
            List<String> parentProfileNames = parseParentProfileNames();
            profileService.createProfileWithParents(profileName, parentProfileNames);
            if (parentProfileNames.isEmpty()) {
                Cli.success("Created profile `" + profileName + "`." );
            } else {
                Cli.success(
                    "Created profile `" + profileName + "` extending from `" + String.join(", ", parentProfileNames) + "`."
                );
            }
            return 0;
        } catch (RuntimeException e) {
            Cli.error(e.getMessage());
            return 1;
        }
    }

    // Splits the `--extends-from` CSV option into a normalized ordered parent list.
    private List<String> parseParentProfileNames() {
        if (extendsFromProfilesCsv == null) {
            return List.of();
        }

        if (extendsFromProfilesCsv.isBlank()) {
            throw new IllegalArgumentException("Parent profile names cannot contain blank entries.");
        }

        String[] segments = extendsFromProfilesCsv.split(",", -1);
        List<String> normalizedParents = new ArrayList<>(segments.length);
        Set<String> seenParents = new LinkedHashSet<>();
        for (String segment : segments) {
            String trimmed = segment == null ? "" : segment.trim();
            if (trimmed.isBlank()) {
                throw new IllegalArgumentException("Parent profile names cannot contain blank entries.");
            }
            if (!seenParents.add(trimmed)) {
                throw new IllegalArgumentException(
                    "Parent profile `" + trimmed + "` is listed more than once."
                );
            }
            normalizedParents.add(trimmed);
        }
        return List.copyOf(normalizedParents);
    }
}
