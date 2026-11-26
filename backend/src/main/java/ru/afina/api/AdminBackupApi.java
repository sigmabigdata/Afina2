package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.entity.User;
import ru.afina.service.AuditService;
import ru.afina.service.BackupService;
import ru.afina.util.FileNames;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Админский API резервных копий БД.
 *
 * <p>Повторяет раздел {@code /admin/system} старой панели: список бэкапов,
 * создание, скачивание, удаление и восстановление. Все методы требуют роль
 * {@code ROLE_ADMIN}.</p>
 *
 * <p>Имя файла бэкапа приходит из URL, поэтому дополнительно проверяется
 * на path traversal: запрещены {@code /}, {@code \} и {@code ..}, а также любые
 * символы кроме латиницы, цифр, точки, дефиса и подчёркивания.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/backups")
public class AdminBackupApi {

    private static final Logger log = LoggerFactory.getLogger(AdminBackupApi.class);

    /** Допустимое имя файла бэкапа: без слэшей, точек-переходов и спецсимволов. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final int MAX_NAME_LENGTH = 255;

    private final BackupService backups;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public AdminBackupApi(BackupService backups, CurrentUser currentUser, AuditService audit) {
        this.backups = backups;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ═══════════════════════════════════════════════════════
    //  Список / создание
    // ═══════════════════════════════════════════════════════

    /** Список резервных копий, новые сверху. */
    @GetMapping
    public List<Map<String, Object>> list(Authentication auth) {
        currentUser.requireAdmin(auth);
        List<Map<String, Object>> result = new ArrayList<>();
        for (BackupService.BackupFile file : backups.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", file.name());
            item.put("sizeBytes", file.size());
            item.put("sizePretty", file.sizePretty());
            item.put("createdAt", file.createdAt());
            item.put("encrypted", file.encrypted());
            result.add(item);
        }
        return result;
    }

    /** Создать новый бэкап БД. Возвращает имя созданного файла. */
    @PostMapping
    public Map<String, Object> create(Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        String name;
        try {
            name = backups.create();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Создание бэкапа прервано");
        } catch (IOException e) {
            log.error("Не удалось создать бэкап", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось создать бэкап: " + e.getMessage());
        }
        audit.backupCreate(admin.getEmail(), name);
        log.warn("Бэкап создан администратором: {}", name);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("created", true);
        result.put("message", "Бэкап создан: " + name);
        return result;
    }

    // ═══════════════════════════════════════════════════════
    //  Скачивание / удаление / восстановление
    // ═══════════════════════════════════════════════════════

    /** Скачать файл бэкапа. */
    @GetMapping("/{name}/download")
    public ResponseEntity<byte[]> download(@PathVariable String name, Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        String safeName = validatedName(name);
        byte[] data;
        try {
            data = backups.read(safeName);
        } catch (IOException e) {
            throw ioError(e);
        }
        audit.event("BACKUP_DOWNLOAD", "SUCCESS", admin.getEmail(),
                "SYSTEM", safeName, null, "Скачан файл бэкапа (" + data.length + " байт)");
        log.info("Бэкап скачан администратором: {} ({} байт)", safeName, data.length);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, FileNames.attachment(safeName))
                .body(data);
    }

    /** Удалить файл бэкапа. */
    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name, Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        String safeName = validatedName(name);
        try {
            backups.delete(safeName);
        } catch (IOException e) {
            throw ioError(e);
        }
        audit.event("BACKUP_DELETE", "SUCCESS", admin.getEmail(),
                "SYSTEM", safeName, null, "Удалён файл бэкапа");
        log.warn("Бэкап удалён администратором: {}", safeName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        result.put("name", safeName);
        result.put("message", "Бэкап удалён: " + safeName);
        return result;
    }

    /** Восстановить БД из бэкапа (перед восстановлением создаётся safety-бэкап). */
    @PostMapping("/{name}/restore")
    public Map<String, Object> restore(@PathVariable String name, Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        String safeName = validatedName(name);
        String safety;
        try {
            safety = backups.restore(safeName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Восстановление БД прервано");
        } catch (IOException e) {
            String message = e.getMessage() == null ? "ошибка файловой системы" : e.getMessage();
            HttpStatus status = message.startsWith("Файл не найден")
                    ? HttpStatus.NOT_FOUND
                    : HttpStatus.CONFLICT;
            throw new ResponseStatusException(status, "Не удалось восстановить БД: " + message);
        }
        audit.backupRestore(admin.getEmail(), safeName);
        log.warn("БД восстановлена из {} администратором, safety-бэкап: {}", safeName, safety);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("restored", true);
        result.put("name", safeName);
        result.put("safetyBackup", safety);
        result.put("message", "БД восстановлена из " + safeName
                + ". Safety-бэкап: " + safety + ". Перезапустите приложение!");
        return result;
    }

    // ═══════════════════════════════════════════════════════
    //  Хелперы
    // ═══════════════════════════════════════════════════════

    /**
     * Проверка имени файла бэкапа на path traversal.
     *
     * @throws IllegalArgumentException если имя пустое, слишком длинное или содержит
     *                                  недопустимые символы ({@code /}, {@code \}, {@code ..})
     */
    private static String validatedName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Имя файла бэкапа не задано");
        }
        String candidate = name.trim();
        if (candidate.contains("/") || candidate.contains("\\") || candidate.contains("..")) {
            throw new IllegalArgumentException("Недопустимое имя файла бэкапа: " + name);
        }
        if (candidate.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Слишком длинное имя файла бэкапа (макс. " + MAX_NAME_LENGTH + ")");
        }
        if (!SAFE_NAME.matcher(candidate).matches()) {
            throw new IllegalArgumentException("Недопустимое имя файла бэкапа: " + name);
        }
        return candidate;
    }

    /** Преобразует ошибку файловых операций в HTTP-статус API. */
    private static ResponseStatusException ioError(IOException e) {
        String message = e.getMessage() == null ? "ошибка файловой системы" : e.getMessage();
        HttpStatus status = message.startsWith("Файл не найден")
                ? HttpStatus.NOT_FOUND
                : HttpStatus.INTERNAL_SERVER_ERROR;
        return new ResponseStatusException(status, message);
    }
}
