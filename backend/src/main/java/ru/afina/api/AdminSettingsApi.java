package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.afina.api.dto.SettingDto;
import ru.afina.entity.AppSetting;
import ru.afina.service.EmailService;
import ru.afina.service.SettingsService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Админский REST API настроек приложения.
 *
 * <p>Порт страницы {@code /admin/settings}: список key-value настроек,
 * массовое обновление, тестовое письмо SMTP и управление IP-whitelist
 * административной панели.</p>
 *
 * <p>Секретные значения (имена содержат {@code password}, {@code secret},
 * {@code token} или {@code key}) наружу не отдаются — вместо значения
 * возвращается {@code "***"} и флаг {@code secret=true}. При сохранении
 * маска {@code "***"} означает «не менять».</p>
 *
 * <p>Все методы требуют роль {@code ROLE_ADMIN}; доступ дополнительно
 * ограничен IP-whitelist на уровне {@code SecurityConfig}.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
public class AdminSettingsApi {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsApi.class);

    /** Маска секретного значения. */
    private static final String MASK = "***";

    /** Ключ настройки IP-whitelist админки. */
    private static final String K_IP_WHITELIST = "admin.ip_whitelist";

    private final SettingsService settings;
    private final EmailService emailService;
    private final CurrentUser currentUser;

    /** Хост SMTP из окружения (.env.prod) — используется только для проверки наличия SMTP. */
    private final String envMailHost;

    public AdminSettingsApi(SettingsService settings,
                            EmailService emailService,
                            CurrentUser currentUser,
                            @Value("${spring.mail.host:}") String envMailHost) {
        this.settings = settings;
        this.emailService = emailService;
        this.currentUser = currentUser;
        this.envMailHost = envMailHost;
    }

    // ═══════════════════════════════════════════════════════
    //  Список и сохранение
    // ═══════════════════════════════════════════════════════

    /** Все настройки; секретные значения замаскированы и помечены. */
    @GetMapping("")
    public List<SettingDto> list(Authentication auth) {
        currentUser.requireAdmin(auth);
        return settings.getAll().stream().map(AdminSettingsApi::toDto).toList();
    }

    /**
     * Массовое обновление настроек: {@code {ключ: значение, ...}}.
     *
     * <p>Пары с секретным ключом и значением {@code "***"} пропускаются —
     * так UI присылает неизменённые пароли и токены.</p>
     */
    @PutMapping("")
    public Map<String, Object> update(@RequestBody Map<String, String> values,
                                      Authentication auth) {
        var admin = currentUser.requireAdmin(auth);
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Список настроек пуст");
        }
        int updated = 0;
        int skipped = 0;
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Пустой ключ настройки");
            }
            String value = e.getValue();
            if (isSecretKey(key) && (value == null || MASK.equals(value))) {
                skipped++;
                continue;
            }
            settings.set(key, value == null ? "" : value, admin.getEmail());
            updated++;
        }
        log.info("Настройки обновлены администратором {}: изменено {}, пропущено {}",
                admin.getEmail(), updated, skipped);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("updated", updated);
        body.put("skipped", skipped);
        return body;
    }

    // ═══════════════════════════════════════════════════════
    //  SMTP
    // ═══════════════════════════════════════════════════════

    /**
     * Отправить тестовое письмо на указанный адрес.
     *
     * @throws IllegalStateException если SMTP не настроен ни в окружении, ни в БД
     */
    @PostMapping("/smtp/test")
    public Map<String, Object> smtpTest(@RequestBody SmtpTestRequest request,
                                        Authentication auth) {
        var admin = currentUser.requireAdmin(auth);
        String to = request == null || request.to() == null ? "" : request.to().trim();
        if (to.isEmpty() || !to.contains("@")) {
            throw new IllegalArgumentException("Укажите корректный email");
        }
        if (!smtpAvailable()) {
            throw new IllegalStateException("SMTP не настроен: задайте smtp.host в настройках "
                    + "или MAIL_HOST в окружении");
        }
        try {
            emailService.sendTest(to);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка отправки письма: " + e.getMessage(), e);
        }
        log.info("Тестовое письмо отправлено на {} администратором {}", to, admin.getEmail());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sent", true);
        body.put("to", to);
        return body;
    }

    // ═══════════════════════════════════════════════════════
    //  IP-whitelist административной панели
    // ═══════════════════════════════════════════════════════

    /** Текущее значение IP-whitelist (пустая строка, если не задано). */
    @GetMapping("/ip-whitelist")
    public Map<String, Object> ipWhitelist(Authentication auth) {
        currentUser.requireAdmin(auth);
        return Map.of("value", settings.getOrDefault(K_IP_WHITELIST, ""));
    }

    /** Сохранить IP-whitelist административной панели. */
    @PutMapping("/ip-whitelist")
    public Map<String, Object> saveIpWhitelist(@RequestBody IpWhitelistRequest request,
                                               Authentication auth) {
        var admin = currentUser.requireAdmin(auth);
        String raw = request == null || request.value() == null ? "" : request.value().trim();
        if (!raw.isEmpty() && !"*".equals(raw) && !isValidIpWhitelist(raw)) {
            throw new IllegalArgumentException("Некорректный IP/CIDR в whitelist: «" + raw
                    + "». Примеры: 203.0.113.5, 203.0.113.0/24, * (все IP)");
        }
        settings.set(K_IP_WHITELIST, raw, admin.getEmail());
        if ("*".equals(raw)) {
            log.warn("⚠ ADMIN IP whitelist = * (разрешены все IP). by={}", admin.getEmail());
        } else {
            log.info("IP-whitelist обновлён администратором {}: «{}»", admin.getEmail(), raw);
        }
        return Map.of("value", raw);
    }

    // ═══════════════════════════════════════════════════════
    //  Вспомогательные методы
    // ═══════════════════════════════════════════════════════

    /** Сущность настройки → DTO с маскированием секретных значений. */
    private static SettingDto toDto(AppSetting s) {
        boolean secret = isSecretKey(s.getKey());
        return new SettingDto(
                s.getKey(),
                secret ? MASK : s.getValue(),
                s.getDescription(),
                s.getUpdatedAt(),
                s.getUpdatedBy(),
                secret);
    }

    /** Секретный ли ключ: имя содержит password/secret/token/key. */
    static boolean isSecretKey(String key) {
        if (key == null || key.isBlank()) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("password")
                || lower.contains("secret")
                || lower.contains("token")
                || lower.contains("key");
    }

    /** Есть ли рабочий SMTP: хост в БД или осмысленный spring.mail.host. */
    private boolean smtpAvailable() {
        if (settings.isSmtpConfigured()) return true;
        return envMailHost != null
                && !envMailHost.isBlank()
                && !"localhost".equalsIgnoreCase(envMailHost)
                && !"127.0.0.1".equals(envMailHost);
    }

    /** Проверка списка IP/CIDR: каждый элемент — IPv4, IPv4/prefix или IPv6-literal. */
    static boolean isValidIpWhitelist(String raw) {
        for (String part : raw.split(",")) {
            String e = part.trim();
            if (e.isEmpty()) continue;
            if ("*".equals(e)) continue;
            String address = e;
            int prefix = -1;
            if (e.contains("/")) {
                int slash = e.indexOf('/');
                address = e.substring(0, slash);
                try {
                    prefix = Integer.parseInt(e.substring(slash + 1));
                } catch (NumberFormatException nfe) {
                    return false;
                }
                if (prefix < 0 || prefix > 32) return false;
            }
            if (address.contains(":")) {
                // IPv6: проверяем разбором, без CIDR-арифметики
                try {
                    java.net.InetAddress.getByName(address);
                } catch (Exception ex) {
                    return false;
                }
                continue;
            }
            if (!address.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
            for (String octet : address.split("\\.")) {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) return false;
            }
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════
    //  DTO запросов
    // ═══════════════════════════════════════════════════════

    /** Тело запроса тестового письма: {@code {"to":"user@example.com"}}. */
    public record SmtpTestRequest(String to) {
    }

    /** Тело запроса IP-whitelist: {@code {"value":"203.0.113.0/24"}}. */
    public record IpWhitelistRequest(String value) {
    }
}
