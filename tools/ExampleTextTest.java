package fr.androforge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exercises the complete pinned corpus display without rewriting any manifest field. */
public final class ExampleTextTest {
    private static int checks;
    private static final Pattern FRENCH = Pattern.compile(
            "(?iu)[éèêëàâîïôùûüç]|\\b(?:exemple|fichiers|manuel|affectation|contrôleurs|préservé|conservé|extrait|fragment documentaire|patch officiel)\\b");
    private static final Pattern TOKENS = Pattern.compile(
            "_[A-Z_]+|\\b(?:S10|P1\\.1|input2|output1|output|rate|LFO|B|L)\\b");

    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    private static String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "Pass the generated metadata fixture path");
        check("lfo — example 12".equals(ExampleText.title("lfo — exemple 12")), "Circuit identifier and example number changed");
        check("Assignment explained, p. 134 — 7".equals(ExampleText.title("Affectation expliquée p. 134 — 7")), "Assignment page/ordinal changed");
        check("delay — files — 2".equals(ExampleText.title("delay — fichiers — 2")), "File section/ordinal not translated");
        check("P2B8 and P10 controllers — 4".equals(ExampleText.title("Contrôleurs P2B8 et P10 — 4")), "Module identifiers changed");
        check("custom_name_exemple".equals(ExampleText.title("custom_name_exemple")), "An identifier was rewritten");
        check("Unknown category".equals(ExampleText.category("Unknown category")), "Unknown category must stay available");
        check("Unknown note\r\n\r\n".equals(ExampleText.note("Unknown note\r\n\r\n")), "Unknown notes/line endings must be preserved");
        check(ExampleText.title(null) == null && ExampleText.category(null) == null && ExampleText.note(null) == null, "Missing metadata should remain missing");
        Set<String> categories = Set.of("Official patches", "Moto Kit", "Manual — circuits", "Manual — supplementary examples", "Manual — introduction", "Manual — parameters");
        List<String> rows = Files.readAllLines(Path.of(args[0]), StandardCharsets.US_ASCII);
        check(rows.size() == 630, "The fixture must cover all 630 pinned examples");
        for (String row : rows) {
            String[] columns = row.split("\t", -1);
            check(columns.length == 4, "Invalid fixture row");
            String id = decode(columns[0]);
            String category = decode(columns[1]), title = decode(columns[2]), note = decode(columns[3]);
            String displayedCategory = ExampleText.category(category);
            String displayedTitle = ExampleText.title(title);
            String displayedNote = ExampleText.note(note);
            check(categories.contains(displayedCategory), "Untranslated category: " + id);
            check(!FRENCH.matcher(displayedTitle).find(), "Untranslated title: " + id);
            check(!FRENCH.matcher(displayedNote).find(), "Untranslated note: " + id);
            Matcher tokens = TOKENS.matcher(note);
            while (tokens.find()) check(displayedNote.contains(tokens.group()), "Technical token changed: " + id);
            Matcher numbers = Pattern.compile("[0-9]+").matcher(title);
            while (numbers.find()) check(displayedTitle.contains(numbers.group()), "Page/example number changed: " + id);
        }
        System.out.println("Example display: " + checks + " checks passed (all 630 entries, English labels, technical tokens preserved)");
    }
}
