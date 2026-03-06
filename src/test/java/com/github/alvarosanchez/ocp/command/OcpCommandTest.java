package com.github.alvarosanchez.ocp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.command.interactive.InteractiveApp;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import io.micronaut.configuration.picocli.PicocliRunner;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OcpCommandTest {

    @AfterEach
    void resetStartupNotice() {
        Cli.consumeStartupNotice();
    }

    @Test
    void helpSubcommandPrintsUsage() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            int exitCode = PicocliRunner.execute(OcpCommand.class, "help");
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        assertTrue(output.toString().contains("Usage: ocp"));
    }

    @Test
    void versionOptionPrintsProjectVersion() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            int exitCode = PicocliRunner.execute(OcpCommand.class, "--version");
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        String expectedVersion = readExpectedVersion();
        assertTrue(output.toString().contains(expectedVersion));
    }

    @Test
    void noSubcommandFallsBackToUsageInNonInteractiveEnvironment() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            int exitCode = PicocliRunner.execute(OcpCommand.class);
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        assertTrue(output.toString().contains("Usage: ocp"));
    }

    @Test
    void startupNoticePrintsImmediatelyForNonInteractiveFlow() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            Cli.init();
            OcpCommand.presentStartupVersionNotice(new String[] {"profile", "list"}, "Update available", true);
        } finally {
            System.setOut(originalOut);
        }

        assertTrue(output.toString().contains("Update available"));
        assertNull(Cli.consumeStartupNotice());
    }

    @Test
    void startupNoticeIsDeferredForInteractiveRootMode() {
        Cli.init();

        OcpCommand.presentStartupVersionNotice(new String[0], "Update available", true);

        assertEquals("Update available", Cli.consumeStartupNotice());
        assertNull(Cli.consumeStartupNotice());
    }

    @Test
    void deferredStartupNoticeIsConsumedByInteractiveApp() throws Exception {
        Cli.init();
        Cli.setStartupNotice("Update available");

        try (ApplicationContext context = ApplicationContext.run()) {
            InteractiveApp app = new InteractiveApp(
                context.getBean(ProfileService.class),
                context.getBean(RepositoryService.class),
                context.getBean(ObjectMapper.class)
            );

            Field startupNoticeField = InteractiveApp.class.getDeclaredField("startupUpdateNotice");
            startupNoticeField.setAccessible(true);
            assertEquals("Update available", startupNoticeField.get(app));

            Field statusField = InteractiveApp.class.getDeclaredField("status");
            statusField.setAccessible(true);
            assertEquals("Ready. Select a node in the hierarchy.", statusField.get(app));
            assertNull(Cli.consumeStartupNotice());
        }
    }

    @Test
    void mainMethodIsPublicStatic() throws Exception {
        Method method = OcpCommand.class.getDeclaredMethod("main", String[].class);

        assertTrue(java.lang.reflect.Modifier.isPublic(method.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
    }

    @Test
    void interactiveDeferralOnlyAppliesToRootInvocation() {
        assertTrue(OcpCommand.shouldDeferVersionNoticeToInteractiveUi(new String[0], true));
        assertFalse(OcpCommand.shouldDeferVersionNoticeToInteractiveUi(new String[] {"help"}, true));
        assertFalse(OcpCommand.shouldDeferVersionNoticeToInteractiveUi(new String[0], false));
    }

    private static String readExpectedVersion() {
        try (var inputStream = OcpCommandTest.class.getResourceAsStream("/META-INF/ocp/version.txt")) {
            assertNotNull(inputStream);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read version resource", e);
        }
    }
}
