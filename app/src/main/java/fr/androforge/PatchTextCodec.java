package fr.androforge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Strict, bounded UTF-8 IO shared by documents, autosave and draft recovery. */
final class PatchTextCodec {
    private PatchTextCodec() {}

    static byte[] encode(String text) throws IOException {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IOException("The text contains an incomplete Unicode character.", exception);
        }
    }

    static String read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        for (;;) {
            int count = input.read(chunk);
            if (count == -1) break;
            if (count == 0) {
                int single = input.read();
                if (single == -1) break;
                chunk[0] = (byte) single;
                count = 1;
            }
            if (count > limit - total) throw new IOException("The file exceeds the allowed limit (" + limit / (1024 * 1024) + " MiB).");
            total += count;
            buffer.write(chunk, 0, count);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer.toByteArray())).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("The file is not valid UTF-8 text. Convert its encoding before importing it.", exception);
        }
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray();
            char[] result = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                result[i * 2] = hex[(digest[i] & 255) >>> 4];
                result[i * 2 + 1] = hex[digest[i] & 15];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static boolean canEditInTextView(String text) {
        if (text.length() > 256 * 1024) return false;
        int lines = 1, lineCharacters = 0;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\n' || character == '\r') {
                if (character == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (++lines > 4096) return false;
                lineCharacters = 0;
            } else if (++lineCharacters > 16384) return false;
        }
        return true;
    }
}
