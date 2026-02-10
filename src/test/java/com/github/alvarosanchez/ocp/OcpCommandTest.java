package com.github.alvarosanchez.ocp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class OcpCommandTest {

    @Test
    void helpSubcommandPrintsUsage() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            int exitCode = new CommandLine(new OcpCommand()).execute("help");
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        assertTrue(output.toString().contains("Usage: ocp"));
    }
}
