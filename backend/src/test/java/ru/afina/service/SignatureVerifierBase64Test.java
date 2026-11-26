package ru.afina.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SignatureVerifier: base64 decode + extractCn")
class SignatureVerifierBase64Test {

    private static SignatureVerifier verifier;

    @BeforeAll
    static void setUp() {
        // Пути не используются в decodeBase64Tolerant / extractCn
        verifier = new SignatureVerifier("/tmp/certs", "/tmp/crls", null);
    }

    // ─── decodeBase64Tolerant ──────────────────────────

    @Test
    @DisplayName("decodeBase64Tolerant: стандартный base64")
    void decodeStandard() {
        byte[] out = SignatureVerifier.decodeBase64Tolerant("SGVsbG8=");
        assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("Hello");
    }

    @Test
    @DisplayName("decodeBase64Tolerant: без padding")
    void decodeWithoutPadding() {
        byte[] out = SignatureVerifier.decodeBase64Tolerant("SGVsbG8");
        assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("Hello");
    }

    @Test
    @DisplayName("decodeBase64Tolerant: PEM-обёртка")
    void decodePem() {
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + "SGVsbG8=\n"
                + "-----END CERTIFICATE-----";
        byte[] out = SignatureVerifier.decodeBase64Tolerant(pem);
        assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("Hello");
    }

    @Test
    @DisplayName("decodeBase64Tolerant: URL-safe (- и _)")
    void decodeUrlSafe() {
        // "Hello" в base64 = "SGVsbG8="; закодируем с другими символами
        // base64 от 0xFB 0xFF: "+/8=" → "-_8=" в URL-safe
        // Проще: base64 от "???" = "Pz8/"; не содержит - _
        // Возьмём реальный url-safe: "test" через -_ замену
        // base64 "AAEC" без проблем; используем строку с - и _:
        // base64 от байтов [0xFB,0xFF] = "+/8=" → URL-safe "-_8="
        byte[] out = SignatureVerifier.decodeBase64Tolerant("-_8=");
        assertThat(out).containsExactly((byte) 0xFB, (byte) 0xFF);
    }

    @Test
    @DisplayName("decodeBase64Tolerant: whitespace удаляется")
    void decodeWhitespace() {
        byte[] out = SignatureVerifier.decodeBase64Tolerant("S G V s\nb G 8 =");
        assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("Hello");
    }

    @Test
    @DisplayName("decodeBase64Tolerant: невалидные символы вырезаются")
    void decodeGarbageChars() {
        // "!" и "?" удаляются → "SGVsbG8="
        byte[] out = SignatureVerifier.decodeBase64Tolerant("!SGVsbG8=??");
        assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("Hello");
    }

    @Test
    @DisplayName("decodeBase64Tolerant: длина %4 == 1 → IAE")
    void decodeBadLength() {
        assertThatThrownBy(() -> SignatureVerifier.decodeBase64Tolerant("ABCDE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Некорректная длина");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("decodeBase64Tolerant: пусто/пробелы → IAE 'empty base64'")
    void decodeEmpty(String input) {
        assertThatThrownBy(() -> SignatureVerifier.decodeBase64Tolerant(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty base64");
    }

    @Test
    @DisplayName("decodeBase64Tolerant: null → IAE")
    void decodeNull() {
        assertThatThrownBy(() -> SignatureVerifier.decodeBase64Tolerant(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── extractCn ─────────────────────────────────────

    @Test
    @DisplayName("extractCn: стандартный X500")
    void extractCnStandard() {
        assertThat(verifier.extractCn("CN=Иванов Иван, O=Bank, C=RU"))
                .isEqualTo("Иванов Иван");
    }

    @Test
    @DisplayName("extractCn: case-insensitive")
    void extractCnCaseInsensitive() {
        assertThat(verifier.extractCn("cn=иванов, o=bank"))
                .isEqualTo("иванов");
    }

    @Test
    @DisplayName("extractCn: снимает кавычки")
    void extractCnQuoted() {
        assertThat(verifier.extractCn("CN=\"Иванов Иван\", O=Bank"))
                .isEqualTo("Иванов Иван");
    }

    @Test
    @DisplayName("extractCn: без CN → возвращает как есть")
    void extractCnNoCn() {
        String x500 = "O=Bank, C=RU";
        assertThat(verifier.extractCn(x500)).isEqualTo(x500);
    }

    @Test
    @DisplayName("extractCn: null → пустая строка")
    void extractCnNull() {
        assertThat(verifier.extractCn(null)).isEmpty();
    }
}
