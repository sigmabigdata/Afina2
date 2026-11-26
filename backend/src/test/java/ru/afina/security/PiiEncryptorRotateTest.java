package ru.afina.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PiiEncryptor: rotate, hashPhone, версии ключей")
class PiiEncryptorRotateTest {

    @TempDir
    Path dir;

    private PiiEncryptor encryptor;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("pii.key.v1"),
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        Files.writeString(dir.resolve("pii.key.current"), "1");
        encryptor = new PiiEncryptor(dir.resolve("legacy").toString(), dir.toString());
    }

    @Test
    @DisplayName("getCurrentVersion: 1 после init")
    void currentVersionOne() {
        assertThat(encryptor.getCurrentVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("rotate: создаёт v2, обновляет .current, возвращает 2")
    void rotateCreatesV2() throws Exception {
        int v = encryptor.rotate();
        assertThat(v).isEqualTo(2);
        assertThat(encryptor.getCurrentVersion()).isEqualTo(2);
        assertThat(Files.exists(dir.resolve("pii.key.v2"))).isTrue();
        assertThat(Files.readString(dir.resolve("pii.key.current")).trim()).isEqualTo("2");
    }

    @Test
    @DisplayName("rotate: данные v1 остаются читаемыми после ротации")
    void oldDataReadableAfterRotate() throws Exception {
        String ct = encryptor.encrypt("secret");
        encryptor.rotate();
        assertThat(encryptor.decrypt(ct)).isEqualTo("secret");
    }

    @Test
    @DisplayName("rotate: новые данные шифруются v2")
    void newDataUsesV2() throws Exception {
        encryptor.rotate();
        String ct = encryptor.encrypt("new-secret");
        assertThat(ct).startsWith("v2:");
        assertThat(encryptor.decrypt(ct)).isEqualTo("new-secret");
    }

    @Test
    @DisplayName("hashPhone: 10 цифр → добавляет 7 в начало")
    void hashPhone10() {
        String h = encryptor.hashPhone("9991234567");
        assertThat(h).isEqualTo(encryptor.hash("79991234567"));
    }

    @Test
    @DisplayName("hashPhone: 11 цифр, начинается с 8 → меняет на 7")
    void hashPhone8Prefix() {
        String h = encryptor.hashPhone("89991234567");
        assertThat(h).isEqualTo(encryptor.hash("79991234567"));
    }

    @Test
    @DisplayName("hashPhone: 11 цифр, начинается с 7 → как есть")
    void hashPhone7Prefix() {
        assertThat(encryptor.hashPhone("79991234567"))
                .isEqualTo(encryptor.hash("79991234567"));
    }

    @Test
    @DisplayName("hashPhone: форматированный номер → нормализуется")
    void hashPhoneFormatted() {
        String a = encryptor.hashPhone("+7 (999) 123-45-67");
        String b = encryptor.hashPhone("79991234567");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("hashPhone: пусто/null → null")
    void hashPhoneEdge() {
        assertThat(encryptor.hashPhone(null)).isNull();
        assertThat(encryptor.hashPhone("")).isNull();
        assertThat(encryptor.hashPhone("abc")).isNull();
    }

    @Test
    @DisplayName("hash: разные регистры/пробелы → один hash")
    void hashNormalized() {
        assertThat(encryptor.hash("User@B.RU"))
                .isEqualTo(encryptor.hash("  user@b.ru  "));
    }

    @Test
    @DisplayName("hash: null → null")
    void hashNull() {
        assertThat(encryptor.hash(null)).isNull();
    }
}
