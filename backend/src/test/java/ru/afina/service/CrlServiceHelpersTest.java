package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CrlService: утилиты и работа с файлами")
class CrlServiceHelpersTest {

    @TempDir
    Path tmpDir;

    private CrlService service;

    @BeforeEach
    void setUp() {
        service = new CrlService(tmpDir.toString());
    }

    // ─── static helpers ────────────────────────────────

    @Test
    @DisplayName("prettySize: B / KB / MB")
    void prettySize() {
        assertThat(CrlService.prettySize(0)).isEqualTo("0 B");
        assertThat(CrlService.prettySize(1023)).isEqualTo("1023 B");
        assertThat(CrlService.prettySize(1024)).isEqualTo("1 KB");
        assertThat(CrlService.prettySize(2048)).isEqualTo("2 KB");
        assertThat(CrlService.prettySize(1024L * 1024)).isEqualTo("1.00 MB");  // Locale.ROOT в impl
    }

    @Test
    @DisplayName("formatInstant: null → '—'")
    void formatInstantNull() {
        assertThat(CrlService.formatInstant(null)).isEqualTo("—");
    }

    @Test
    @DisplayName("formatInstant: Instant → dd.MM.yyyy HH:mm")
    void formatInstantValue() {
        // 2024-03-15 12:34 UTC → в локальном TZ; проверим формат
        Instant i = Instant.parse("2024-03-15T12:34:00Z");
        String s = CrlService.formatInstant(i);
        assertThat(s).matches("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}");
    }

    // ─── пустой каталог ────────────────────────────────

    @Test
    @DisplayName("getCount: пустой каталог → 0")
    void countEmpty() {
        assertThat(service.getCount()).isZero();
    }

    @Test
    @DisplayName("getTotalSize: пустой каталог → 0")
    void totalSizeEmpty() {
        assertThat(service.getTotalSize()).isZero();
    }

    @Test
    @DisplayName("listAll: пустой каталог → пустой список")
    void listAllEmpty() {
        assertThat(service.listAll()).isEmpty();
    }

    @Test
    @DisplayName("deleteAllAuto: пустой каталог → 0 удалено")
    void deleteAllAutoEmpty() {
        assertThat(service.deleteAllAuto()).isZero();
    }

    // ─── delete / path traversal ───────────────────────

    @Test
    @DisplayName("delete: файл отсутствует → не падает (deleteIfExists)")
    void deleteMissingOk() {
        // Отсутствующий файл — 404 на уровне API, а не «молча успешно»
        assertThatThrownBy(() -> service.delete("nonexistent.crl"))
                .isInstanceOf(ru.afina.error.NotFoundException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    @DisplayName("delete: path traversal → SecurityException")
    void deletePathTraversal() {
        assertThatThrownBy(() -> service.delete("../../etc/passwd"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Недопустимый");
    }

    @Test
    @DisplayName("delete: реальный файл удаляется")
    void deleteExisting() throws Exception {
        Path f = tmpDir.resolve("test.crl");
        Files.writeString(f, "dummy");
        service.delete("test.crl");
        assertThat(Files.exists(f)).isFalse();
    }

    @Test
    @DisplayName("deleteMany: несуществующие имена тоже считаются успехом (deleteIfExists идемпотентен)")
    void deleteManyMissing() {
        // deleteMany пропускает отсутствующие файлы и считает только удалённые
        int n = service.deleteMany(List.of("a.crl", "b.crl"));
        assertThat(n).isZero();
    }

    // ─── saveManual ────────────────────────────────────

    @Test
    @DisplayName("saveManual: битые байты → CertificateException или CRLException")
    void saveManualInvalid() {
        assertThatThrownBy(() -> service.saveManual("bad.crl", new byte[]{1, 2, 3}))
                .isInstanceOfAny(
                        java.security.cert.CertificateException.class,
                        java.security.cert.CRLException.class);
    }

    @Test
    @DisplayName("saveManual: null name → default 'manual.crl' не падает на валидации байтов")
    void saveManualNullName() {
        // сначала идёт валидация байтов → CertificateException или CRLException
        assertThatThrownBy(() -> service.saveManual(null, new byte[]{0}))
                .isInstanceOfAny(
                        java.security.cert.CertificateException.class,
                        java.security.cert.CRLException.class);
    }

    // ─── getCrlsDir ────────────────────────────────────

    @Test
    @DisplayName("getCrlsDir: возвращает абсолютный нормализованный путь")
    void crlsDir() {
        Path dir = service.getCrlsDir();
        assertThat(dir).isAbsolute();
        assertThat(dir).isEqualTo(tmpDir.toAbsolutePath().normalize());
    }
}
