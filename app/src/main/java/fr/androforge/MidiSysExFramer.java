package fr.androforge;

import java.util.Arrays;

/** MIDI 1.0 framing only: no DROID command or acknowledgement is inferred. */
final class MidiSysExFramer {
    interface Listener {
        void onFrame(byte[] frame);
        void onDiscarded();
    }

    static final int MAX_ACTIVATION_BYTES = 64006;
    private final byte[] buffer;
    private final Listener listener;
    private int length;

    MidiSysExFramer(int maximumBytes, Listener listener) {
        if (maximumBytes < 2 || maximumBytes > 1024 * 1024 || listener == null)
            throw new IllegalArgumentException("Invalid SysEx framing budget");
        buffer = new byte[maximumBytes];
        this.listener = listener;
    }

    synchronized void accept(byte[] data, int offset, int count) {
        if (data == null || offset < 0 || count < 0 || offset > data.length - count)
            throw new IllegalArgumentException("Invalid MIDI byte range");
        for (int index = offset; index < offset + count; index++) {
            int value = data[index] & 255;
            // System Real-Time messages may appear between any two SysEx bytes.
            if (value >= 0xf8) continue;
            if (value == 0xf0) {
                if (length != 0) discard();
                buffer[0] = (byte) value; length = 1;
            } else if (length != 0) {
                if (value != 0xf7 && value >= 0x80 || length == buffer.length) {
                    discard();
                } else {
                    buffer[length++] = (byte) value;
                    if (value == 0xf7) {
                        byte[] frame = Arrays.copyOf(buffer, length);
                        length = 0;
                        listener.onFrame(frame);
                    }
                }
            }
        }
    }

    synchronized void reset() { length = 0; }
    synchronized int bufferedBytes() { return length; }

    private void discard() { length = 0; listener.onDiscarded(); }

    static String activationProblem(byte[] frame) {
        if (frame == null || frame.length < 6 || frame.length > MAX_ACTIVATION_BYTES)
            return "La trame DROID dépasse la limite de 64 000 octets de patch ou est vide.";
        if ((frame[0] & 255) != 0xf0 || frame[1] != 0 || frame[2] != 0x66 || frame[3] != 0x66
                || frame[4] != 'P' || (frame[frame.length - 1] & 255) != 0xf7)
            return "La trame ne correspond pas au transfert officiel de patch DROID.";
        for (int index = 5; index < frame.length - 1; index++)
            if ((frame[index] & 255) >= 128)
                return "Le transfert DROID requiert une charge ASCII MIDI de 7 bits.";
        return "";
    }
}
