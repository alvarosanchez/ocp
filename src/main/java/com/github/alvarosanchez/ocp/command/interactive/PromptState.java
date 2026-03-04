package com.github.alvarosanchez.ocp.command.interactive;

import java.util.ArrayList;
import java.util.List;

final class PromptState {
    final PromptAction action;
    final String title;
    final List<String> labels;
    final List<String> values;
    int currentField;
    String expectedConfirmation;

    private PromptState(PromptAction action, String title, List<String> labels) {
        this.action = action;
        this.title = title;
        this.labels = List.copyOf(labels);
        this.values = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            this.values.add("");
        }
    }

    static PromptState single(PromptAction action, String title, String label) {
        return new PromptState(action, title, List.of(label));
    }

    static PromptState multi(PromptAction action, String title, List<String> labels) {
        return new PromptState(action, title, labels);
    }

    String label() {
        return labels.get(currentField);
    }

    String currentValue() {
        return values.get(currentField);
    }

    void append(char value) {
        values.set(currentField, values.get(currentField) + value);
    }

    void deleteLast() {
        String current = values.get(currentField);
        if (current.isEmpty()) {
            return;
        }
        values.set(currentField, current.substring(0, current.length() - 1));
    }

    boolean nextField() {
        if (currentField >= labels.size() - 1) {
            return false;
        }
        currentField++;
        return true;
    }
}
