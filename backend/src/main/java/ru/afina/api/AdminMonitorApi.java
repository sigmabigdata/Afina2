package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.entity.MonitorEvent;
import ru.afina.repository.MonitorEventRepository;
import ru.afina.service.LogService;
import ru.afina.service.MonitoringService;
import ru.afina.service.SystemInfoService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Админский API мониторинга и сведений о сервере.
 *
 * <p>Повторяет разделы {@code /admin/monitor} и {@code /admin/system} старой
 * панели, но отдаёт данные в JSON. Все методы требуют роль {@code ROLE_ADMIN};
 * доступ дополнительно ограничен IP-whitelist на уровне {@code SecurityConfig}.</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminMonitorApi {

    private static final Logger log = LoggerFactory.getLogger(AdminMonitorApi.class);

    private static final int DEFAULT_HISTORY_LIMIT = 100;
    private static final int MAX_HISTORY_LIMIT = 200;
    private static final int DEFAULT_LOG_LINES = 200;
    private static final int MIN_LOG_LINES = 10;
    private static final int MAX_LOG_LINES = 2000;

    private final MonitoringService monitoring;
    private final MonitorEventRepository events;
    private final LogService logService;
    private final SystemInfoService systemInfo;
    private final CurrentUser currentUser;

    public AdminMonitorApi(MonitoringService monitoring,
                           MonitorEventRepository events,
                           LogService logService,
                           SystemInfoService systemInfo,
                           CurrentUser currentUser) {
        this.monitoring = monitoring;
        this.events = events;
        this.logService = logService;
        this.systemInfo = systemInfo;
        this.currentUser = currentUser;
    }

    // ═══════════════════════════════════════════════════════
    //  Мониторинг
    // ═══════════════════════════════════════════════════════

    /**
     * Живой прогон проверок всех компонентов (DB, SMTP, storage, keys, disk,
     * memory, admins.env) без записи событий и без отправки алертов.
     */
    @GetMapping("/monitor/checks")
    public List<Map<String, Object>> checks(Authentication auth) {
        currentUser.requireAdmin(auth);
        List<Map<String, Object>> result = new ArrayList<>();
        for (MonitoringService.CheckResult check : monitoring.runAllChecks()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("component", check.component());
            item.put("title", check.title());
            item.put("healthy", check.healthy());
            item.put("message", check.message());
            item.put("latencyMs", check.latencyMs());
            result.add(item);
        }
        return result;
    }

    /** История событий мониторинга (переходы OK/FAIL), новые сверху. */
    @GetMapping("/monitor/history")
    public List<Map<String, Object>> history(
            @RequestParam(defaultValue = "" + DEFAULT_HISTORY_LIMIT) int limit,
            Authentication auth) {
        currentUser.requireAdmin(auth);
        int size = clamp(limit, 1, MAX_HISTORY_LIMIT);
        List<MonitorEvent> recent = events.findAllByOrderByCheckedAtDesc(PageRequest.of(0, size));
        List<Map<String, Object>> result = new ArrayList<>(recent.size());
        for (MonitorEvent event : recent) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", event.getId());
            item.put("checkedAt", event.getCheckedAt());
            item.put("status", event.getStatus());
            item.put("component", event.getComponent());
            item.put("reason", event.getReason());
            item.put("alertSent", event.isAlertSent());
            result.add(item);
        }
        return result;
    }

    /** Последние строки журнала приложения. */
    @GetMapping("/monitor/logs")
    public Map<String, Object> logs(
            @RequestParam(defaultValue = "" + DEFAULT_LOG_LINES) int lines,
            Authentication auth) {
        currentUser.requireAdmin(auth);
        int count = clamp(lines, MIN_LOG_LINES, MAX_LOG_LINES);
        List<String> tail = logService.tail(count);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lines", tail);
        result.put("count", tail.size());
        result.put("requested", count);
        return result;
    }

    /** Отправить тестовое оповещение на адрес из настроек мониторинга. */
    @PostMapping("/monitor/test-alert")
    public Map<String, Object> testAlert(Authentication auth) {
        currentUser.requireAdmin(auth);
        String to = monitoring.alertEmail();
        if (to == null || to.isBlank()) {
            throw new IllegalStateException(
                    "Email для алертов не задан (monitor.test_email / monitor.mail_to)");
        }
        try {
            monitoring.sendTestAlert();
        } catch (IllegalStateException e) {
            // Настройки/окружение не позволяют отправить — это конфликт состояния.
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Отправка тестового оповещения прервана");
        } catch (Exception e) {
            log.error("Тестовый алерт не отправлен", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Не удалось отправить тестовое оповещение: " + e.getMessage());
        }
        log.warn("Тестовый алерт мониторинга отправлен администратором");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", true);
        result.put("to", to);
        result.put("message", "Тестовое оповещение отправлено на " + to);
        return result;
    }

    // ═══════════════════════════════════════════════════════
    //  Сведения о сервере
    // ═══════════════════════════════════════════════════════

    /** Метрики сервера: uptime, память, CPU, диск, БД, хранилище, JVM. */
    @GetMapping("/system")
    public Map<String, Object> system(Authentication auth) {
        currentUser.requireAdmin(auth);
        Duration uptime = systemInfo.getJvmUptime();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uptimeSeconds", uptime.getSeconds());
        result.put("uptimePretty", formatDuration(uptime.getSeconds()));
        result.put("startedAt", Instant.now().minus(uptime));
        result.put("memory", systemInfo.getMemory());
        result.put("cpu", systemInfo.getCpu());
        result.put("disk", systemInfo.getDisk());
        result.put("storage", systemInfo.getStorageSize());
        result.put("database", systemInfo.getDatabaseStats());
        result.put("runtime", systemInfo.getRuntimeInfo());
        result.put("diagnostics", systemInfo.runDiagnostics());
        return result;
    }

    // ═══════════════════════════════════════════════════════
    //  Хелперы
    // ═══════════════════════════════════════════════════════

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    /** Человекочитаемая длительность: «2д 3ч 4м 5с». */
    private static String formatDuration(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("д ");
        if (hours > 0) sb.append(hours).append("ч ");
        if (minutes > 0) sb.append(minutes).append("м ");
        sb.append(secs).append("с");
        return sb.toString();
    }
}
