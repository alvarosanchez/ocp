package com.github.alvarosanchez.ocp.command.interactive;

import java.util.ArrayList;
import java.util.List;

final class PromptState {
    final PromptAction action;
    final String title;
    final List<String> labels;
    final List<String> values;
    final List<List<String>> options;
    int currentField;
    String expectedConfirmation;

    private PromptState(PromptAction action, String title, List<String> labels, List<List<String>> options) {
        this.action = action;
        this.title = title;
        this.labels = List.copyOf(labels);
        if (options.size() != labels.size()) {
            throw new IllegalArgumentException("Prompt options size must match labels size.");
        }
        List<List<String>> normalizedOptions = new ArrayList<>();
        for (List<String> fieldOptions : options) {
            normalizedOptions.add(fieldOptions == null ? List.of() : List.copyOf(fieldOptions));
        }
        this.options = List.copyOf(normalizedOptions);
        this.values = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            List<String> fieldOptions = this.options.get(index);
            this.values.add(fieldOptions.isEmpty() ? "" : fieldOptions.getFirst());
        }
    }

    static PromptState single(PromptAction action, String title, String label) {
        return new PromptState(action, title, List.of(label), List.of(List.of()));
    }

    static PromptState multi(PromptAction action, String title, List<String> labels) {
        List<List<String>> options = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            options.add(List.of());
        }
        return new PromptState(action, title, labels, options);
    }

    static PromptState multiWithOptions(
        PromptAction action,
        String title,
        List<String> labels,
        List<List<String>> options
    ) {
        return new PromptState(action, title, labels, options);
    }

    String label() {
        return labels.get(currentField);
    }

    String currentValue() {
        return values.get(currentField);
    }

    boolean currentFieldHasOptions() {
        return !options.get(currentField).isEmpty();
    }

    List<String> currentFieldOptions() {
        return options.get(currentField);
    }

    int currentFieldSelectedOptionIndex() {
        if (!currentFieldHasOptions()) {
            return -1;
        }
        String current = currentValue();
        int index = options.get(currentField).indexOf(current);
        return index < 0 ? 0 : index;
    }

    void selectPreviousOption() {
        if (!currentFieldHasOptions()) {
            return;
        }
        List<String> fieldOptions = options.get(currentField);
        int currentIndex = currentFieldSelectedOptionIndex();
        int nextIndex = currentIndex <= 0 ? fieldOptions.size() - 1 : currentIndex - 1;
        values.set(currentField, fieldOptions.get(nextIndex));
    }

    void selectNextOption() {
        if (!currentFieldHasOptions()) {
            return;
        }
        List<String> fieldOptions = options.get(currentField);
        int currentIndex = currentFieldSelectedOptionIndex();
        int nextIndex = currentIndex >= fieldOptions.size() - 1 ? 0 : currentIndex + 1;
        values.set(currentField, fieldOptions.get(nextIndex));
    }

    void append(char value) {
        if (currentFieldHasOptions()) {
            return;
        }
        values.set(currentField, values.get(currentField) + value);
    }

    void deleteLast() {
        if (currentFieldHasOptions()) {
            values.set(currentField, options.get(currentField).getFirst());
            return;
        }
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
