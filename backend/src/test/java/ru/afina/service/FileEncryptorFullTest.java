package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FileEncryptor: полная проверка шифрования AES-256-GCM")
class FileEncryptorFullTest {

    @TempDir Path dir;

    private FileEncryptor enc;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("file.key.v1"),
                Base64.getEncoder().encodeToString(new byte[32]));
        Files.writeString(dir.resolve("file.key.current"), "1");
        enc = new FileEncryptor(dir.resolve("legacy").toString(), dir.toString());
    }

    // ─── базовое ─────────────────────────────

    @Test
    @DisplayName("getCurrentVersion: 1 после init")
    void currentVersion() {
        assertThat(enc.getCurrentVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("encrypt/decrypt: roundtrip для 1 KB случайных байтов")
    void roundtrip1Kb() {
        byte[] data = new byte[1024];
        new SecureRandom().nextBytes(data);

        var encrypted = enc.encrypt(data);
        assertThat(encrypted.version()).isEqualTo(1);
        assertThat(encrypted.ivBase64()).isNotBlank();
        assertThat(encrypted.bytes()).hasSizeGreaterThanOrEqualTo(data.length + 12 + 16);

        assertThat(enc.decrypt(encrypted.bytes(), 1)).isEqualTo(data);
    }

    @Test
    @DisplayName("encrypt/decrypt: два шифрования одного plaintext → разные IV и ciphertext")
    void ivRandom() {
        byte[] data = "hello world".getBytes();
        var e1 = enc.encrypt(data);
        var e2 = enc.encrypt(data);

        assertThat(e1.ivBase64()).isNotEqualTo(e2.ivBase64());
        assertThat(e1.bytes()).isNotEqualTo(e2.bytes());
    }

    // ─── ошибки ────────────────────────────

    @Test
    @DisplayName("decrypt: неизвестная версия → IllegalState, причина 'Нет ключа v99'")
    void decryptUnknownVersion() {
        var e = enc.encrypt(new byte[]{1, 2, 3});
        // В проде throw new IllegalStateException("Нет ключа vN") завёрнут
        // в catch (Exception e) → "Ошибка расшифрования" (cause = "Нет ключа vN").
        assertThatThrownBy(() -> enc.decrypt(e.bytes(), 99))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ошибка расшифрования")
                .hasCauseInstanceOf(IllegalStateException.class)
                .getCause()
                .hasMessageContaining("Нет ключа v99");
    }

    @Test
    @DisplayName("decrypt: слишком короткий input → IllegalArgument")
    void decryptTooShort() {
        assertThatThrownBy(() -> enc.decrypt(new byte[5], 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("decrypt: подменённый байт → IllegalState (AEAD tag)")
    void decryptTampered() {
        var e = enc.encrypt("secret data".getBytes());
        e.bytes()[20] ^= 0x01; // порча ciphertext

        assertThatThrownBy(() -> enc.decrypt(e.bytes(), 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("decrypt (без версии) → использует v1")
    void decryptNoVersion() {
        var e = enc.encrypt("payload".getBytes(), 1);
        assertThat(enc.decrypt(e.bytes())).isEqualTo("payload".getBytes());
    }

    // ─── ротация ────────────────────────────

    @Test
    @DisplayName("rotate: v1 → v2, .current обновлён, старые данные читаются")
    void rotate() throws Exception {
        var old = enc.encrypt("old".getBytes(), 1);
        int v = enc.rotate();
        assertThat(v).isEqualTo(2);
        assertThat(enc.getCurrentVersion()).isEqualTo(2);
        assertThat(Files.readString(dir.resolve("file.key.current")).trim()).isEqualTo("2");

        // старые данные v1 всё ещё расшифровываются
        assertThat(enc.decrypt(old.bytes(), 1)).isEqualTo("old".getBytes());

        // новые — v2
        var fresh = enc.encrypt("fresh".getBytes());
        assertThat(fresh.version()).isEqualTo(2);
        assertThat(enc.decrypt(fresh.bytes(), 2)).isEqualTo("fresh".getBytes());
    }

    // ─── Encrypted record ──────────────────────

    @Test
    @DisplayName("Encrypted: equals/hashCode/toString")
    void encryptedRecord() {
        byte[] b = {1, 2, 3};
        var a1 = new FileEncryptor.Encrypted(b, "iv", 1);
        var a2 = new FileEncryptor.Encrypted(new byte[]{1, 2, 3}, "iv", 1);
        var a3 = new FileEncryptor.Encrypted(new byte[]{1, 2, 4}, "iv", 1);

        assertThat(a1).isEqualTo(a2).hasSameHashCodeAs(a2).isNotEqualTo(a3);
        assertThat(a1.toString()).contains("bytes=3B", "iv=iv", "v1");
    }

    // ─── битые ключи ──────────────────────────

    @Test
    @DisplayName("loadKey: ключ не 32 байта → IllegalState при init")
    void badKeySize() throws Exception {
        Path d2 = Files.createTempDirectory("bad-key");
        Files.writeString(d2.resolve("file.key.v1"),
                Base64.getEncoder().encodeToString(new byte[16]));
        Files.writeString(d2.resolve("file.key.current"), "1");

        assertThatThrownBy(() -> new FileEncryptor(d2.resolve("legacy").toString(), d2.toString()))
                .isInstanceOf(IllegalStateException.class);
    }
}
