package com.github.alvarosanchez.ocp;

import java.nio.file.Path;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

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

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "list", description = "List discovered profiles.")
    static class ListCommand implements Runnable {

        @Override
        public void run() {
            System.out.println("No profiles available yet. Add a repository with `ocp repository add`.");
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
