package com.github.alvarosanchez.ocp.command.interactive;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;

import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.toolkit.Toolkit.richText;

final class ShortcutHintRenderer {

    private static final Style PREFIX_STYLE = Style.EMPTY.fg(Color.GRAY);
    private static final Style KEY_STYLE = Style.EMPTY.bold().fg(Color.CYAN);
    private static final Style DESCRIPTION_STYLE = Style.EMPTY.fg(Color.BRIGHT_WHITE);
    private static final Style SEPARATOR_STYLE = Style.EMPTY.fg(Color.GRAY);

    private ShortcutHintRenderer() {
    }

    static Element line(List<TreeShortcutHints.Shortcut> shortcuts) {
        return line(null, shortcuts);
    }

    static Element line(String prefix, List<TreeShortcutHints.Shortcut> shortcuts) {
        List<Span> spans = new ArrayList<>();
        if (prefix != null && !prefix.isBlank()) {
            spans.add(Span.styled(prefix + " ", PREFIX_STYLE));
        }

        for (int i = 0; i < shortcuts.size(); i++) {
            if (i > 0) {
                spans.add(Span.styled(" | ", SEPARATOR_STYLE));
            }
            TreeShortcutHints.Shortcut shortcut = shortcuts.get(i);
            spans.add(Span.styled(shortcut.key(), KEY_STYLE));
            if (shortcut.description() != null && !shortcut.description().isBlank()) {
                spans.add(Span.styled(" " + shortcut.description(), DESCRIPTION_STYLE));
            }
        }

        if (spans.isEmpty()) {
            spans.add(Span.styled("", DESCRIPTION_STYLE));
        }
        return richText(Text.from(Line.from(spans.toArray(Span[]::new))));
    }

    static String plainLine(List<TreeShortcutHints.Shortcut> shortcuts) {
        return plainLine(null, shortcuts);
    }

    static String plainLine(String prefix, List<TreeShortcutHints.Shortcut> shortcuts) {
        StringBuilder builder = new StringBuilder();
        if (prefix != null && !prefix.isBlank()) {
            builder.append(prefix).append(' ');
        }
        for (int i = 0; i < shortcuts.size(); i++) {
            if (i > 0) {
                builder.append(" | ");
            }
            TreeShortcutHints.Shortcut shortcut = shortcuts.get(i);
            builder.append(shortcut.key());
            if (shortcut.description() != null && !shortcut.description().isBlank()) {
                builder.append(' ').append(shortcut.description());
            }
        }
        return builder.toString();
    }
}
