package fr.androforge;

/** Exercises draft preservation and the PC token grammar independently of Android. */
public final class ForgeCellsTest {
    private static int checks;
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static void equal(String expected, String actual, String description) {
        check(expected.equals(actual), description + "\nExpected: " + expected + "\nActual: " + actual);
    }
    private static void rename(String input, String expected) {
        equal(expected, ForgeCells.renameCableTokens(input, "_OLD", "_NEW"), "Cable token boundaries must preserve all unrelated text");
    }

    public static void main(String[] arguments) {
        // The same expression can come from different empty-cell roles. Draft
        // persistence and native connect must retain the explicit triplet.
        ForgeCells base = new ForgeCells("2", "", "3");
        ForgeCells factor = new ForgeCells("", "2", "3");
        equal(base.draftExpression(), factor.draftExpression(), "Ambiguous raw draft fixture must exercise identical expressions");
        check(!base.equals(factor), "Draft equality must retain the distinct base/factor roles");
        equal("_WIRE * 2 + 3", factor.with(0, "_WIRE").draftExpression(), "Connecting the empty base must preserve the existing factor");
        equal("_WIRE + 3", factor.with(1, "_WIRE").draftExpression(), "Connecting the factor must preserve the empty base");
        equal("2", factor.factor, "Changing a cell must leave the retained draft intact");
        equal("", factor.base, "Changing a cell must not fill another empty cell");
        equal("I1 / 2 - 3V", new ForgeCells("I1", "1 / 2", " -3V ").draftExpression(), "Fractions and signed offsets must remain faithful");
        equal("1 / 2", new ForgeCells("", "1 / 2", "").draftExpression(), "A factor-only reciprocal must not lose its denominator");
        equal("-1 * _RIGHT + _LEFT", new ForgeCells("-1", "_RIGHT", "_LEFT").draftExpression(), "PC subtraction cells must retain both signals");
        equal("", new ForgeCells("", "", "").draftExpression(), "An entirely empty draft must remain empty");
        equal("_CABLE_2", ForgeCells.cableName(" _Cable_2 "), "Cable names must use PC ASCII case folding");
        equal("", ForgeCells.cableName("_"), "An empty cable name is not a cable");
        equal("", ForgeCells.cableName("_câble"), "Non-ASCII names must remain ordinary draft text");
        equal("", ForgeCells.cableName("_A + _B"), "An expression is not a single cable atom");
        rename("_old * _OLD + _Old", "_NEW * _NEW + _NEW");
        rename("_OLD_extra + X_OLD + _OLD2 + (_old)", "_OLD_extra + X_OLD + _OLD2 + (_NEW)");
        rename("_OLD # _old comment", "_NEW # _old comment");
        rename("\"_OLD # quoted\" + _old # rest", "\"_OLD # quoted\" + _NEW # rest");
        rename("\"unterminated _OLD", "\"unterminated _OLD");
        // PC only recognizes double quotes; backslashes do not escape quotes.
        rename("'_old' + _OLD", "'_NEW' + _NEW");
        rename("\"a\\\" _old", "\"a\\\" _NEW");
        rename("Préfixe 🧪 _old\r\n# _OLD", "Préfixe 🧪 _NEW\r\n# _OLD");
        String large = "_old + ".repeat(10000) + "\"_old\" # _old";
        equal("_NEW + ".repeat(10000) + "\"_old\" # _old", ForgeCells.renameCableTokens(large, "_OLD", "_NEW"), "Large lexical rename must remain exact");
        System.out.println("Forge cell drafts: " + checks + " checks passed");
    }
}
