package ru.afina.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.afina.entity.MonitorEvent;
import ru.afina.repository.MonitorEventRepository;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Покомпонентный мониторинг: DB, SMTP, storage, keys, disk, memory, admins.env.
 *
 * <p>Каждые 5 минут:</p>
 * <ul>
 *   <li>Прогоняет все проверки (таймаут на каждую ~3 сек).</li>
 *   <li>Сравнивает с прошлым состоянием (app_settings.monitor.components_state).</li>
 *   <li>Пишет в monitor_events только переходы (OK→FAIL, FAIL→OK).</li>
 *   <li>Алерт на 1-й сбой подряд, потом на 5-й, 10-й и т.д.</li>
 *   <li>Алерт «восстановлено», когда все снова OK после сбоев.</li>
 * </ul>
 *
 * <p>Работает через app (JavaMail). Если сам app упал — работает {@code monitor.sh} через cron.</p>
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);
    private static final String SYSTEM = "system";
    private static final String KEY_FAILS = "monitor.consecutive_fails";
    private static final String EVT_ALERT = "alert";
    /** В демо фоновые задачи не нужны: у каждой сессии своя одноразовая БД. */
    @org.springframework.beans.factory.annotation.Value("${app.demo.enabled:false}")
    private boolean demoMode;

    /** Шаблоны алерта по умолчанию (редактируются в /admin/settings). */
    private static final String DEFAULT_ALERT_SUBJECT = "Афина · {status}: {components}";
    private static final String DEFAULT_ALERT_BODY = """
            {details}

            Сбоев подряд: {fails}

            Открыть: {url}/admin/monitor
            """;
    private static final String KEY_STATE = "monitor.components_state";

    private static final int ALERT_REMIND_EVERY = 5;

    public record CheckResult(String component, String title, boolean healthy,
                              String message, long latencyMs) {
        public String emoji() { return healthy ? "✅" : "❌"; }
    }

    private final MonitorEventRepository eventRepo;
    private final SettingsService settings;
    private final EmailService emailService;
    private final JdbcTemplate jdbc;

    @Value("${app.base-url:}")
    private String appBaseUrl;

    @Value("${app.storage-path:/app/storage/documents}")
    private String storagePath;

    @Value("${app.file-encryption-key-path:/opt/afinaz/secrets/file.key}")
    private String fileKeyPath;

    @Value("${app.keys-dir:./secrets/keys}")
    private String keysDir;

    @Value("${app.pii-key-path:/opt/afinaz/secrets/pii.key}")
    private String piiKeyPath;

    @Value("${app.admins-file:./admins.env}")
    private String adminsFile;

    @Value("${spring.mail.host:}")
    private String envMailHost;

    @Value("${spring.mail.port:25}")
    private int envMailPort;

    public MonitoringService(MonitorEventRepository eventRepo,
                             SettingsService settings,
                             EmailService emailService,
                             DataSource dataSource) {
        this.eventRepo = eventRepo;
        this.settings = settings;
        this.emailService = emailService;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void scheduledCheck() {
        if (demoMode) return;
        if (!settings.getBoolOrDefault("monitor.enabled", true)) return;
        try {
            checkAll();
        } catch (Exception e) {
            log.error("scheduledCheck failed", e);
        }
    }

    public List<CheckResult> checkAll() {
        settings.set("monitor.last_check_at", Instant.now().toString(), SYSTEM);

        List<CheckResult> results = runAllChecks();
        boolean anyFailed = results.stream().anyMatch(r -> !r.healthy());

        Map<String, Boolean> prev = parseState(settings.getOrDefault(KEY_STATE, "{}"));
        Map<String, Boolean> curr = results.stream()
                .collect(Collectors.toMap(CheckResult::component, CheckResult::healthy));

        // Логируем только переходы состояний
        for (CheckResult r : results) {
            boolean prevOk = prev.getOrDefault(r.component(), true);
            if (!r.healthy()) {
                saveEvent(r.component(), "FAIL", r.message(), false);
            } else if (!prevOk) {
                saveEvent(r.component(), "OK", "recovered: " + r.message(), false);
            }
        }

        settings.set(KEY_STATE, buildStateJson(curr), SYSTEM);

        if (!anyFailed) {
            onAllOk();
        } else {
            onFailure(results);
        }
        return results;
    }

    private void onAllOk() {
        int fails = settings.getIntOrDefault(KEY_FAILS, 0);
        if (fails <= 0) return;

        log.info("monitoring: recovered after {} fails", fails);
        if (alertsEnabled() && settings.getBoolOrDefault("monitor.alert_recovery", true)) {
            String to = alertEmail();
            if (!to.isBlank()) {
                try {
                    emailService.sendAlert(to,
                            alertSubject("ВОССТАНОВЛЕНО", "все компоненты"),
                            "Все компоненты снова в норме (было " + fails
                                    + " сбоев подряд).\n\n" + appBaseUrl + "/admin/monitor");
                    settings.set("monitor.last_alert_at", Instant.now().toString(), SYSTEM);
                } catch (Exception e) {
                    log.warn("recovery email failed: {}", e.getMessage());
                }
            }
        }
        settings.set(KEY_FAILS, "0", SYSTEM);
    }

    private void onFailure(List<CheckResult> results) {
        int fails = settings.getIntOrDefault(KEY_FAILS, 0) + 1;
        settings.set(KEY_FAILS, String.valueOf(fails), SYSTEM);

        List<String> failedComponents = results.stream()
                .filter(r -> !r.healthy()).map(CheckResult::component).toList();
        log.warn("monitoring: fails={}, failed components: {}", fails, failedComponents);

        boolean shouldAlert = (fails == 1) || (fails % ALERT_REMIND_EVERY == 0);
        if (!shouldAlert) return;

        if (!alertsEnabled()) {
            log.info("monitoring: fails={}, alerts disabled (alert.enabled=false)", fails);
            saveEvent(EVT_ALERT, "FAIL", "alerts disabled in settings", false);
            return;
        }

        String to = alertEmail();
        if (to.isBlank()) {
            log.warn("alert email is empty — проверьте /admin/monitor");
            return;
        }
        try {
            emailService.sendAlert(to,
                    alertSubject("Сервис недоступен", failedComponentsText(results)),
                    buildAlertBody(results, fails));
            settings.set("monitor.last_alert_at", Instant.now().toString(), SYSTEM);
            saveEvent(EVT_ALERT, "FAIL", "sent to " + to, true);
            if (log.isInfoEnabled()) {
                log.info("alert sent to {} (fails={})", ru.afina.security.PiiMask.email(to), fails);
            }
        } catch (Exception e) {
            log.error("alert email failed", e);
            saveEvent(EVT_ALERT, "FAIL", "email error: " + e.getMessage(), false);
        }
    }

    public String alertEmail() {
        String test = settings.getOrDefault("monitor.test_email", "");
        return test.isBlank() ? settings.getOrDefault("monitor.mail_to", "") : test;
    }

    public List<CheckResult> runAllChecks() {
        List<CheckResult> results = new ArrayList<>();
        results.add(runCheck("db", "База данных", this::checkDb));
        results.add(runCheck("smtp", "SMTP", this::checkSmtp));
        results.add(runCheck("storage", "Хранилище файлов", this::checkStorage));
        results.add(runCheck("keys", "Ключи шифрования", this::checkKeys));
        results.add(runCheck("disk", "Диск", this::checkDisk));
        results.add(runCheck("memory", "Память JVM", this::checkMemory));
        results.add(runCheck("admins", "admins.env", this::checkAdmins));
        return results;
    }

    @FunctionalInterface
    @SuppressWarnings("java:S112") // спектр исключений широк намеренно
    private interface Checker { String check() throws Exception; }

    private CheckResult runCheck(String component, String title, Checker fn) {
        long t = System.nanoTime();
        try {
            String msg = fn.check();
            long ms = (System.nanoTime() - t) / 1_000_000;
            log.info("check[{}]: OK ({}ms) — {}", component, ms, msg);
            return new CheckResult(component, title, true, msg, ms);
        } catch (Exception e) {
            long ms = (System.nanoTime() - t) / 1_000_000;
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("check[{}]: FAIL ({}ms) — {}", component, ms, err);
            return new CheckResult(component, title, false, err, ms);
        }
    }

    private String checkDb() {
        Integer x = jdbc.queryForObject("SELECT 1", Integer.class);
        if (x == null || x != 1) throw new IllegalStateException("SELECT 1 → " + x);
        Long users = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        return "OK, users=" + users;
    }

    private String checkSmtp() {
        String host = settings.getOrDefault("smtp.host", envMailHost);
        int port = settings.getIntOrDefault("smtp.port", envMailPort);
        if (host.isBlank()) throw new IllegalStateException("SMTP host не задан");
        try (Socket s = new Socket()) {
            // 10s, не 3s: на хосте с Docker bridge Postfix иногда форкает smtpd
            // до 4-5 сек (fork slow + rDNS-резолв 172.22.0.3 + conntrack). При 3s
            // монитор сдаётся раньше, чем Postfix пришлёт баннер → ложный FAIL.
            s.connect(new InetSocketAddress(host, port), 10000);
            s.setSoTimeout(10000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String banner = r.readLine();
            if (banner == null || !banner.startsWith("220")) {
                throw new IllegalStateException(host + ":" + port + " → " + banner);
            }
            return host + ":" + port + " → " + banner.trim();
        } catch (SocketTimeoutException e) {
            throw new IllegalStateException(host + ":" + port + " timeout");
        } catch (ConnectException e) {
            throw new IllegalStateException(host + ":" + port + " refused");
        } catch (Exception e) {
            throw new IllegalStateException(host + ":" + port + " → " + e.getMessage());
        }
    }

    @SuppressWarnings("java:S112") // внутренний хелпер: спектр исключений широк

    private String checkStorage() throws Exception {
        Path p = Paths.get(storagePath);
        if (!Files.isDirectory(p)) throw new IllegalStateException("нет директории " + p);
        if (!Files.isWritable(p)) throw new IllegalStateException("нет доступа на запись " + p);
        long count = 0;
        long size = 0;
        try (var s = Files.list(p)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(f)) {
                    count++;
                    try {
                        size += Files.size(f);
                    } catch (Exception ignored) {
                        // файл исчез между листингом и подсчётом
                    }
                }
            }
        }
        return p.getFileName() + " OK, " + count + " файлов, " + prettySize(size);
    }

    @SuppressWarnings("java:S112") // внутренний хелпер: спектр исключений широк

    private String checkKeys() throws Exception {
        // Ключи версионированы: secrets/keys/file.key.vN и pii.key.vN.
        // Проверяем именно актуальную версию (legacy-путь мог остаться только
        // для миграции со старой установки).
        Path fk = latestKey("file.key.v");
        Path pk = latestKey("pii.key.v");
        if (fk == null) throw new IllegalStateException("файловый ключ не найден в " + keysDir);
        if (pk == null) throw new IllegalStateException("ключ PII не найден в " + keysDir);
        if (!Files.isReadable(fk)) throw new IllegalStateException("файловый ключ недоступен");
        if (!Files.isReadable(pk)) throw new IllegalStateException("ключ PII недоступен");
        if (Files.size(fk) < 32) throw new IllegalStateException("файловый ключ повреждён");
        if (Files.size(pk) < 32) throw new IllegalStateException("ключ PII повреждён");
        return "ключи OK: " + fk.getFileName() + ", " + pk.getFileName();
    }

    /** Последняя версия ключа с указанным префиксом (null — если файлов нет). */
    private Path latestKey(String prefix) throws java.io.IOException {
        Path dir = Paths.get(keysDir);
        if (!Files.isDirectory(dir)) return null;
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .filter(Files::isRegularFile)
                    .max(java.util.Comparator.comparingInt(this::keyVersion))
                    .orElse(null);
        }
    }

    private int keyVersion(Path p) {
        String name = p.getFileName().toString();
        int idx = name.lastIndexOf(".v") + 2;
        try {
            return Integer.parseInt(name.substring(idx));
        } catch (Exception e) {
            return -1;
        }
    }

    private String checkDisk() {
        Path p = Paths.get(settings.getOrDefault("monitor.disk_path", "/"));
        java.io.File f = p.toFile();
        long total = f.getTotalSpace();
        long free = f.getFreeSpace();
        if (total == 0) throw new IllegalStateException("не читается размер");
        long usedPct = (total - free) * 100 / total;
        int threshold = settings.getIntOrDefault("monitor.disk_threshold_pct", 90);
        if (usedPct >= threshold) throw new IllegalStateException(usedPct + "% >= " + threshold + "%");
        return usedPct + "% занято";
    }

    private String checkMemory() {
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long usedPct = (total - free) * 100 / total;
        int threshold = settings.getIntOrDefault("monitor.memory_threshold_pct", 90);
        if (usedPct >= threshold) throw new IllegalStateException(usedPct + "% >= " + threshold + "%");
        return usedPct + "% JVM";
    }

    @SuppressWarnings("java:S112") // внутренний хелпер: спектр исключений широк

    private String checkAdmins() throws Exception {
        Path p = Paths.get(adminsFile);
        if (!Files.isRegularFile(p)) throw new IllegalStateException("admins.env не найден");
        long n = 0;
        for (String line : Files.readAllLines(p)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            if (line.contains("|")) n++;
        }
        if (n == 0) throw new IllegalStateException("admins.env пуст");
        return n + " админ(ов)";
    }

    /** Тестовый алерт через EmailService (не увеличивает счётчик). */
    @SuppressWarnings("java:S112") // спектр исключений широк намеренно
    public void sendTestAlert() throws Exception {
        String to = alertEmail();
        if (to.isBlank()) {
            throw new IllegalStateException("Email для алертов не задан (monitor.test_email / monitor.mail_to)");
        }
        List<CheckResult> results = runAllChecks();
        emailService.sendAlert(to,
                alertSubject("ТЕСТ алерта", "проверка канала"),
                "Это тестовое сообщение из /admin/monitor.\n\n" + buildAlertBody(results, 0));
        if (log.isInfoEnabled()) {
            log.info("test alert sent to {}", ru.afina.security.PiiMask.email(to));
        }
    }

    private void saveEvent(String component, String status, String reason, boolean alertSent) {
        try {
            MonitorEvent e = new MonitorEvent();
            e.setComponent(component);
            e.setStatus(status);
            e.setReason(truncate(reason, 500));
            e.setAlertSent(alertSent);
            eventRepo.save(e);
        } catch (Exception e) {
            log.warn("save event failed: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private static String prettySize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return (b / 1024) + " KB";
        if (b < 1024L * 1024 * 1024) return (b / 1024 / 1024) + " MB";
        return String.format(java.util.Locale.ROOT, "%.2f GB", b / 1024.0 / 1024 / 1024);
    }

    private Map<String, Boolean> parseState(String json) {
        Map<String, Boolean> m = new HashMap<>();
        if (json == null || json.isBlank() || "{}".equals(json)) return m;
        String s = json.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);
        if (s.isBlank()) return m;
        for (String pair : s.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) m.put(kv[0].trim().replace("\"", ""),
                    Boolean.parseBoolean(kv[1].trim()));
        }
        return m;
    }

    private String buildStateJson(Map<String, Boolean> state) {
        return state.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    /** Алерты включены? (alert.enabled, по умолчанию да). */
    private boolean alertsEnabled() {
        return settings.getBoolOrDefault("alert.enabled", true);
    }

    /** Список отказавших компонентов для {components}. */
    private static String failedComponentsText(List<CheckResult> results) {
        List<String> failed = results.stream().filter(r -> !r.healthy())
                .map(CheckResult::title).toList();
        return failed.isEmpty() ? "все компоненты" : String.join(", ", failed);
    }

    /** Тема алерта из шаблона alert.subject. */
    private String alertSubject(String status, String components) {
        String tpl = settings.getOrDefault("alert.subject", DEFAULT_ALERT_SUBJECT);
        return tpl.replace("{status}", status).replace("{components}", components);
    }

    /** Тело алерта из шаблона alert.body: {details}, {fails}, {url}. */
    private String buildAlertBody(List<CheckResult> results, int fails) {
        StringBuilder details = new StringBuilder();
        for (CheckResult r : results) {
            details.append(r.emoji()).append(" ").append(r.title())
                    .append(" [").append(r.component()).append("]: ")
                    .append(r.message()).append(" (").append(r.latencyMs()).append("ms)\n");
        }
        String tpl = settings.getOrDefault("alert.body", DEFAULT_ALERT_BODY);
        return tpl.replace("{details}", details.toString().trim())
                .replace("{fails}", String.valueOf(fails))
                .replace("{url}", appBaseUrl == null ? "" : appBaseUrl);
    }
}
