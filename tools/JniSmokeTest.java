package fr.androforge;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes the Android JNI bridge on the host JVM, with no Android or JSON dependency. */
public final class JniSmokeTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(String expected, String actual, String message) {
        check(expected.equals(actual), message + "\nExpected: " + expected + "\nActual: " + actual);
    }

    private static void rejected(Runnable action, String expectedSource) {
        boolean rejected = false;
        try { action.run(); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Invalid JNI mutation must raise IllegalArgumentException");
        equal(expectedSource, NativeBridge.source(), "Rejected JNI mutation changed the document");
    }

    // A small lexical integrity check, not a substitute for the Android JSON parser.
    private static void validJsonEnvelope(String json) {
        Deque<Character> brackets = new ArrayDeque<>();
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (c < 32) throw new AssertionError("JSON contains an unescaped control character");
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') brackets.push(c);
            else if (c == '}' || c == ']') {
                if (brackets.isEmpty()) throw new AssertionError("Unexpected JSON closing bracket");
                char open = brackets.pop();
                if (!((open == '{' && c == '}') || (open == '[' && c == ']'))) {
                    throw new AssertionError("Mismatched JSON brackets");
                }
            }
        }
        check(!quoted && !escaped && brackets.isEmpty(), "Incomplete JSON response from JNI");
    }

    private static void unicodeAndHistory() {
        String original = "\ufeff# Séquence 🥁 🎛️ 漢字 Ελληνικά العربية\r\n"
                + "# NUL de référence : \u0000\r\n"
                + "[copy] # titre conservé\r\n"
                + "    input = I1  # commentaire inchangé\r\n"
                + "    output = O1\r\n";
        String initial = NativeBridge.load(original);
        equal(original, NativeBridge.source(), "UTF-16 / real UTF-8 round trip must preserve BMP, emoji, BOM, NUL and CRLF");
        check(initial.contains("\"firmware\":\"blue-7\""), "Unexpected bundled firmware version");
        check(initial.contains("\"name\":\"copy\""), "JNI state did not expose the circuit");
        check(initial.contains("\"canUndo\":false"), "Initial load must clear history");
        validJsonEnvelope(initial);

        String edited = original.replace("input = I1", "input = I2 * 0.5 + 2V");
        String state = NativeBridge.setParameter(0, "input", "I2 * 0.5 + 2V");
        equal(edited, NativeBridge.source(), "Parameter edit must preserve comments and CRLF through JNI");
        check(state.contains("\"canUndo\":true"), "Parameter edit did not create undo history");
        NativeBridge.undo();
        equal(original, NativeBridge.source(), "JNI undo did not restore exact Unicode source");
        NativeBridge.redo();
        equal(edited, NativeBridge.source(), "JNI redo did not restore exact edited source");

        String replaced = "# Nouveau patch 🎚️\n[display]\nheader = \"A # hash\"\n";
        NativeBridge.replaceSource(replaced);
        equal(replaced, NativeBridge.source(), "JNI replaceSource changed Unicode or quoted hashes");
        NativeBridge.undo();
        equal(edited, NativeBridge.source(), "Source replacement must be undoable through JNI");
        NativeBridge.redo();
        equal(replaced, NativeBridge.source(), "Source replacement must be redoable through JNI");
    }

    private static void rejectedMutationsAreAtomic() {
        String original = "# Document à préserver 🎹\n[copy]\ninput = I1\noutput = O1\n";
        NativeBridge.load(original);
        rejected(() -> NativeBridge.setParameter(0, "input", "2\n[lfo]"), original);
        rejected(() -> NativeBridge.setParameter(0, "input", "2\routput=O2"), original);
        rejected(() -> NativeBridge.setParameter(0, "input", "2\u0000extra"), original);
        rejected(() -> NativeBridge.setParameter(0, "input", "2 # injected comment"), original);
        rejected(() -> NativeBridge.setParameter(0, "input\noutput", "2"), original);
        rejected(() -> NativeBridge.setParameter(-1, "input", "2"), original);
        rejected(() -> NativeBridge.addCircuit("copy]\n[lfo"), original);
        rejected(() -> NativeBridge.load(null), original);
        check(NativeBridge.state().contains("\"canUndo\":false"), "Rejected JNI calls changed undo history");
        check(NativeBridge.state().contains("\"canRedo\":false"), "Rejected JNI calls changed redo history");
    }

    private static void officialCatalogue() {
        String catalogue = NativeBridge.catalogue();
        check(catalogue.startsWith("[") && catalogue.endsWith("]"), "JNI catalogue must be a JSON array");
        validJsonEnvelope(catalogue);
        // Each circuit has a manual page; parameter objects do not. Counting this
        // field keeps the check independent of optional metadata and key order.
        Pattern entry = Pattern.compile("\"manualPage\"\\s*:\\s*[0-9]+");
        Matcher matcher = entry.matcher(catalogue);
        int circuits = 0;
        while (matcher.find()) circuits++;
        check(circuits == 76, "Expected 76 official blue-7 circuits, found " + circuits);
        check(catalogue.contains("\"name\":\"copy\""), "Official copy circuit is missing");
        check(catalogue.contains("\"name\":\"display\""), "blue-7 display circuit is missing");
        check(catalogue.contains("\"name\":\"clockedtrigger\""), "blue-7 clockedtrigger circuit is missing");
        check(catalogue.contains("\"direction\":\"output\""), "Official schema directions are missing");
        System.out.println("Official catalogue: " + circuits + " blue-7 circuits");
    }

    private static void unicodeAndSizeRejections() {
        String original = "# Patch préservé 🧪\n[copy]\ninput = I1\noutput = O1\n";
        NativeBridge.load(original);
        rejected(() -> NativeBridge.load("# isolated high \ud800\n"), original);
        rejected(() -> NativeBridge.replaceSource("# isolated low \udfff\n"), original);
        rejected(() -> NativeBridge.setParameter(0, "input", "\ud800"), original);
        rejected(() -> NativeBridge.addCircuit("copy\udfff"), original);
        final int maximumBytes = 4 * 1024 * 1024;
        String tooLong = "#".repeat(maximumBytes + 1);
        rejected(() -> NativeBridge.load(tooLong), original);
        rejected(() -> NativeBridge.replaceSource(tooLong), original);
        rejected(() -> NativeBridge.setParameter(0, "input", tooLong), original);
        // UTF-16 length alone is insufficient: BMP and supplementary text need
        // up to three UTF-8 bytes per Java code unit.
        String utf8TooLong = "漢".repeat(maximumBytes / 3 + 1);
        rejected(() -> NativeBridge.load(utf8TooLong), original);
        String exactBoundary = "#" + "x".repeat(maximumBytes - 1);
        validJsonEnvelope(NativeBridge.load(exactBoundary));
        equal(exactBoundary, NativeBridge.source(), "4 MiB boundary must survive JNI without truncation");
        NativeBridge.load(original);
        NativeBridge.setParameter(0, "input", "I2");
        NativeBridge.undo();
        String before = NativeBridge.state();
        rejected(() -> NativeBridge.replaceSource(utf8TooLong), original);
        equal(before, NativeBridge.state(), "Rejection must preserve redo and the complete state");
        NativeBridge.redo();
        equal(original.replace("input = I1", "input = I2"), NativeBridge.source(), "Redo must survive a rejected source replacement");
    }

    private static void concurrentReadersAndEdits() {
        NativeBridge.load("[copy]\ninput = I1\noutput = O1\n");
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread[] readers = new Thread[3];
        for (int reader = 0; reader < readers.length; reader++) {
            readers[reader] = new Thread(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        String state = NativeBridge.state();
                        if (!state.contains("\"name\":\"copy\"")) throw new AssertionError("Concurrent state lost circuit");
                        String source = NativeBridge.source();
                        if (!source.startsWith("[copy]\ninput = I")) throw new AssertionError("Concurrent source is partial");
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            readers[reader].start();
        }
        for (int i = 0; i < 100; i++) NativeBridge.setParameter(0, "input", i % 2 == 0 ? "I2" : "I1");
        for (Thread reader : readers) {
            try { reader.join(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        }
        check(failure.get() == null, "Concurrent JNI reads failed: " + failure.get());
        equal("[copy]\ninput = I1\noutput = O1\n", NativeBridge.source(), "Concurrent edit sequence changed the final document");
    }

    private static void partialAnalysisIsRecoverable() {
        String source = "# oversized field retained\n[copy]\ninput = " + "1".repeat(8193) + "\noutput = O1\n";
        String state = NativeBridge.load(source);
        validJsonEnvelope(state);
        check(state.contains("\"analysisComplete\":false"), "Oversized field must mark partial analysis");
        equal(source, NativeBridge.source(), "Partial analysis must preserve the complete source");
        rejected(() -> NativeBridge.addCircuit("copy"), source);
        rejected(() -> NativeBridge.setParameter(0, "input", "I1"), source);
        rejected(() -> NativeBridge.removeCircuit(0), source);
        String recovered = "[copy]\ninput = I1\noutput = O1\n";
        NativeBridge.replaceSource(recovered);
        check(NativeBridge.state().contains("\"analysisComplete\":true"), "Source replacement must recover analysis");
        NativeBridge.undo();
        equal(source, NativeBridge.source(), "Undo must recover the complete partial document");
        NativeBridge.redo();
        equal(recovered, NativeBridge.source(), "Redo must recover the corrected document");
    }

    private static void forgeCellsAndCables() {
        NativeBridge.load("");
        validJsonEnvelope(NativeBridge.addCircuit("copy"));
        equal("[copy]\n    input = 0\n    output =\n", NativeBridge.source(), "Typical creation must initialize input defaults and retain blank output rows");
        NativeBridge.load("");
        NativeBridge.addCircuitWithSelection("lfo", 2);
        equal("[lfo]\n    hz = 1\n    square =\n", NativeBridge.source(), "Essential selection must use the official metadata");
        String essential = NativeBridge.source();
        rejected(() -> NativeBridge.addCircuitWithSelection("lfo", 4), essential);
        String expression = NativeBridge.analyzeExpression("_LEFT - _RIGHT", false);
        validJsonEnvelope(expression);
        check(expression.contains("\"editable\":true") && expression.contains("\"base\":\"-1\"")
            && expression.contains("\"factor\":\"_RIGHT\"") && expression.contains("\"offset\":\"_LEFT\""), "Subtraction must expose Forge's three cells");
        equal("I1 / 2 - 1V", NativeBridge.composeExpression("I1", "1 / 2", "-1V", false), "Fraction and negative offset must survive JNI composition");
        check(NativeBridge.analyzeExpression("I1 * 2 * 3", false).contains("\"editable\":false"), "Complex expressions must remain in raw editing");
        rejected(() -> NativeBridge.composeExpression("I1", "1 / 0", "", false), essential);
        rejected(() -> NativeBridge.composeExpression("_CABLE", "2", "", true), essential);
        rejected(() -> NativeBridge.analyzeExpression("\ud800", false), essential);
        String original = "\ufeff# Câblage 🧪\r\n[copy]\r\ninput = I1 * 2 + 3 # target\r\noutput = O1 # source\r\n";
        NativeBridge.load(original);
        String state = NativeBridge.setParameterExpression(0, "input", "I2", "0.5", "-2V");
        validJsonEnvelope(state);
        check(state.contains("\"direction\":\"input\"") && state.contains("\"expression\":{\"editable\":true"), "State must expose direction and cell metadata");
        NativeBridge.undo(); equal(original, NativeBridge.source(), "Cell edit must preserve Unicode source and history");
        validJsonEnvelope(NativeBridge.connectParameters(0, "output", 0, "O1", 0, "input", 1, "I4 * 0.25 + 2V", "wire"));
        String connected = original.replace("I1 * 2 + 3", "I4 * _WIRE + 2V").replace("output = O1", "output = _WIRE");
        equal(connected, NativeBridge.source(), "Connecting endpoints must retain every other draft cell and comment");
        String cables = NativeBridge.cables();
        validJsonEnvelope(cables);
        check(cables.contains("\"name\":\"_WIRE\",\"inputs\":1,\"outputs\":1") && cables.contains("\"column\":1"), "Cable inventory must identify the factor and output endpoints");
        rejected(() -> NativeBridge.connectParameters(0, "output", 1, "_WIRE", 0, "input", 0, "I1", "_OTHER"), connected);
        rejected(() -> NativeBridge.connectParameters(0, "output", 0, "_WIRE", 0, "input", 3, "I1", "_OTHER"), connected);
        NativeBridge.undo(); equal(original, NativeBridge.source(), "Both cable endpoints must Undo together");
        NativeBridge.redo(); equal(connected, NativeBridge.source(), "Both cable endpoints must Redo together");
        NativeBridge.undo();
        validJsonEnvelope(NativeBridge.connectParameterExpressions(0, "output", 0, "O1", "", "", 0, "input", 0, "", "2", "3", "empty"));
        String emptyBase = original.replace("I1 * 2 + 3", "_EMPTY * 2 + 3").replace("output = O1", "output = _EMPTY");
        equal(emptyBase, NativeBridge.source(), "Connecting an empty base must preserve the distinct factor and offset draft cells");
        rejected(() -> NativeBridge.connectParameterExpressions(0, "output", 0, "O1", "2", "", 0, "input", 0, "", "2", "3", "bad"), emptyBase);
        rejected(() -> NativeBridge.connectParameterExpressions(0, "output", 0, "O1", "", "", 0, "input", 0, "\ud800", "2", "3", "bad"), emptyBase);
        rejected(() -> NativeBridge.connectParameterExpressions(0, "output", 0, "O1", "", "", 0, "input", 0, "I1 * 2", "", "", "bad"), emptyBase);
        NativeBridge.undo(); equal(original, NativeBridge.source(), "Explicit cell connection must Undo both endpoints together");
        NativeBridge.connectParameters(0, "output", 0, "O1", 0, "input", 1, "I4 * 0.25 + 2V", "wire");
        validJsonEnvelope(NativeBridge.renameCable("wire", "renamed"));
        String renamed = connected.replace("_WIRE", "_RENAMED");
        equal(renamed, NativeBridge.source(), "Cable rename must preserve the Unicode source");
        NativeBridge.undo(); equal(connected, NativeBridge.source(), "Global rename must be one Undo step");
        NativeBridge.load("[copy]\ninput = _A + _B\noutput = _A\n");
        String collision = NativeBridge.source();
        rejected(() -> NativeBridge.renameCable("a", "b"), collision);
        rejected(() -> NativeBridge.renameCableWithMerge("a", "b", false), collision);
        validJsonEnvelope(NativeBridge.renameCableWithMerge("a", "b", true));
        equal("[copy]\ninput = _B + _B\noutput = _B\n", NativeBridge.source(), "Explicit cable fusion must retain both occurrences");
        NativeBridge.undo(); equal(collision, NativeBridge.source(), "Explicit fusion must Undo atomically");
        validJsonEnvelope(NativeBridge.deleteCable("a"));
        equal("[copy]\ninput = _B\noutput = \n", NativeBridge.source(), "Disconnecting a native cable must preserve the remaining input atom");
    }

    public static void main(String[] arguments) {
        unicodeAndHistory();
        rejectedMutationsAreAtomic();
        officialCatalogue();
        unicodeAndSizeRejections();
        concurrentReadersAndEdits();
        partialAnalysisIsRecoverable();
        forgeCellsAndCables();
        registerLabelsAndCircuitComments();
        deploymentAndActivation();
        System.out.println("Host JNI smoke: " + checks + " checks passed (Unicode, cells, cables, comments, labels, deployment, activation, history, atomic rejections, catalogue)");
    }

    private static void registerLabelsAndCircuitComments() {
        String original = "\ufeff# Mon patch 🥁\r\n# LABELS: master=18;unknown=keep\r\n"
            + "# I1: [CLK] external clock\r\n# G1: [OLD] alias\r\n# G1.1: [NEW] effective\r\n"
            + "\r\n[p10]\r\n[copy] # circuit inline\r\ninput = I1 # parameter comment\r\noutput = G2.1\r\n";
        String state = NativeBridge.load(original);
        validJsonEnvelope(state);
        check(state.contains("\"catalogueFirmware\":\"blue-7\"") && state.contains("\"masterType\":18"), "Supported catalogue and master metadata must remain distinct");
        check(state.contains("\"register\":\"G1.1\",\"short\":\"NEW\"") && !state.contains("\"short\":\"OLD\""), "Register aliases and duplicate labels must select the last effective definition");
        check(state.contains("\"comment\":\"circuit inline\"") && state.contains("\"rackModules\""), "State must expose circuit comments and native rack numbering");
        NativeBridge.setRegisterLabel("i1", "CLOCK 🥁", "Horloge externe éè");
        String updated = original.replace("[CLK] external clock", "[CLOCK 🥁] Horloge externe éè");
        equal(updated, NativeBridge.source(), "Register label edit must preserve CRLF, unknown metadata and other annotations");
        NativeBridge.undo(); equal(original, NativeBridge.source(), "Label edit must Undo atomically");
        NativeBridge.redo(); equal(updated, NativeBridge.source(), "Label edit must Redo atomically");
        NativeBridge.undo();
        NativeBridge.setRegisterLabel("G1", "", "");
        equal(original.replace("# G1: [OLD] alias\r\n", "").replace("# G1.1: [NEW] effective\r\n", ""), NativeBridge.source(), "Removing a label must remove all aliases without exposing a stale duplicate");
        NativeBridge.undo(); equal(original, NativeBridge.source(), "Removing duplicate labels must Undo together");
        validJsonEnvelope(NativeBridge.setBlockComment(1, "Commentaire 🥁\nSeconde ligne"));
        String commented = original.replace("[copy] # circuit inline", "# Commentaire 🥁\r\n# Seconde ligne\r\n[copy]");
        equal(commented, NativeBridge.source(), "Multiline circuit comment must preserve parameter comments and labels");
        NativeBridge.undo(); equal(original, NativeBridge.source(), "Circuit comments must Undo byte-exactly");
        String before = NativeBridge.state();
        rejected(() -> NativeBridge.setRegisterLabel("I1", "\ud800", "text"), original);
        rejected(() -> NativeBridge.setRegisterLabel("I1", "CLOCK", "\udc00"), original);
        rejected(() -> NativeBridge.setRegisterLabel("I1", "a]b", "text"), original);
        rejected(() -> NativeBridge.setRegisterLabel("I1", "a".repeat(19), "text"), original);
        rejected(() -> NativeBridge.setRegisterLabel("I1", "CLOCK", "text\n[copy]"), original);
        rejected(() -> NativeBridge.setRegisterLabel("I0", "CLOCK", "text"), original);
        rejected(() -> NativeBridge.setBlockComment(1, "\ud800"), original);
        rejected(() -> NativeBridge.setBlockComment(1, "[lfo]"), original);
        rejected(() -> NativeBridge.setBlockComment(1, "input = I2"), original);
        rejected(() -> NativeBridge.setBlockComment(0, "controller"), original);
        rejected(() -> NativeBridge.setBlockComment(1, "a".repeat(16385)), original);
        rejected(() -> NativeBridge.setBlockComment(1, "bad\u0000text"), original);
        rejected(() -> NativeBridge.setRegisterLabel(null, "CLOCK", "text"), original);
        rejected(() -> NativeBridge.setBlockComment(1, null), original);
        equal(before, NativeBridge.state(), "Rejected annotation edits must preserve source, metadata and history");
        NativeBridge.load("#INPUTS:\n# I1: [" + "a".repeat(8193) + "] oversized\n[copy]\ninput = I1\n");
        String oversized = NativeBridge.source();
        check(NativeBridge.state().contains("\"analysisComplete\":false"), "Oversized annotation model must remain explicitly partial");
        rejected(() -> NativeBridge.setRegisterLabel("I1", "CLK", ""), oversized);
        rejected(() -> NativeBridge.setBlockComment(0, "text"), oversized);
        equal(oversized, NativeBridge.source(), "Partial annotation analysis must preserve all original bytes");
        String controllers = "# LABELS: master=18\n# P1.1: [OLD] removed\n# P2.3: [KEEP] remaining\n"
            + "[p10]\n[m4]\n[copy]\ninput = P2.3\noutput = O1\n# input = B2.4\n";
        NativeBridge.load(controllers);
        validJsonEnvelope(NativeBridge.removeCircuit(0));
        equal(controllers.replace("# P1.1: [OLD] removed\n", "").replace("[p10]\n", "")
            .replace("P2.3", "P1.3").replace("B2.4", "B1.4"), NativeBridge.source(), "Unused controller removal must renumber labels and active/disabled references together");
        NativeBridge.undo(); equal(controllers, NativeBridge.source(), "Controller renumbering must Undo byte-exactly");
        String used = "[p10]\n[m4]\n[copy]\n# input = P1.1\noutput = O1\n";
        NativeBridge.load(used);
        NativeBridge.setParameter(2, "output", "O2"); NativeBridge.undo();
        String usedState = NativeBridge.state();
        rejected(() -> NativeBridge.removeCircuit(0), used);
        equal(usedState, NativeBridge.state(), "Controller removal rejection must preserve disabled references and history");
        NativeBridge.redo(); equal(used.replace("output = O1", "output = O2"), NativeBridge.source(), "Controller rejection must preserve Redo");
    }

    private static void deploymentAndActivation() {
        String original = "\ufeff# Patch 🥁 éè\r\n# LABELS: master=18;unknown=keep\r\n"
                + "# I1: [CLK] Horloge 🥁\r\n\r\n"
                + "[copy]\r\ninput = I1 * 0.5 + -2V # annotation\r\noutput = O1\r\n"
                + "# input = bad disabled value\r\n[p10]\r\n[display]\r\nheader = \"a # b\"\r\n";
        NativeBridge.load(original);
        NativeBridge.setParameter(0, "input", "I2"); NativeBridge.undo();
        String state = NativeBridge.state();
        String expected = "[p10]\n[copy]\ninput=I1*0.5-2V\noutput=O1\n[display]\nheader=\"a # b\"\n";
        equal(expected, NativeBridge.prepareDeployment(), "Deployment must compact only active patch data and preserve quoted text");
        byte[] bytes = expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] frame = new byte[bytes.length + 6];
        frame[0] = (byte) 0xf0; frame[1] = 0; frame[2] = 0x66; frame[3] = 0x66; frame[4] = 'P';
        System.arraycopy(bytes, 0, frame, 5, bytes.length); frame[frame.length - 1] = (byte) 0xf7;
        check(Arrays.equals(frame, NativeBridge.activationMessage(expected)), "JNI must return the exact unsigned MIDI upload bytes");
        equal(original, NativeBridge.source(), "Preparing deployment/activation changed original annotations or source");
        equal(state, NativeBridge.state(), "Preparing deployment/activation changed history");
        rejected(() -> NativeBridge.activationMessage(null), original);
        rejected(() -> NativeBridge.activationMessage("\ud800"), original);
        rejected(() -> NativeBridge.activationMessage("\udc00"), original);
        rejected(() -> NativeBridge.activationMessage("[display]\nheader=\"é\"\n"), original);
        rejected(() -> NativeBridge.activationMessage("[copy]\ninput=\u0000\n"), original);
        rejected(() -> NativeBridge.activationMessage("[copy]\r\ninput=1\r\n"), original);
        rejected(() -> NativeBridge.activationMessage("a".repeat(64)), original);
        String max = ("a".repeat(63) + "\n").repeat(1000);
        check(NativeBridge.activationMessage(max).length == 64006, "Exact maximum activation payload must be accepted");
        rejected(() -> NativeBridge.activationMessage(max + "\n"), original);
        equal(state, NativeBridge.state(), "Rejected activation arguments changed source/history");
        NativeBridge.redo();
        equal(original.replace("input = I1 * 0.5 + -2V", "input = I2"), NativeBridge.source(), "Read-only deployment failures must preserve Redo");
        for (String invalid : new String[]{"[copy]\ninput=\n", "[future]\n", "[copy]\nunknown=1\n",
                "[copy]\ninput=I1 / 0\n", "[display]\nheader=\"é\"\n", "[copy]\noutput=_EMPTY\n"}) {
            NativeBridge.load(invalid);
            String before = NativeBridge.state();
            rejected(NativeBridge::prepareDeployment, invalid);
            equal(before, NativeBridge.state(), "Rejected deployment must retain the whole source and native state");
        }
        String partial = "[copy]\ninput=" + "a".repeat(8193) + "\n";
        NativeBridge.load(partial);
        check(NativeBridge.state().contains("\"analysisComplete\":false"), "Oversized input must expose partial analysis");
        rejected(NativeBridge::prepareDeployment, partial);
        NativeBridge.load("");
        equal("", NativeBridge.prepareDeployment(), "Empty patch serialization must match the official empty payload");
        check(Arrays.equals(new byte[]{(byte)0xf0,0,0x66,0x66,'P',(byte)0xf7}, NativeBridge.activationMessage("")), "Empty payload framing must match the official codec");
    }
}
