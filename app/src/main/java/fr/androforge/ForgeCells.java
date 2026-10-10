package fr.androforge;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lossless editor cell contents; native code validates and serializes applied expressions. */
public final class ForgeCells {
    private static final Pattern RECIPROCAL = Pattern.compile("^1\\s*/\\s*(.+)$");
    private static final Pattern NEGATIVE_NUMBER = Pattern.compile("^-(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?[Vv%]?$");
    private static final Pattern CABLE = Pattern.compile("^_[A-Za-z0-9_]+$");
    public final String base, factor, offset;

    public ForgeCells(String base, String factor, String offset) {
        this.base = Objects.requireNonNull(base);
        this.factor = Objects.requireNonNull(factor);
        this.offset = Objects.requireNonNull(offset);
    }

    public String cell(int column) { return column == 0 ? base : column == 1 ? factor : offset; }
    public ForgeCells with(int column, String text) {
        if (column < 0 || column > 2) throw new IllegalArgumentException("Invalid expression column");
        return new ForgeCells(column == 0 ? text : base, column == 1 ? text : factor, column == 2 ? text : offset);
    }

    /** Builds only a private draft; never rewrites an imported expression without user input. */
    public String draftExpression() {
        boolean a = !base.trim().isEmpty(), b = !factor.trim().isEmpty(), c = !offset.trim().isEmpty();
        String result = a ? base : b ? factor : "";
        if (a && b) {
            Matcher reciprocal = RECIPROCAL.matcher(factor.trim());
            result += reciprocal.matches() ? " / " + reciprocal.group(1) : " * " + factor;
        }
        if (c) {
            if (result.isEmpty()) return offset;
            String trimmed = offset.trim();
            result += NEGATIVE_NUMBER.matcher(trimmed).matches() ? " - " + trimmed.substring(1) : " + " + offset;
        }
        return result;
    }

    public int characters() { return base.length() + factor.length() + offset.length(); }
    public boolean hasEmptyBaseRoles() {
        return base.trim().isEmpty() && (!factor.trim().isEmpty() || !offset.trim().isEmpty());
    }
    public static String cableName(String atom) {
        String trimmed = atom.trim();
        return CABLE.matcher(trimmed).matches() ? trimmed.toUpperCase(Locale.ROOT) : "";
    }
    /** Matches native token boundaries; quoted text and comments remain byte-for-byte intact. */
    public static String renameCableTokens(String value, String oldName, String newName) {
        StringBuilder changed = new StringBuilder(value.length());
        boolean quotedText = false;
        for (int i = 0; i < value.length();) {
            char character = value.charAt(i);
            if (character == '"') { quotedText = !quotedText; changed.append(character); i++; }
            else if (quotedText) { changed.append(character); i++; }
            else if (character == '#') { changed.append(value, i, value.length()); break; }
            else if (tokenCharacter(character)) {
                int end = i + 1;
                while (end < value.length() && tokenCharacter(value.charAt(end))) end++;
                String token = value.substring(i, end);
                changed.append(token.equalsIgnoreCase(oldName) ? newName : token); i = end;
            } else { changed.append(character); i++; }
        }
        return changed.toString();
    }
    private static boolean tokenCharacter(char value) {
        return value == '_' || value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value >= '0' && value <= '9';
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ForgeCells)) return false;
        ForgeCells cells = (ForgeCells) other;
        return base.equals(cells.base) && factor.equals(cells.factor) && offset.equals(cells.offset);
    }
    @Override public int hashCode() { return Objects.hash(base, factor, offset); }
}
