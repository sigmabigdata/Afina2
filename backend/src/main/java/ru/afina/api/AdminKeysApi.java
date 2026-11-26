package ru.afina.api;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.entity.User;
import ru.afina.service.AuditService;
import ru.afina.service.KeysService;
import ru.afina.util.FileNames;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Админский API ключей шифрования (file.key / pii.key).
 *
 * <p>Повторяет раздел {@code /admin/keys} старой панели: состояние ключей,
 * выгрузка всех версий в ZIP под паролем (AES-256, zip4j) и ротация.
 * Все методы требуют роль {@code ROLE_ADMIN}.</p>
 *
 * <p>Пароль от ZIP передаётся только в теле POST-запроса: он не попадает
 * в access-лог, Referer и историю браузера. Сам пароль в приложении
 * не хранится — при потере архив не открыть.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/keys")
public class AdminKeysApi {

    private static final Logger log = LoggerFactory.getLogger(AdminKeysApi.class);

    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 256;

    /** Версионные файлы ключей: file.key.vN и pii.key.vN. */
    private static final String KEY_FILE_PATTERN = "(file|pii)\\.key\\.v\\d+";
    private static final String README_NAME = "README.txt";

    private final KeysService keys;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public AdminKeysApi(KeysService keys, CurrentUser currentUser, AuditService audit) {
        this.keys = keys;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ═══════════════════════════════════════════════════════
    //  Состояние ключей
    // ═══════════════════════════════════════════════════════

    /** Состояние ключей: файлы, дата последней выгрузки, версии. */
    @GetMapping
    public Map<String, Object> list(Authentication auth) {
        currentUser.requireAdmin(auth);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keys", keys.listAll());
        result.put("lastBackupAt", keys.getLastBackupAt());
        result.put("downloadCount", keys.getDownloadCount());
        result.put("daysSinceLastBackup", keys.daysSinceLastBackup());
        result.put("piiVersion", keys.getPiiCurrentVersion());
        result.put("fileVersion", keys.getFileCurrentVersion());
        result.put("minPasswordLength", MIN_PASSWORD_LENGTH);
        return result;
    }

    // ═══════════════════════════════════════════════════════
    //  Выгрузка ZIP под паролем
    // ═══════════════════════════════════════════════════════

    /**
     * Скачать ZIP со всеми версиями ключей, защищённый паролем (AES-256).
     *
     * <p>В архив попадают все версии {@code file.key.vN} и {@code pii.key.vN}:
     * старые данные зашифрованы старыми ключами, без них восстановление неполное.</p>
     */
    @PostMapping("/download")
    public ResponseEntity<byte[]> download(@RequestBody(required = false) DownloadRequest request,
                                           Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        if (request == null) {
            throw new IllegalArgumentException("Пароль не задан");
        }
        String password = request.password();
        validatePassword(password, request.confirmPassword());

        Path keysDir = keys.getKeysDir();
        List<Path> keyFiles = listKeyFiles(keysDir);
        if (keyFiles.isEmpty()) {
            throw new IllegalStateException("Файлы ключей недоступны: " + keysDir);
        }

        byte[] zipBytes;
        try {
            zipBytes = buildEncryptedZip(keyFiles, password);
        } catch (IOException e) {
            log.error("Не удалось подготовить архив ключей", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Ошибка подготовки архива ключей: " + e.getMessage());
        }

        String zipName = "afina2-keys-" + LocalDate.now() + ".zip";
        keys.recordBackupDownload(admin.getEmail());
        audit.keysDownload(admin.getEmail(), zipName);
        log.warn("Архив ключей (пароль, AES-256) выгружен администратором ({} байт)", zipBytes.length);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, FileNames.attachment(zipName))
                .body(zipBytes);
    }

    /** Проверка пароля: не короче 12 символов и совпадает с подтверждением. */
    private static void validatePassword(String password, String confirmPassword) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Пароль не задан");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Пароль должен быть не короче " + MIN_PASSWORD_LENGTH + " символов");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Пароль слишком длинный (макс. " + MAX_PASSWORD_LENGTH + " символов)");
        }
        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Пароли не совпадают");
        }
    }

    /** Все версии ключей из каталога (отсортированы по имени). */
    private static List<Path> listKeyFiles(Path keysDir) {
        try (var stream = Files.list(keysDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().matches(KEY_FILE_PATTERN))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Каталог ключей недоступен: " + keysDir);
        }
    }

    /**
     * Создаёт защищённый паролем ZIP в памяти (zip4j работает с файлами,
     * поэтому архив собирается во временном каталоге и удаляется после чтения).
     */
    private static byte[] buildEncryptedZip(List<Path> keyFiles, String password) throws IOException {
        Path tmpDir = createSecureTempDir();
        try {
            Path tmpZip = tmpDir.resolve("afina2-keys.zip");
            char[] secret = password.toCharArray();
            try {
                ZipParameters params = new ZipParameters();
                params.setCompressionMethod(CompressionMethod.DEFLATE);
                params.setEncryptFiles(true);
                params.setEncryptionMethod(EncryptionMethod.AES);
                params.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);

                try (ZipFile zip = new ZipFile(tmpZip.toFile(), secret)) {
                    for (Path key : keyFiles) {
                        zip.addFile(key.toFile(), params);
                    }
                    // README.txt — без пароля, чтобы инструкции были видны сразу.
                    Path readme = tmpDir.resolve(README_NAME);
                    Files.writeString(readme, readmeText(), StandardCharsets.UTF_8);
                    ZipParameters plain = new ZipParameters();
                    plain.setCompressionMethod(CompressionMethod.DEFLATE);
                    plain.setEncryptFiles(false);
                    zip.addFile(readme.toFile(), plain);
                }
            } finally {
                Arrays.fill(secret, '\0');
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Files.copy(tmpZip, buffer);
            return buffer.toByteArray();
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    /** Временный каталог только для владельца (0700, защита от подглядывания). */
    private static Path createSecureTempDir() throws IOException {
        Path dir = Files.createTempDirectory("afina2-keys-");
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            // Не-POSIX файловая система: права каталога задаёт ОС.
            log.debug("POSIX-права для временного каталога недоступны: {}", e.getMessage());
        }
        return dir;
    }

    /** Рекурсивное удаление временного каталога. */
    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            List<Path> paths = stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Не удалось удалить {}: {}", path, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Очистка временного каталога не удалась: {}", e.getMessage());
        }
    }

    /** Инструкция, которую человек прочитает даже без пароля к архиву. */
    private static String readmeText() {
        return """
                Афина · Бэкап ключей шифрования
                Скачано: %s

                В архиве:
                - file.key.vN — AES-256-GCM для файлов документов
                - pii.key.vN  — AES-256-GCM для персональных данных (email, телефон, ФИО)

                ⚠️  Без этих ключей восстановить данные невозможно.
                ⚠️  Храните архив в защищённом месте (1Password, сейф).
                ⚠️  Пароль от ZIP не хранится в приложении — если потеряете, архив не открыть.
                ⚠️  Никогда не пересылайте архив и пароль в одном канале.

                Файлы .key внутри защищены AES-256 (zip4j).
                Для распаковки: 7z x afina2-keys-*.zip   (или zip4j, WinRAR 5+)
                """.formatted(LocalDate.now());
    }

    // ═══════════════════════════════════════════════════════
    //  Ротация ключей
    // ═══════════════════════════════════════════════════════

    /** Ротация ключа персональных данных (PII). */
    @PostMapping("/rotate/pii")
    public Map<String, Object> rotatePii(Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        int version;
        try {
            version = keys.rotatePii();
        } catch (IOException e) {
            log.error("Ротация ключа PII не удалась", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Ошибка ротации ключа PII: " + e.getMessage());
        }
        audit.event("KEYS_ROTATE_PII", "SUCCESS", admin.getEmail(),
                "KEYS", "pii", null, "new version v" + version);
        log.warn("Ключ PII ротирован администратором → v{}", version);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", "pii");
        result.put("version", version);
        result.put("message", "Ключ PII ротирован → v" + version
                + " (старые данные остаются расшифровываемыми)");
        return result;
    }

    /** Ротация ключа файлов документов. */
    @PostMapping("/rotate/file")
    public Map<String, Object> rotateFile(Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        int version;
        try {
            version = keys.rotateFile();
        } catch (IOException e) {
            log.error("Ротация ключа файлов не удалась", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Ошибка ротации ключа файлов: " + e.getMessage());
        }
        audit.event("KEYS_ROTATE_FILE", "SUCCESS", admin.getEmail(),
                "KEYS", "file", null, "new version v" + version);
        log.warn("Ключ файлов ротирован администратором → v{}", version);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", "file");
        result.put("version", version);
        result.put("message", "Ключ файлов ротирован → v" + version
                + " (старые файлы остаются читаемыми)");
        return result;
    }

    // ═══════════════════════════════════════════════════════
    //  DTO запроса
    // ═══════════════════════════════════════════════════════

    /** Тело запроса на выгрузку архива с ключами. */
    public record DownloadRequest(String password, String confirmPassword) {
    }
}
