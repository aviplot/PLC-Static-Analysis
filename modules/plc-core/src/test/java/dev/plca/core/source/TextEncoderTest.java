package dev.plca.core.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextEncoderTest {

    private static final String ST = "PROGRAM P (* Größe, 5 € *)\r\nEND_PROGRAM\r\n";

    /** Decode then encode an unchanged file: the same bytes, whatever the charset and BOM. */
    @ParameterizedTest
    @ValueSource(strings = {"utf8", "utf8-bom", "utf16le-bom", "utf16be-bom", "utf16le", "windows-1252"})
    void roundTripKeepsBytes(String kind) {
        byte[] original = switch (kind) {
            case "utf8" -> ST.getBytes(StandardCharsets.UTF_8);
            case "utf8-bom" -> concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, ST, StandardCharsets.UTF_8);
            case "utf16le-bom" -> concat(new byte[] {(byte) 0xFF, (byte) 0xFE}, ST, StandardCharsets.UTF_16LE);
            case "utf16be-bom" -> concat(new byte[] {(byte) 0xFE, (byte) 0xFF}, ST, StandardCharsets.UTF_16BE);
            case "utf16le" -> ST.getBytes(StandardCharsets.UTF_16LE);
            default -> ST.getBytes(TextDecoder.WINDOWS_1252);
        };
        TextDecoder.Decoded d = TextDecoder.decode(original);
        TextEncoder.Encoded e = TextEncoder.encode(d.text(), d.charset(), d.bomLength());
        assertThat(e.data()).isEqualTo(original);
        assertThat(e.fellBack()).isFalse();
    }

    @Test
    void unmappableFallsBackToUtf8WithBom() {
        String edited = "PROGRAM P (* Größe → Höhe ✓ → *)\nEND_PROGRAM\n";
        TextEncoder.Encoded e = TextEncoder.encode(edited, TextDecoder.WINDOWS_1252, 0);
        assertThat(e.fellBack()).isTrue();
        assertThat(e.unmappable()).isEqualTo("→✓");
        assertThat(e.charset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(e.requested()).isEqualTo(TextDecoder.WINDOWS_1252);
        TextDecoder.Decoded back = TextDecoder.decode(e.data());
        assertThat(back.text()).isEqualTo(edited);
        assertThat(back.bomLength()).isEqualTo(3);
    }

    @Test
    void xmlDeclarationFollowsTheFallback() {
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<a>→</a>\n";
        TextEncoder.Encoded e = TextEncoder.encode(xml, Charset.forName("ISO-8859-1"), 0);
        assertThat(new String(e.data(), 3, e.data().length - 3, StandardCharsets.UTF_8))
                .isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<a>→</a>\n");
        // a declaration the file keeps is left alone
        String ok = "<?xml version='1.0' encoding='windows-1252'?><a>ä</a>";
        assertThat(TextEncoder.encode(ok, TextDecoder.WINDOWS_1252, 0).data())
                .isEqualTo(ok.getBytes(TextDecoder.WINDOWS_1252));
    }

    @Test
    void asciiOriginalGetsABomOnceItIsNot() {
        byte[] ascii = "PROGRAM P END_PROGRAM".getBytes(StandardCharsets.US_ASCII);
        assertThat(TextEncoder.encodeLike("PROGRAM P (* x *) END_PROGRAM", ascii)
                        .bom())
                .isFalse();
        TextEncoder.Encoded e = TextEncoder.encodeLike("PROGRAM P (* Größe *) END_PROGRAM", ascii);
        assertThat(e.bom()).isTrue();
        assertThat(e.fellBack()).isFalse();
        assertThat(TextEncoder.encodeLike(
                                "<?xml version=\"1.0\"?><a>ä</a>",
                                "<?xml version=\"1.0\"?><a/>".getBytes(StandardCharsets.US_ASCII))
                        .bom())
                .isFalse();
    }

    private static byte[] concat(byte[] bom, String text, Charset cs) {
        byte[] body = text.getBytes(cs);
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }
}
