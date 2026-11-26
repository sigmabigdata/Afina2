package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.afina.api.dto.CrlDto;
import ru.afina.service.CrlOnDemandService;
import ru.afina.service.CrlService;
import ru.afina.service.CrlStatusService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Админский REST API управления списками отзыва сертификатов (CRL).
 *
 * <p>Порт страницы {@code /admin/crl}: список файлов CRL с рассчитанным
 * статусом, сводка по каталогу, принудительное обновление по всем подписям,
 * ручная загрузка, удаление одного файла или всех автоматически
 * скачанных, а также отчёт о доверии и сроках действия.</p>
 *
 * <p>Все методы требуют роль {@code ROLE_ADMIN}; доступ дополнительно
 * ограничен IP-whitelist на уровне {@code SecurityConfig}.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/crl")
public class AdminCrlApi {

    private static final Logger log = LoggerFactory.getLogger(AdminCrlApi.class);

    /** Порог «истекает»: меньше 30 дней до nextUpdate. */
    private static final long EXPIRING_DAYS = 30;

    private static final String STATUS_OK = "ok";
    private static final String STATUS_EXPIRING = "expiring";
    private static final String STATUS_EXPIRED = "expired";

    private final CrlService crlService;
    private final CrlOnDemandService crlOnDemandService;
    private final CrlStatusService crlStatusService;
    private final CurrentUser currentUser;
    private final ApiMapper mapper;

    public AdminCrlApi(CrlService crlService,
                       CrlOnDemandService crlOnDemandService,
                       CrlStatusService crlStatusService,
                       CurrentUser currentUser,
                       ApiMapper mapper) {
        this.crlService = crlService;
        this.crlOnDemandService = crlOnDemandService;
        this.crlStatusService = crlStatusService;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    // ═══════════════════════════════════════════════════════
    //  Список и сводка
    // ═══════════════════════════════════════════════════════

    /**
     * Список CRL с рассчитанным статусом.
     *
     * <p>Статус: {@code expired} — срок истёк, {@code expiring} — истекает
     * в ближайшие 30 дней, {@code ok} — в остальных случаях.</p>
     */
    @GetMapping("")
    public List<CrlDto> list(Authentication auth) {
        currentUser.requireAdmin(auth);
        return crlService.listAll().stream()
                .map(c -> mapper.crl(c, statusOf(c.daysToExpiry())))
                .toList();
    }

    /**
     * Сводка по каталогу CRL: количество файлов, суммарный размер,
     * число истекающих и истёкших.
     */
    @GetMapping("/summary")
    public Map<String, Object> summary(Authentication auth) {
        currentUser.requireAdmin(auth);
        List<CrlService.CrlInfo> all = crlService.listAll();
        long expiring = all.stream()
                .filter(c -> STATUS_EXPIRING.equals(statusOf(c.daysToExpiry())))
                .count();
        long expired = all.stream()
                .filter(c -> STATUS_EXPIRED.equals(statusOf(c.daysToExpiry())))
                .count();
        long totalSize = crlService.getTotalSize();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", crlService.getCount());
        body.put("totalSize", totalSize);
        body.put("totalSizePretty", CrlService.prettySize(totalSize));
        body.put("expiring", expiring);
        body.put("expired", expired);
        return body;
    }

    // ═══════════════════════════════════════════════════════
    //  Обновление и загрузка
    // ═══════════════════════════════════════════════════════

    /**
     * Принудительно скачать CRL по всем подписям в БД (игнорируя TTL)
     * и удалить осиротевшие {@code auto-*.crl}.
     *
     * <p>Возвращает все поля {@link CrlOnDemandService.RefreshResult};
     * дополнительно продублированы короткие ключи {@code downloaded}
     * и {@code failed}.</p>
     */
    @PostMapping("/refresh")
    public Map<String, Object> refresh(Authentication auth) {
        var admin = currentUser.requireAdmin(auth);
        CrlOnDemandService.RefreshResult result =
                crlOnDemandService.refreshAll(admin.getEmail());
        if (result == null) {
            throw new IllegalStateException("Не удалось выполнить обновление CRL");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("downloaded", result.crlDownloaded());
        body.put("failed", result.crlFailed());
        body.put("deleted", result.crlDeleted());
        body.put("totalSignatures", result.totalSignatures());
        body.put("parsedSignatures", result.parsedSignatures());
        body.put("parseErrors", result.parseErrors());
        body.put("uniqueCerts", result.uniqueCerts());
        body.put("crlDownloaded", result.crlDownloaded());
        body.put("crlDeleted", result.crlDeleted());
        body.put("crlFailed", result.crlFailed());
        body.put("failures", result.failures() == null ? List.of() : result.failures());
        log.info("CRL refresh by {}: downloaded={}, failed={}, deleted={}",
                admin.getEmail(), result.crlDownloaded(),
                result.crlFailed(), result.crlDeleted());
        return body;
    }

    /**
     * Загрузить CRL вручную (multipart-поле {@code file}).
     *
     * <p>Имя файла очищается сервисом; содержимое проверяется на корректность
     * структуры CRL.</p>
     */
    @PostMapping(value = "", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
            @RequestPart(value = "file", required = false) MultipartFile file,
            Authentication auth) {
        var admin = currentUser.requireAdmin(auth);
        if (file == null) {
            throw new IllegalArgumentException("Не передан файл CRL (поле file)");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Файл пустой");
        }
        String originalName = file.getOriginalFilename();
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать файл: " + e.getMessage(), e);
        }
        try {
            crlService.saveManual(originalName, data);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить CRL: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Файл не является корректным CRL: "
                    + e.getMessage(), e);
        }
        log.info("CRL загружен вручную администратором {}: {} ({} байт)",
                admin.getEmail(), originalName, data.length);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uploaded", true);
        body.put("fileName", originalName);
        body.put("sizeBytes", (long) data.length);
        body.put("sizePretty", CrlService.prettySize(data.length));
        return body;
    }

    // ═══════════════════════════════════════════════════════
    //  Удаление
    // ═══════════════════════════════════════════════════════

    /** Удалить один файл CRL по имени. */
    @DeleteMapping("/{fileName}")
    public Map<String, Object> delete(@PathVariable String fileName, Authentication auth) {
        var admin = currentUser.requireAdmin(auth);
        String safeName = safeFileName(fileName);
        try {
            crlService.delete(safeName);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось удалить CRL: " + e.getMessage(), e);
        } catch (SecurityException e) {
            throw new IllegalArgumentException("Недопустимое имя файла CRL", e);
        }
        log.info("CRL удалён администратором {}: {}", admin.getEmail(), safeName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", true);
        body.put("fileName", safeName);
        return body;
    }

    /** Удалить все автоматически скачанные CRL ({@code auto-*.crl}). */
    @DeleteMapping("/auto")
    public Map<String, Object> deleteAllAuto(Authentication auth) {
        var admin = currentUser.requireAdmin(auth);
        int deleted = crlService.deleteAllAuto();
        log.info("Удалено auto-*.crl: {} (by {})", deleted, admin.getEmail());
        return Map.of("deleted", deleted);
    }

    // ═══════════════════════════════════════════════════════
    //  Отчёт о состоянии
    // ═══════════════════════════════════════════════════════

    /**
     * Отчёт {@link CrlStatusService#report()}: элементы (срок действия,
     * издатель, проверка подписи) и сводка.
     */
    @GetMapping("/status")
    public Map<String, Object> status(Authentication auth) {
        currentUser.requireAdmin(auth);
        CrlStatusService.Report report = crlStatusService.report();
        if (report == null) {
            throw new IllegalStateException("Отчёт по CRL недоступен");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", report.items() == null ? List.of()
                : report.items().stream().map(AdminCrlApi::statusItem).toList());
        body.put("summary", summaryMap(report.summary()));
        return body;
    }

    // ═══════════════════════════════════════════════════════
    //  Вспомогательные методы
    // ═══════════════════════════════════════════════════════

    /** Статус CRL по числу дней до {@code nextUpdate}. */
    private static String statusOf(long daysToExpiry) {
        if (daysToExpiry < 0) return STATUS_EXPIRED;
        if (daysToExpiry < EXPIRING_DAYS) return STATUS_EXPIRING;
        return STATUS_OK;
    }

    /**
     * Защита от path traversal: имя файла CRL не должно содержать
     * разделителей пути и переходов вверх ({@code /}, {@code \}, {@code ..}).
     */
    static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Не указано имя файла CRL");
        }
        String name = fileName.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..")
                || name.startsWith(".") || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Недопустимое имя файла CRL");
        }
        return name;
    }

    private static Map<String, Object> statusItem(CrlStatusService.CrlStatusInfo i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fileName", i.fileName());
        m.put("issuerCn", i.issuerCn());
        m.put("thisUpdate", i.thisUpdate());
        m.put("nextUpdate", i.nextUpdate());
        m.put("sizePretty", i.sizePretty());
        m.put("signatureVerified", i.signatureVerified());
        m.put("status", i.status() == null
                ? STATUS_OK : i.status().name().toLowerCase(Locale.ROOT));
        m.put("note", i.note());
        return m;
    }

    private static Map<String, Object> summaryMap(CrlStatusService.Summary s) {
        if (s == null) {
            return Map.of("total", 0, "ok", 0, "expiring", 0, "expired", 0, "untrusted", 0);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", s.total());
        m.put("ok", s.ok());
        m.put("expiring", s.expiring());
        m.put("expired", s.expired());
        m.put("untrusted", s.untrusted());
        return m;
    }
}
