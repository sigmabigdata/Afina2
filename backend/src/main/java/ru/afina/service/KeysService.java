package ru.afina.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Информация о ключах шифрования (file.key, pii.key).
 * Только для чтения — никаких изменений отсюда.
 */
@Service
public class KeysService {

    private final Path keysDir;
    private final SettingsService settings;

    private final ru.afina.security.PiiEncryptor piiEncryptor;
    private final FileEncryptor fileEncryptor;

    public KeysService(
            @Value("${app.keys-dir:/opt/afinaz/secrets/keys}") String keysDir,
            SettingsService settings,
            ru.afina.security.PiiEncryptor piiEncryptor,
            FileEncryptor fileEncryptor) {
        this.keysDir = Path.of(keysDir);
        this.settings = settings;
        this.piiEncryptor = piiEncryptor;
        this.fileEncryptor = fileEncryptor;
    }

    public record KeyInfo(
            String name,
            String description,
            String path,
            boolean exists,
            long sizeBytes,
            String modifiedAt,
            String permissions,
            String owner,
            String problem
    ) {}

    /** Дата последнего скачивания бэкапа (ISO Instant или null). */
    public Instant getLastBackupAt() {
        String v = settings.getOrDefault("keys.last_backup_at", "");
        if (v.isBlank()) return null;
        try { return Instant.parse(v); } catch (Exception e) { return null; }
    }

    public long getDownloadCount() {
        return Long.parseLong(settings.getOrDefault("keys.download_count", "0"));
    }

    public long daysSinceLastBackup() {
        Instant last = getLastBackupAt();
        if (last == null) return -1;
        return ChronoUnit.DAYS.between(last, Instant.now());
    }

    /** Записать факт скачивания бэкапа. */
    public void recordBackupDownload(String by) {
        settings.set("keys.last_backup_at", Instant.now().toString(), by);
        long count = getDownloadCount() + 1;
        settings.set("keys.download_count", String.valueOf(count), by);
    }

    public List<KeyInfo> listAll() {
        List<KeyInfo> result = new ArrayList<>();
        int fileVer = fileEncryptor.getCurrentVersion();
        int piiVer = piiEncryptor.getCurrentVersion();
        result.add(inspect("file.key (v" + fileVer + ")",
                "Файлы документов (AES-256-GCM)", keysDir.resolve("file.key.v" + fileVer)));
        result.add(inspect("pii.key (v" + piiVer + ")",
                "Персональные данные: email, телефон, ФИО в документах (AES-256-GCM)",
                keysDir.resolve("pii.key.v" + piiVer)));
        return result;
    }

    private KeyInfo inspect(String name, String description, Path path) {
        if (!Files.exists(path)) {
            return new KeyInfo(name, description, path.toString(),
                    false, 0, "—", "—", "—", "Файл отсутствует");
        }
        try {
            long size = Files.size(path);
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            String modifiedAt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .withZone(ZoneId.systemDefault()).format(modified);

            PosixInfo posix = inspectPosix(path);
            String problem = posix.problem();

            // Версионный ключ — 32 байта в Base64 без переноса = 44 символа.
            if (size != 44) {
                problem = (problem != null ? problem + "; " : "")
                        + "Размер не 44 байт (текущий: " + size + ")";
            }

            return new KeyInfo(name, description, path.toString(),
                    true, size, modifiedAt, posix.perms(), posix.owner(), problem);
        } catch (IOException e) {
            return new KeyInfo(name, description, path.toString(),
                    false, 0, "—", "—", "—", "Ошибка чтения: " + e.getMessage());
        }
    }

    /** Результат чтения POSIX-атрибутов файла. */
    private record PosixInfo(String perms, String owner, String problem) {}

    /** Читает права и владельца. На не-POSIX системах возвращает прочерки. */
    private PosixInfo inspectPosix(Path path) {
        try {
            PosixFileAttributes attrs = Files.readAttributes(path, PosixFileAttributes.class);
            String perms = PosixFilePermissions.toString(attrs.permissions());
            String owner = attrs.owner().getName() + ":" + attrs.group().getName();
            String problem = "rw-------".equals(perms)
                    ? null
                    : "Права не 600 (текущие: " + perms + ")";
            return new PosixInfo(perms, owner, problem);
        } catch (UnsupportedOperationException e) {
            return new PosixInfo("—", "—", null);
        } catch (Exception e) {
            return new PosixInfo("—", "—", "Ошибка чтения атрибутов: " + e.getMessage());
        }
    }

    public Path getFileKeyPath() {
        return keysDir.resolve("file.key.v" + fileEncryptor.getCurrentVersion());
    }
    public Path getPiiKeyPath() {
        return keysDir.resolve("pii.key.v" + piiEncryptor.getCurrentVersion());
    }

    /** Каталог версионных ключей (для бэкапа всех версий). */
    public Path getKeysDir() { return keysDir; }
    // ==================== Ротация ====================

    public int rotatePii() throws java.io.IOException {
        return piiEncryptor.rotate();
    }

    public int rotateFile() throws java.io.IOException {
        return fileEncryptor.rotate();
    }

    public int getPiiCurrentVersion() {
        return piiEncryptor.getCurrentVersion();
    }

    public int getFileCurrentVersion() {
        return fileEncryptor.getCurrentVersion();
    }

}
