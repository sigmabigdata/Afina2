package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.security.PiiEncryptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KeysService: инспекция ключей + ротация + бэкап-статистика")
class KeysServiceTest {

    @Mock private SettingsService settings;
    @Mock private PiiEncryptor piiEncryptor;
    @Mock private FileEncryptor fileEncryptor;

    @TempDir Path tmpDir;

    private KeysService service;
    private Path keysDir;
    private Path fileKey;
    private Path piiKey;

    @BeforeEach
    void setUp() throws Exception {
        keysDir = tmpDir.resolve("keys");
        Files.createDirectories(keysDir);
        fileKey = keysDir.resolve("file.key.v1");
        piiKey = keysDir.resolve("pii.key.v1");
        service = new KeysService(keysDir.toString(), settings, piiEncryptor, fileEncryptor);
    }

    private void stubVersion1() {
        when(fileEncryptor.getCurrentVersion()).thenReturn(1);
        when(piiEncryptor.getCurrentVersion()).thenReturn(1);
    }

    private void writeValidKey(Path p) throws Exception {
        Files.writeString(p, Base64.getEncoder().encodeToString(new byte[32]));
    }

    // ─── getLastBackupAt ─────────────────────

    @Test
    @DisplayName("getLastBackupAt: пусто → null")
    void lastBackupEmpty() {
        when(settings.getOrDefault("keys.last_backup_at", "")).thenReturn("");
        assertThat(service.getLastBackupAt()).isNull();
    }

    @Test
    @DisplayName("getLastBackupAt: валидный ISO → Instant")
    void lastBackupValid() {
        String iso = "2024-03-15T10:00:00Z";
        when(settings.getOrDefault("keys.last_backup_at", "")).thenReturn(iso);
        assertThat(service.getLastBackupAt()).isEqualTo(Instant.parse(iso));
    }

    @Test
    @DisplayName("getLastBackupAt: битый ISO → null")
    void lastBackupBroken() {
        when(settings.getOrDefault("keys.last_backup_at", "")).thenReturn("garbage");
        assertThat(service.getLastBackupAt()).isNull();
    }

    // ─── getDownloadCount / daysSinceLastBackup ──

    @Test
    @DisplayName("getDownloadCount: пусто → 0")
    void downloadCountEmpty() {
        when(settings.getOrDefault("keys.download_count", "0")).thenReturn("0");
        assertThat(service.getDownloadCount()).isZero();
    }

    @Test
    @DisplayName("getDownloadCount: 42 → 42")
    void downloadCountValue() {
        when(settings.getOrDefault("keys.download_count", "0")).thenReturn("42");
        assertThat(service.getDownloadCount()).isEqualTo(42L);
    }

    @Test
    @DisplayName("daysSinceLastBackup: null last → -1")
    void daysSinceNull() {
        when(settings.getOrDefault("keys.last_backup_at", "")).thenReturn("");
        assertThat(service.daysSinceLastBackup()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("daysSinceLastBackup: 10 дней назад → 10")
    void daysSinceTen() {
        String iso = Instant.now().minusSeconds(10L * 86400).toString();
        when(settings.getOrDefault("keys.last_backup_at", "")).thenReturn(iso);
        assertThat(service.daysSinceLastBackup()).isEqualTo(10L);
    }

    // ─── recordBackupDownload ────────────────

    @Test
    @DisplayName("recordBackupDownload: set last_backup_at + download_count")
    void recordDownload() {
        when(settings.getOrDefault("keys.download_count", "0")).thenReturn("5");

        service.recordBackupDownload("admin");

        verify(settings).set(org.mockito.ArgumentMatchers.eq("keys.last_backup_at"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("admin"));
        verify(settings).set("keys.download_count", "6", "admin");
    }

    // ─── listAll / inspect ────────────────────

    @Test
    @DisplayName("listAll: оба файла отсутствуют → exists=false, problem")
    void listAllMissing() {
        stubVersion1();
        var list = service.listAll();
        assertThat(list).hasSize(2);
        assertThat(list.get(0).name()).isEqualTo("file.key (v1)");
        assertThat(list.get(0).exists()).isFalse();
        assertThat(list.get(0).problem()).contains("отсутствует");
        assertThat(list.get(1).name()).isEqualTo("pii.key (v1)");
    }

    @Test
    @DisplayName("listAll: файл не 44 байта → problem про размер")
    void listAllWrongSize() throws Exception {
        stubVersion1();
        Files.writeString(fileKey, "short");

        var list = service.listAll();
        assertThat(list.get(0).exists()).isTrue();
        assertThat(list.get(0).sizeBytes()).isNotEqualTo(44);
        assertThat(list.get(0).problem()).contains("44");
    }

    @Test
    @DisplayName("listAll: валидные ключи 44 байта → exists=true")
    void listAllClean() throws Exception {
        stubVersion1();
        writeValidKey(fileKey);
        writeValidKey(piiKey);

        var list = service.listAll();
        assertThat(list.get(0).sizeBytes()).isEqualTo(44L);
        assertThat(list.get(0).exists()).isTrue();
        assertThat(list.get(1).exists()).isTrue();
    }

    // ─── ротация / версии (делегирование) ────

    @Test
    @DisplayName("rotatePii: делегирует в piiEncryptor.rotate()")
    void rotatePiiDelegate() throws Exception {
        when(piiEncryptor.rotate()).thenReturn(3);
        assertThat(service.rotatePii()).isEqualTo(3);
    }

    @Test
    @DisplayName("rotateFile: делегирует в fileEncryptor.rotate()")
    void rotateFileDelegate() throws Exception {
        when(fileEncryptor.rotate()).thenReturn(4);
        assertThat(service.rotateFile()).isEqualTo(4);
    }

    @Test
    @DisplayName("getPiiCurrentVersion / getFileCurrentVersion — делегируют")
    void currentVersions() {
        when(piiEncryptor.getCurrentVersion()).thenReturn(2);
        when(fileEncryptor.getCurrentVersion()).thenReturn(5);
        assertThat(service.getPiiCurrentVersion()).isEqualTo(2);
        assertThat(service.getFileCurrentVersion()).isEqualTo(5);
    }

    // ─── геттеры путей ────────────────────────

    @Test
    @DisplayName("getFileKeyPath / getPiiKeyPath — версионные пути")
    void paths() {
        stubVersion1();
        assertThat(service.getFileKeyPath()).isEqualTo(keysDir.resolve("file.key.v1"));
        assertThat(service.getPiiKeyPath()).isEqualTo(keysDir.resolve("pii.key.v1"));
        assertThat(service.getKeysDir()).isEqualTo(keysDir);
    }
}
