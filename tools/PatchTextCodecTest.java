package fr.androforge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.util.Arrays;
import java.util.HexFormat;

/** Runs the real document codec with hostile providers and boundary documents. */
public final class PatchTextCodecTest {
    private static int checks;
    private static final int MAXIMUM_BYTES = 4 * 1024 * 1024;
    private static final String UNICODE = "\ufeffA\r\n\u0000🥁\r漢";
    private static final byte[] UNICODE_BYTES = HexFormat.of().parseHex("efbbbf410d0a00f09fa5810de6bca2");

    private interface IOAction { void run() throws IOException; }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static IOException rejected(IOAction operation) {
        try { operation.run(); }
        catch (IOException expected) {
            check(true, "IO failure observed");
            return expected;
        }
        throw new AssertionError("Invalid document unexpectedly accepted");
    }

    private static void losslessUtf8AndProviderReads() throws IOException {
        check(Arrays.equals(UNICODE_BYTES, PatchTextCodec.encode(UNICODE)), "Encoding changed BOM, CRLF, CR, NUL, emoji or CJK bytes");
        check(UNICODE.equals(PatchTextCodec.read(new ByteArrayInputStream(UNICODE_BYTES), UNICODE_BYTES.length)), "Reading changed original text");
        ByteArrayInputStream fragmented = new ByteArrayInputStream(UNICODE_BYTES) {
            @Override public synchronized int read(byte[] destination, int offset, int length) {
                return super.read(destination, offset, Math.min(length, 1));
            }
        };
        check(UNICODE.equals(PatchTextCodec.read(fragmented, UNICODE_BYTES.length)), "UTF-8 code points split across provider reads were damaged");

        final int[] singleReads = {0};
        InputStream zeroBulk = new InputStream() {
            private final ByteArrayInputStream actual = new ByteArrayInputStream(UNICODE_BYTES);
            @Override public int read(byte[] destination, int offset, int length) { return 0; }
            @Override public int read() { singleReads[0]++; return actual.read(); }
        };
        check(UNICODE.equals(PatchTextCodec.read(zeroBulk, UNICODE_BYTES.length)), "A provider returning zero caused truncation or incorrect EOF handling");
        check(singleReads[0] == UNICODE_BYTES.length + 1, "Zero-byte provider did not make bounded forward progress");
        check("".equals(PatchTextCodec.read(new ByteArrayInputStream(new byte[0]), 0)), "Empty document must be accepted with a zero-byte limit");
        rejected(() -> PatchTextCodec.read(new ByteArrayInputStream(new byte[]{0}), 0));

        IOException providerFailure = new IOException("Provider disconnected");
        IOException observed = rejected(() -> PatchTextCodec.read(new InputStream() {
            @Override public int read() throws IOException { throw providerFailure; }
        }, MAXIMUM_BYTES));
        check(observed == providerFailure, "The provider's read failure must propagate unchanged");
    }

    private static void malformedDocumentsNeverGetReplacedSilently() {
        String[] malformed = {
                "80", "c0af", "c2", "e282", "eda080", "f4908080", "f09fa5", "e228a1", "fffe4100", "e9"
        };
        for (String hex : malformed) {
            byte[] bytes = HexFormat.of().parseHex(hex);
            IOException error = rejected(() -> PatchTextCodec.read(new ByteArrayInputStream(bytes), MAXIMUM_BYTES));
            check(error.getCause() instanceof CharacterCodingException, "Malformed UTF-8 must fail strict decoding: " + hex);
        }
        for (String text : new String[]{"\ud800", "\udfff", "prefix\ud800suffix", "\udc00\ud800"}) {
            IOException error = rejected(() -> PatchTextCodec.encode(text));
            check(error.getCause() instanceof CharacterCodingException, "Unpaired UTF-16 surrogate must fail encoding");
        }
    }

    private static void byteLimitsApplyBeforeDecoding() throws IOException {
        byte[] exact = new byte[MAXIMUM_BYTES];
        Arrays.fill(exact, (byte) 'x');
        exact[0] = '#';
        String decoded = PatchTextCodec.read(new ByteArrayInputStream(exact), MAXIMUM_BYTES);
        check(decoded.length() == MAXIMUM_BYTES, "Exact 4 MiB ASCII document was truncated");
        check(Arrays.equals(exact, PatchTextCodec.encode(decoded)), "Exact boundary document did not round trip byte for byte");
        byte[] tooLong = Arrays.copyOf(exact, MAXIMUM_BYTES + 1);
        rejected(() -> PatchTextCodec.read(new ByteArrayInputStream(tooLong), MAXIMUM_BYTES));

        String unicodeBoundary = "x" + "漢".repeat(MAXIMUM_BYTES / 3);
        byte[] unicodeBytes = PatchTextCodec.encode(unicodeBoundary);
        check(unicodeBytes.length == MAXIMUM_BYTES, "Multibyte boundary fixture must be exactly 4 MiB");
        check(unicodeBoundary.equals(PatchTextCodec.read(new ByteArrayInputStream(unicodeBytes), MAXIMUM_BYTES)), "Exact multibyte document was rejected or normalized");
        byte[] unicodeTooLong = PatchTextCodec.encode(unicodeBoundary + "x");
        rejected(() -> PatchTextCodec.read(new ByteArrayInputStream(unicodeTooLong), MAXIMUM_BYTES));
        rejected(() -> PatchTextCodec.read(new ByteArrayInputStream(UNICODE_BYTES), UNICODE_BYTES.length - 1));
    }

    private static void androidTextWidgetBudgets() {
        check(PatchTextCodec.canEditInTextView(UNICODE), "Normal imported patch text must remain editable");
        check(PatchTextCodec.canEditInTextView("x".repeat(16384)), "Exact line budget must be editable");
        check(!PatchTextCodec.canEditInTextView("x".repeat(16385)), "One oversized line must use safe export instead of TextView");
        check(PatchTextCodec.canEditInTextView("🥁".repeat(8192)), "Supplementary text at the UTF-16 line boundary must be editable");
        check(!PatchTextCodec.canEditInTextView("🥁".repeat(8193)), "Supplementary text must count its UTF-16 layout cost");
        for (String newline : new String[]{"\n", "\r\n", "\r"}) {
            String exactLines = "x" + (newline + "x").repeat(4095);
            check(PatchTextCodec.canEditInTextView(exactLines), "Exact line count rejected for newline " + newline.replace("\r", "CR").replace("\n", "LF"));
            check(!PatchTextCodec.canEditInTextView(exactLines + newline + "x"), "Too many lines accepted");
        }
        check(PatchTextCodec.canEditInTextView("\r\n".repeat(4095)), "CRLF must count once, including final empty line");
        check(!PatchTextCodec.canEditInTextView("\r\n".repeat(4096)), "Trailing empty lines must count toward layout budget");
        String totalBoundary = ("x".repeat(16383) + "\n").repeat(16);
        check(totalBoundary.length() == 256 * 1024, "Total-character fixture must hit the exact budget");
        check(PatchTextCodec.canEditInTextView(totalBoundary), "Exact total-character budget must be editable");
        check(!PatchTextCodec.canEditInTextView(totalBoundary + "x"), "Total-character overrun must be blocked despite safe individual lines");
    }

    private static void stableSourceFingerprints() {
        check("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".equals(PatchTextCodec.sha256("")), "Incorrect SHA256 of empty source");
        check("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(PatchTextCodec.sha256("abc")), "Incorrect published SHA256 test vector");
        check("ba1e8fc0046b1dcbfa6f450e5b98110d1d96c951aa8cfb0533c125c9cf0e544b".equals(PatchTextCodec.sha256(UNICODE)), "Source fingerprint changed BOM, Unicode, NUL or line endings");
        check(!PatchTextCodec.sha256("a\r\n").equals(PatchTextCodec.sha256("a\n")), "Fingerprint must distinguish CRLF and LF when rebasing drafts");
    }

    public static void main(String[] arguments) throws IOException {
        losslessUtf8AndProviderReads();
        malformedDocumentsNeverGetReplacedSilently();
        byteLimitsApplyBeforeDecoding();
        androidTextWidgetBudgets();
        stableSourceFingerprints();
        System.out.println("Text codec: " + checks + " checks passed (strict UTF-8, providers, size/layout limits, fingerprints)");
    }
}
