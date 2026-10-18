package dev.plca.core.source;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes PLC source exports. Observed in the wild: UTF-8 with and without BOM (TwinCAT, L5X), UTF-16 with BOM (some
 * CODESYS exports), and Windows-1252 (German/European comments in older ST and SCL files). Never fails: anything that
 * is not valid UTF-8 falls back to Windows-1252, which maps every byte.
 */
public final class TextDecoder {

    public static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /** Decoded text; {@code bomLength} bytes were skipped and are not part of {@code text}. */
    public record Decoded(String text, Charset charset, int bomLength) {}

    private TextDecoder() {}

    public static Decoded decode(byte[] data) {
        if (startsWith(data, 0xEF, 0xBB, 0xBF)) {
            return new Decoded(new String(data, 3, data.length - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8, 3);
        }
        if (startsWith(data, 0xFF, 0xFE)) {
            return new Decoded(
                    new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE, 2);
        }
        if (startsWith(data, 0xFE, 0xFF)) {
            return new Decoded(
                    new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE, 2);
        }
        Charset utf16 = guessBomlessUtf16(data);
        if (utf16 != null) {
            return new Decoded(new String(data, utf16), utf16, 0);
        }
        try {
            CharBuffer chars = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data));
            return new Decoded(chars.toString(), StandardCharsets.UTF_8, 0);
        } catch (CharacterCodingException e) {
            return new Decoded(new String(data, WINDOWS_1252), WINDOWS_1252, 0);
        }
    }

    /** ASCII-heavy text in UTF-16 has a NUL in every other byte; source code never contains NULs otherwise. */
    private static Charset guessBomlessUtf16(byte[] data) {
        int n = Math.min(data.length, 4096) & ~1;
        if (n < 4) {
            return null;
        }
        int evenZeros = 0;
        int oddZeros = 0;
        for (int i = 0; i < n; i += 2) {
            if (data[i] == 0) {
                evenZeros++;
            }
            if (data[i + 1] == 0) {
                oddZeros++;
            }
        }
        int pairs = n / 2;
        if (oddZeros > pairs * 0.9 && evenZeros == 0) {
            return StandardCharsets.UTF_16LE;
        }
        if (evenZeros > pairs * 0.9 && oddZeros == 0) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
