package com.github.alvarosanchez.ocp;

import io.micronaut.configuration.picocli.MicronautFactory;
import io.micronaut.context.ApplicationContext;
import picocli.CommandLine;

public final class Application {

    private Application() {
    }

    public static void main(String[] args) {
        try (ApplicationContext context = ApplicationContext.builder().start()) {
            int exitCode = new CommandLine(context.getBean(OcpCommand.class), new MicronautFactory(context))
                .setUsageHelpAutoWidth(true)
                .execute(args);
            System.exit(exitCode);
        }
    }
}
