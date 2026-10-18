package dev.plca.core.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextDecoderTest {

    private static final String ST = "PROGRAM P (* Größe *)\nEND_PROGRAM\n";

    @Test
    void utf8WithBom() {
        byte[] body = ST.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[body.length + 3];
        data[0] = (byte) 0xEF;
        data[1] = (byte) 0xBB;
        data[2] = (byte) 0xBF;
        System.arraycopy(body, 0, data, 3, body.length);
        TextDecoder.Decoded d = TextDecoder.decode(data);
        assertThat(d.text()).isEqualTo(ST);
        assertThat(d.charset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(d.bomLength()).isEqualTo(3);
    }

    @Test
    void utf8WithoutBom() {
        TextDecoder.Decoded d = TextDecoder.decode(ST.getBytes(StandardCharsets.UTF_8));
        assertThat(d.text()).isEqualTo(ST);
        assertThat(d.charset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(d.bomLength()).isZero();
    }

    @Test
    void utf16WithBom() {
        // Java's UTF_16 encoder writes a big-endian BOM
        TextDecoder.Decoded be = TextDecoder.decode(ST.getBytes(StandardCharsets.UTF_16));
        assertThat(be.text()).isEqualTo(ST);
        assertThat(be.charset()).isEqualTo(StandardCharsets.UTF_16BE);

        byte[] body = ST.getBytes(StandardCharsets.UTF_16LE);
        byte[] le = new byte[body.length + 2];
        le[0] = (byte) 0xFF;
        le[1] = (byte) 0xFE;
        System.arraycopy(body, 0, le, 2, body.length);
        TextDecoder.Decoded d = TextDecoder.decode(le);
        assertThat(d.text()).isEqualTo(ST);
        assertThat(d.charset()).isEqualTo(StandardCharsets.UTF_16LE);
        assertThat(d.bomLength()).isEqualTo(2);
    }

    @Test
    void utf16WithoutBom() {
        TextDecoder.Decoded d = TextDecoder.decode(ST.getBytes(StandardCharsets.UTF_16LE));
        assertThat(d.text()).isEqualTo(ST);
        assertThat(d.charset()).isEqualTo(StandardCharsets.UTF_16LE);
    }

    @Test
    void invalidUtf8FallsBackToWindows1252() {
        TextDecoder.Decoded d = TextDecoder.decode(ST.getBytes(TextDecoder.WINDOWS_1252));
        assertThat(d.text()).isEqualTo(ST);
        assertThat(d.charset()).isEqualTo(TextDecoder.WINDOWS_1252);
    }

    @Test
    void emptyInput() {
        assertThat(TextDecoder.decode(new byte[0]).text()).isEmpty();
    }
}
