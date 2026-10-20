package dev.plca.core.source;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The inverse of {@link TextDecoder}: writes edited text back in the charset and with the BOM the file came in, so an
 * IDE imports it as it exported it. Text the charset cannot hold (a {@code →} in a Windows-1252 file) is written as
 * UTF-8 with BOM instead, with an XML declaration's {@code encoding} changed to match.
 */
public final class TextEncoder {

    /**
     * Encoded bytes. {@code unmappable}: the characters {@code requested} could not hold, in text order (empty when the
     * file kept its charset).
     */
    public record Encoded(byte[] data, Charset charset, boolean bom, Charset requested, String unmappable) {
        public boolean fellBack() {
            return !unmappable.isEmpty();
        }
    }

    private static final Pattern XML_ENCODING =
            Pattern.compile("\\A(\\s*<\\?xml\\b[^>]*?\\bencoding\\s*=\\s*)([\"'])([^\"']*)\\2");

    private TextEncoder() {}

    /**
     * Encodes {@code text} as the file whose bytes were {@code original}. A pure ASCII original says nothing about its
     * charset: text that is no longer ASCII is written as UTF-8 with BOM, so an IDE that assumes the ANSI code page for
     * files without one still reads it right (XML says UTF-8 in its declaration, or by default, and gets no BOM).
     */
    public static Encoded encodeLike(String text, byte[] original) {
        TextDecoder.Decoded d = TextDecoder.decode(original);
        if (d.bomLength() == 0 && d.charset().equals(StandardCharsets.UTF_8) && isAscii(original) && !isAscii(text)) {
            boolean xml = text.stripLeading().startsWith("<?xml");
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            return new Encoded(
                    withBom(utf8, StandardCharsets.UTF_8, !xml), StandardCharsets.UTF_8, !xml, d.charset(), "");
        }
        return encode(text, d.charset(), d.bomLength());
    }

    private static boolean isAscii(byte[] data) {
        for (byte b : data) {
            if (b < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAscii(String text) {
        return text.chars().allMatch(c -> c < 0x80);
    }

    /**
     * Encodes {@code text} like a file {@link TextDecoder#decode decoded} as {@code charset} with a BOM of that size.
     */
    public static Encoded encode(String text, Charset charset, int bomLength) {
        boolean bom = bomLength > 0;
        byte[] data = strict(text, charset);
        if (data != null) {
            return new Encoded(withBom(data, charset, bom), charset, bom, charset, "");
        }
        String unmappable = unmappable(text, charset);
        Matcher m = XML_ENCODING.matcher(text);
        String utf8Text = m.find() ? m.replaceFirst("$1$2UTF-8$2") : text;
        byte[] utf8 = utf8Text.getBytes(StandardCharsets.UTF_8);
        return new Encoded(
                withBom(utf8, StandardCharsets.UTF_8, true), StandardCharsets.UTF_8, true, charset, unmappable);
    }

    private static byte[] strict(String text, Charset charset) {
        CharsetEncoder e = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer b = e.encode(CharBuffer.wrap(text));
            return Arrays.copyOfRange(b.array(), b.arrayOffset() + b.position(), b.arrayOffset() + b.limit());
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    private static String unmappable(String text, Charset charset) {
        CharsetEncoder e = charset.newEncoder();
        Set<String> out = new LinkedHashSet<>();
        text.codePoints().forEach(cp -> {
            String s = Character.toString(cp);
            if (!e.canEncode(s)) {
                out.add(s);
            }
        });
        return String.join("", out);
    }

    private static byte[] withBom(byte[] data, Charset charset, boolean bom) {
        if (!bom) {
            return data;
        }
        byte[] mark = bom(charset);
        byte[] out = new byte[mark.length + data.length];
        System.arraycopy(mark, 0, out, 0, mark.length);
        System.arraycopy(data, 0, out, mark.length, data.length);
        return out;
    }

    private static byte[] bom(Charset charset) {
        if (charset.equals(StandardCharsets.UTF_8)) {
            return new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        }
        if (charset.equals(StandardCharsets.UTF_16LE)) {
            return new byte[] {(byte) 0xFF, (byte) 0xFE};
        }
        if (charset.equals(StandardCharsets.UTF_16BE)) {
            return new byte[] {(byte) 0xFE, (byte) 0xFF};
        }
        return new byte[0];
    }
}
