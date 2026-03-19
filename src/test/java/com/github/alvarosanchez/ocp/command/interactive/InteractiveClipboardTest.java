package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InteractiveClipboardTest {

    @Test
    void clipboardUnavailableMessageForLinuxMentionsWlCopyXclipAndXsel() {
        String message = InteractiveClipboard.clipboardUnavailableMessage("linux");

        assertTrue(message.contains("wl-copy"));
        assertTrue(message.contains("xclip"));
        assertTrue(message.contains("xsel"));
    }

    @Test
    void clipboardUnavailableMessageForUnknownOsMentionsLinuxTools() {
        String message = InteractiveClipboard.clipboardUnavailableMessage("plan9");

        assertTrue(message.contains("wl-copy"));
        assertTrue(message.contains("xclip"));
        assertTrue(message.contains("xsel"));
    }

    @Test
    void clipboardUnavailableMessageForMacMentionsPbcopy() {
        String message = InteractiveClipboard.clipboardUnavailableMessage("mac os x");

        assertTrue(message.contains("pbcopy"));
    }

    @Test
    void clipboardUnavailableMessageForWindowsMentionsClip() {
        String message = InteractiveClipboard.clipboardUnavailableMessage("windows 10");

        assertTrue(message.contains("clip"));
    }

}
