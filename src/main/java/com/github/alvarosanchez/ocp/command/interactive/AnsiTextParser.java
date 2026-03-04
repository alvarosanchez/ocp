package com.github.alvarosanchez.ocp.command.interactive;

import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class AnsiTextParser {

    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[;?0-9]*[ -/]*[@-~]");

    Text parse(String ansi) {
        if (ansi == null || ansi.isEmpty()) {
            return Text.from(Line.from(Span.raw("")));
        }

        List<Line> lines = new ArrayList<>();
        List<Span> currentLine = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        Style currentStyle = Style.EMPTY;

        int index = 0;
        while (index < ansi.length()) {
            char character = ansi.charAt(index);
            if (character == '\u001B') {
                SgrParseResult sgrResult = consumeSgrSequence(ansi, index, run, currentLine, currentStyle);
                if (sgrResult != null) {
                    currentStyle = sgrResult.style();
                    index += sgrResult.consumed();
                    continue;
                }
            }

            if (character == '\r') {
                index++;
                continue;
            }
            if (character == '\n') {
                flushStyledSpan(run, currentStyle, currentLine);
                lines.add(Line.from(currentLine));
                currentLine = new ArrayList<>();
                index++;
                continue;
            }

            run.append(character);
            index++;
        }

        flushStyledSpan(run, currentStyle, currentLine);
        if (lines.isEmpty() || !currentLine.isEmpty()) {
            lines.add(Line.from(currentLine));
        }
        return Text.from(lines);
    }

    static String stripAnsi(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return ANSI_ESCAPE_PATTERN.matcher(input).replaceAll("");
    }

    private SgrParseResult consumeSgrSequence(
        String ansi,
        int startIndex,
        StringBuilder run,
        List<Span> currentLine,
        Style styleBeforeSequence
    ) {
        if (startIndex + 1 >= ansi.length() || ansi.charAt(startIndex + 1) != '[') {
            return null;
        }

        int mIndex = ansi.indexOf('m', startIndex + 2);
        if (mIndex < 0) {
            return null;
        }

        flushStyledSpan(run, styleBeforeSequence, currentLine);
        String codes = ansi.substring(startIndex + 2, mIndex);
        Style nextStyle = applySgrCodes(styleBeforeSequence, codes);
        return new SgrParseResult((mIndex - startIndex) + 1, nextStyle);
    }

    private void flushStyledSpan(StringBuilder run, Style style, List<Span> target) {
        if (run.isEmpty()) {
            return;
        }
        String value = run.toString();
        run.setLength(0);
        target.add(Span.styled(value, style == null ? Style.EMPTY : style));
    }

    private Style applySgrCodes(Style baseStyle, String codeBlock) {
        SgrState state = SgrState.from(baseStyle);
        if (codeBlock == null || codeBlock.isBlank()) {
            return SgrState.empty().toStyle();
        }

        String[] parts = codeBlock.split(";", -1);
        for (int index = 0; index < parts.length; index++) {
            int code = parseSgrCode(parts[index]);
            if (code < 0) {
                continue;
            }

            if (code == 0) {
                state = SgrState.empty();
                continue;
            }

            if (code == 1) {
                state.bold = true;
                continue;
            }
            if (code == 2) {
                state.dim = true;
                continue;
            }
            if (code == 3) {
                state.italic = true;
                continue;
            }
            if (code == 4) {
                state.underlined = true;
                continue;
            }
            if (code == 5) {
                state.slowBlink = true;
                continue;
            }
            if (code == 7) {
                state.reversed = true;
                continue;
            }
            if (code == 8) {
                state.hidden = true;
                continue;
            }
            if (code == 9) {
                state.crossedOut = true;
                continue;
            }
            if (code == 22) {
                state.bold = false;
                state.dim = false;
                continue;
            }
            if (code == 23) {
                state.italic = false;
                continue;
            }
            if (code == 24) {
                state.underlined = false;
                continue;
            }
            if (code == 25) {
                state.slowBlink = false;
                continue;
            }
            if (code == 27) {
                state.reversed = false;
                continue;
            }
            if (code == 28) {
                state.hidden = false;
                continue;
            }
            if (code == 29) {
                state.crossedOut = false;
                continue;
            }

            if (code == 39) {
                state.fg = null;
                continue;
            }
            if (code == 49) {
                state.bg = null;
                continue;
            }

            if (code >= 30 && code <= 37) {
                state.fg = Color.indexed(code - 30);
                continue;
            }
            if (code >= 90 && code <= 97) {
                state.fg = Color.indexed((code - 90) + 8);
                continue;
            }
            if (code >= 40 && code <= 47) {
                state.bg = Color.indexed(code - 40);
                continue;
            }
            if (code >= 100 && code <= 107) {
                state.bg = Color.indexed((code - 100) + 8);
                continue;
            }

            if ((code == 38 || code == 48) && index + 1 < parts.length) {
                int mode = parseSgrCode(parts[index + 1]);
                if (mode == 5 && index + 2 < parts.length) {
                    int colorIndex = parseSgrCode(parts[index + 2]);
                    if (colorIndex >= 0) {
                        if (code == 38) {
                            state.fg = Color.indexed(colorIndex);
                        } else {
                            state.bg = Color.indexed(colorIndex);
                        }
                    }
                    index += 2;
                    continue;
                }
                if (mode == 2 && index + 4 < parts.length) {
                    int red = parseSgrCode(parts[index + 2]);
                    int green = parseSgrCode(parts[index + 3]);
                    int blue = parseSgrCode(parts[index + 4]);
                    if (red >= 0 && green >= 0 && blue >= 0) {
                        Color rgb = Color.rgb(clampColor(red), clampColor(green), clampColor(blue));
                        if (code == 38) {
                            state.fg = rgb;
                        } else {
                            state.bg = rgb;
                        }
                    }
                    index += 4;
                }
            }
        }

        return state.toStyle();
    }

    private int parseSgrCode(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private int clampColor(int component) {
        if (component < 0) {
            return 0;
        }
        return Math.min(component, 255);
    }

    private record SgrParseResult(int consumed, Style style) {
    }

    private static final class SgrState {
        private Color fg;
        private Color bg;
        private boolean bold;
        private boolean dim;
        private boolean italic;
        private boolean underlined;
        private boolean slowBlink;
        private boolean reversed;
        private boolean hidden;
        private boolean crossedOut;

        private static SgrState empty() {
            return new SgrState();
        }

        private static SgrState from(Style style) {
            SgrState state = empty();
            if (style == null) {
                return state;
            }
            style.fg().ifPresent(color -> state.fg = color);
            style.bg().ifPresent(color -> state.bg = color);
            var modifiers = style.effectiveModifiers();
            state.bold = modifiers.contains(Modifier.BOLD);
            state.dim = modifiers.contains(Modifier.DIM);
            state.italic = modifiers.contains(Modifier.ITALIC);
            state.underlined = modifiers.contains(Modifier.UNDERLINED);
            state.slowBlink = modifiers.contains(Modifier.SLOW_BLINK);
            state.reversed = modifiers.contains(Modifier.REVERSED);
            state.hidden = modifiers.contains(Modifier.HIDDEN);
            state.crossedOut = modifiers.contains(Modifier.CROSSED_OUT);
            return state;
        }

        private Style toStyle() {
            Style style = Style.EMPTY;
            if (fg != null) {
                style = style.fg(fg);
            }
            if (bg != null) {
                style = style.bg(bg);
            }
            if (bold) {
                style = style.bold();
            }
            if (dim) {
                style = style.dim();
            }
            if (italic) {
                style = style.italic();
            }
            if (underlined) {
                style = style.underlined();
            }
            if (slowBlink) {
                style = style.slowBlink();
            }
            if (reversed) {
                style = style.reversed();
            }
            if (hidden) {
                style = style.hidden();
            }
            if (crossedOut) {
                style = style.crossedOut();
            }
            return style;
        }
    }
}
