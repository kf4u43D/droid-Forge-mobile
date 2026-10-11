package fr.androforge;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** English display labels for our metadata; official manifest and patch text stay unchanged. */
public final class ExampleText {
    private static final Map<String, String> CATEGORIES = new HashMap<>();
    private static final Map<String, String> HEADINGS = new HashMap<>();
    private static final Map<String, String> NOTES = new HashMap<>();
    private static final Pattern EXAMPLE = Pattern.compile("^(.*) — exemple ([0-9]+)$");
    private static final Pattern ASSIGNMENT = Pattern.compile("^Affectation expliquée p\\. ([0-9]+) — ([0-9]+)$");
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");

    static {
        CATEGORIES.put("Patches officiels", "Official patches");
        CATEGORIES.put("Manuel — circuits", "Manual — circuits");
        CATEGORIES.put("Manuel — compléments", "Manual — supplementary examples");
        CATEGORIES.put("Manuel — introduction", "Manual — introduction");
        CATEGORIES.put("Manuel — paramètres", "Manual — parameters");

        HEADINGS.put("Prise en main", "Getting started");
        HEADINGS.put("Sélection et fonctions multiples", "Selection and multiple functions");
        HEADINGS.put("Presets et tempo", "Presets and tempo");
        HEADINGS.put("Syntaxe des patches", "Patch syntax");
        HEADINGS.put("Contrôleurs P2B8 et P10", "P2B8 and P10 controllers");
        HEADINGS.put("Contrôleur S10", "S10 controller");
        HEADINGS.put("Faders M4", "M4 faders");
        HEADINGS.put("Affichage DB8E", "DB8E display");
        HEADINGS.put("MIDI et X7", "MIDI and X7");
        HEADINGS.put("États et ordre des circuits", "Circuit states and order");
        HEADINGS.put("delay — fichiers", "delay — files");
        HEADINGS.put("recorder — fichiers", "recorder — files");

        NOTES.put("Patch officiel ; certains sont anciens. Les contrôleurs déclarés doivent être présents dans cet ordre.",
                "Official patch; some are older versions. The declared controllers must be present in this order.");
        NOTES.put("INI lisible destiné à l’étude : trop grand pour chargement direct sur le master ; voir le README Moto Kit officiel.",
                "Readable INI for study: too large to load directly on the MASTER; see the official Moto Kit README.");
        NOTES.put("Extrait du manuel : contrôleurs et câbles extérieurs à adapter au contexte.",
                "Manual excerpt: adapt external controllers and cables to the context.");
        NOTES.put("Fragment du manuel : lignes ou contexte omis volontairement.",
                "Manual fragment: lines or context intentionally omitted.");
        NOTES.put("Extrait à intégrer dans un patch et une configuration matérielle adaptés ; les contrôleurs ou câbles extérieurs peuvent être absents.",
                "Excerpt to integrate into a suitable patch and hardware configuration; external controllers or cables may be missing.");
        NOTES.put("Fragment documentaire : contexte ou lignes volontairement omis ; conserver les points de suspension et les tableaux explicatifs.",
                "Documentation fragment: context or lines intentionally omitted; retain the ellipses and explanatory tables.");
        NOTES.put("Affectation isolée dans la prose du manuel, conservée sans déclaration de circuit inventée. Ce fragment documentaire n’est pas un programme autonome.",
                "Isolated assignment in the manual prose, preserved without inventing a circuit declaration. This documentation fragment is not a standalone program.");
        NOTES.put("Contre-exemple volontaire du manuel : sorties led affectées aux registres B plutôt que L.",
                "Intentional counterexample from the manual: led outputs assigned to B registers rather than L.");
        NOTES.put("Illustration de la représentation interne des textes, pas un patch avec table de chaînes.",
                "Illustration of the internal text representation, rather than a patch with a string table.");
        NOTES.put("La déclaration S10 et les références P1.1 sont celles du manuel, conservées sans ajout de contrôleur.",
                "The S10 declaration and P1.1 references are those in the manual, preserved without adding a controller.");
        NOTES.put("Le câble _PTICH est orthographié ainsi dans le manuel ; il diffère de _PITCH et reste inchangé.",
                "The cable _PTICH is spelt this way in the manual; it differs from _PITCH and remains unchanged.");
        NOTES.put("Le paramètre output du LFO est celui du manuel, conservé sans correction.",
                "The LFO output parameter is the one in the manual, preserved without correction.");
        NOTES.put("Le paramètre output1 du switch est celui du manuel, conservé sans correction.",
                "The switch output1 parameter is the one in the manual, preserved without correction.");
        NOTES.put("Le paramètre rate du LFO est celui du manuel, conservé sans correction.",
                "The LFO rate parameter is the one in the manual, preserved without correction.");
        NOTES.put("Les deux paramètres input2 répétés sont ceux du manuel, conservés sans correction.",
                "The two repeated input2 parameters are those in the manual, preserved without correction.");
    }

    private ExampleText() {}

    public static String category(String value) {
        return value == null ? null : CATEGORIES.getOrDefault(value, value);
    }

    public static String title(String value) {
        if (value == null) return null;
        Matcher assignment = ASSIGNMENT.matcher(value);
        if (assignment.matches())
            return "Assignment explained, p. " + assignment.group(1) + " — " + assignment.group(2);
        Matcher example = EXAMPLE.matcher(value);
        if (example.matches()) return heading(example.group(1)) + " — example " + example.group(2);
        return heading(value);
    }

    private static String heading(String value) {
        String exact = HEADINGS.get(value);
        if (exact != null) return exact;
        int separator = value.lastIndexOf(" — ");
        if (separator >= 0 && NUMBER.matcher(value.substring(separator + 3)).matches()) {
            String prefix = HEADINGS.get(value.substring(0, separator));
            if (prefix != null) return prefix + value.substring(separator);
        }
        return value;
    }

    public static String note(String value) {
        if (value == null) return null;
        String[] lines = value.split("\n", -1);
        StringBuilder display = new StringBuilder(value.length());
        for (int index = 0; index < lines.length; index++) {
            if (index != 0) display.append('\n');
            String line = lines[index];
            boolean cr = line.endsWith("\r");
            String phrase = cr ? line.substring(0, line.length() - 1) : line;
            display.append(NOTES.getOrDefault(phrase, phrase));
            if (cr) display.append('\r');
        }
        return display.toString();
    }
}
