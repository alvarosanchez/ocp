package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PromptStateTest {

    @Test
    void singlePromptInitializesLabelAndEmptyValue() {
        PromptState prompt = PromptState.single(PromptAction.CREATE_PROFILE, "Create profile", "Profile name");

        assertEquals(PromptAction.CREATE_PROFILE, prompt.action);
        assertEquals("Create profile", prompt.title);
        assertEquals("Profile name", prompt.label());
        assertEquals("", prompt.currentValue());
        assertFalse(prompt.nextField());
    }

    @Test
    void multiPromptAppendsDeletesAndAdvancesFields() {
        PromptState prompt = PromptState.multi(PromptAction.ADD_REPOSITORY, "Add repository", List.of("URI", "Name"));

        prompt.append('h');
        prompt.append('t');
        prompt.append('t');
        prompt.append('p');
        assertEquals("http", prompt.currentValue());

        prompt.deleteLast();
        assertEquals("htt", prompt.currentValue());

        assertTrue(prompt.nextField());
        assertEquals("Name", prompt.label());
        assertEquals("", prompt.currentValue());

        prompt.append('r');
        prompt.append('1');
        assertEquals("r1", prompt.currentValue());
        assertFalse(prompt.nextField());

        assertEquals(List.of("htt", "r1"), prompt.values);
    }

    @Test
    void deleteLastOnEmptyValueKeepsStateUnchanged() {
        PromptState prompt = PromptState.single(PromptAction.CREATE_PROFILE, "Create profile", "Profile name");

        prompt.deleteLast();

        assertEquals("", prompt.currentValue());
        assertEquals(0, prompt.currentField);
    }
}
