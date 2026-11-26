package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.afina.api.dto.AuditEventDto;
import ru.afina.api.dto.PageDto;
import ru.afina.entity.AuditEvent;
import ru.afina.repository.AuditEventRepository;
import ru.afina.service.AuditCleanupService;
import ru.afina.util.FileNames;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Админский API журнала аудита: постраничный просмотр с фильтрами,
 * выгрузка в CSV, сводная статистика и очистка старых записей.
 *
 * <p>Все методы требуют роль {@code ROLE_ADMIN}. Время событий хранится
 * в UTC ({@link Instant}), фильтры {@code from}/{@code to} принимаются
 * в ISO-8601 и трактуются включительно.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
public class AdminAuditApi {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditApi.class);

    /** Формат времени в CSV — локальное время сервера, как в интерфейсе. */
    private static final DateTimeFormatter CSV_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Заголовок CSV-выгрузки (разделитель «;», UTF-8 с BOM). */
    private static final String CSV_HEADER = String.join(";",
            "Время", "Тип", "Результат", "Субъект", "Роль", "IP",
            "Объект", "ID", "Инфо", "Детали");

    /** Результат события «ошибка» (см. {@code AuditService}). */
    private static final String RESULT_FAIL = "FAIL";

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 500;

    /** Ограничение выгрузки: больше в CSV не отдаём, чтобы не забить память. */
    private static final int MAX_EXPORT = 10_000;

    /** Допустимый срок хранения для ручной очистки, дней. */
    private static final int MAX_RETENTION_DAYS = 3650;

    private final AuditEventRepository repository;
    private final AuditCleanupService cleanupService;
    private final CurrentUser currentUser;
    private final ApiMapper mapper;

    public AdminAuditApi(AuditEventRepository repository,
                         AuditCleanupService cleanupService,
                         CurrentUser currentUser,
                         ApiMapper mapper) {
        this.repository = repository;
        this.cleanupService = cleanupService;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    /**
     * Постраничный список событий, новые первыми.
     *
     * @param type   фильтр по типу события
     * @param result фильтр по результату ({@code SUCCESS}/{@code FAIL}/{@code WARN})
     * @param actor  подстрока email субъекта без учёта регистра
     * @param from   нижняя граница времени (ISO-8601, включительно)
     * @param to     верхняя граница времени (ISO-8601, включительно)
     * @param page   номер страницы, начиная с 0
     * @param size   размер страницы (1..{@value #MAX_PAGE_SIZE})
     */
    @GetMapping({"", "/events"})
    public PageDto<AuditEventDto> events(@RequestParam(required = false) String type,
                                         @RequestParam(required = false) String result,
                                         @RequestParam(required = false) String actor,
                                         @RequestParam(name = "from", required = false)
                                         String from,
                                         @RequestParam(name = "to", required = false) String to,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         Authentication auth) {
        currentUser.requireAdmin(auth);
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Page<AuditEvent> found = repository.search(
                normalize(type), normalize(result), actorPattern(actor),
                parseInstant(from, "from"), parseInstant(to, "to"),
                PageRequest.of(safePage, safeSize));
        List<AuditEventDto> items = found.getContent().stream()
                .map(mapper::audit)
                .toList();
        return PageDto.of(items, found.getTotalElements(), safePage, safeSize);
    }

    /**
     * Выгрузка журнала в CSV (разделитель «;», UTF-8 BOM).
     *
     * <p>Фильтры те же, что у {@link #events}; ограничение — {@value #MAX_EXPORT}
     * последних событий.</p>
     */
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) String type,
                                            @RequestParam(required = false) String result,
                                            @RequestParam(required = false) String actor,
                                            @RequestParam(name = "from", required = false)
                                            String from,
                                            @RequestParam(name = "to", required = false)
                                            String to,
                                            Authentication auth) {
        currentUser.requireAdmin(auth);
        List<AuditEvent> events = repository.search(
                normalize(type), normalize(result), actorPattern(actor),
                parseInstant(from, "from"), parseInstant(to, "to"),
                PageRequest.of(0, MAX_EXPORT)).getContent();

        StringBuilder csv = new StringBuilder("\ufeff");
        csv.append(CSV_HEADER).append("\r\n");
        for (AuditEvent e : events) {
            csv.append(csvCell(CSV_TIME.format(e.getEventTime()))).append(';')
                    .append(csvCell(e.getEventType())).append(';')
                    .append(csvCell(e.getResult())).append(';')
                    .append(csvCell(e.getActorEmail())).append(';')
                    .append(csvCell(e.getActorRole())).append(';')
                    .append(csvCell(e.getActorIp())).append(';')
                    .append(csvCell(e.getTargetType())).append(';')
                    .append(csvCell(e.getTargetId())).append(';')
                    .append(csvCell(e.getTargetInfo())).append(';')
                    .append(csvCell(e.getDetails())).append("\r\n");
        }

        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.attachment("audit-" + LocalDate.now() + ".csv"))
                .body(body);
    }

    /** Сводка: всего событий, за сегодня, ошибок и разбивка по типам. */
    @GetMapping("/stats")
    public Map<String, Object> stats(Authentication auth) {
        currentUser.requireAdmin(auth);
        ZoneId zone = ZoneId.systemDefault();
        Instant todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();

        Map<String, Long> byType = new LinkedHashMap<>();
        for (AuditEventRepository.TypeCount row : repository.countByType()) {
            byType.put(row.getEventType(), row.getTotal());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", repository.count());
        body.put("today", repository.countByEventTimeAfter(todayStart));
        body.put("failures", repository.countByResult(RESULT_FAIL));
        body.put("byType", byType);
        return body;
    }

    /**
     * Удаление событий старше {@code days} дней.
     *
     * @return количество удалённых записей
     */
    @DeleteMapping("/cleanup")
    public Map<String, Object> cleanup(@RequestParam(defaultValue = "365") int days,
                                       Authentication auth) {
        currentUser.requireAdmin(auth);
        if (days < 1 || days > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException(
                    "Срок хранения должен быть от 1 до " + MAX_RETENTION_DAYS + " дней");
        }
        long deleted = cleanupService.cleanup(days);
        log.info("Ручная очистка аудита: удалено {} записей старше {} дней", deleted, days);
        return Map.of("deleted", deleted);
    }

    // ─────────────────────────── внутреннее ───────────────────────────

    /** Пустое значение фильтра равнозначно его отсутствию. */
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Подстрока email субъекта в нижнем регистре, обёрнутая в LIKE-шаблон. */
    private static String actorPattern(String actor) {
        String value = normalize(actor);
        return value == null ? null : "%" + value.toLowerCase(Locale.ROOT) + "%";
    }

    /** Разбор ISO-8601 в {@link Instant}; ошибка формата — 400. */
    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Некорректное значение " + field
                    + ": ожидается ISO-8601, например 2024-01-31T00:00:00Z");
        }
    }

    /**
     * Ячейка CSV: всегда в кавычках, внутренние кавычки удваиваются.
     * Разделитель «;» и переводы строк внутри значения остаются безопасными.
     */
    private static String csvCell(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
