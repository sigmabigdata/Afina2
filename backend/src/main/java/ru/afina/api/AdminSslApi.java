package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.entity.User;
import ru.afina.service.AuditService;
import ru.afina.service.SslCertificateService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Админский API TLS-сертификата Angie.
 *
 * <p>Повторяет раздел {@code /admin/ssl} старой панели: состояние сертификата,
 * загрузка пары «сертификат + ключ», удаление, перезапуск Angie и откат
 * на предыдущий сертификат. Все методы требуют роль {@code ROLE_ADMIN}.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/ssl")
public class AdminSslApi {

    private static final Logger log = LoggerFactory.getLogger(AdminSslApi.class);

    /** Лимит на каждый PEM-документ (сертификат и ключ) — 1 МБ. */
    private static final int MAX_PEM_LENGTH = 1024 * 1024;

    private final SslCertificateService ssl;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public AdminSslApi(SslCertificateService ssl, CurrentUser currentUser, AuditService audit) {
        this.ssl = ssl;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ═══════════════════════════════════════════════════════
    //  Состояние
    // ═══════════════════════════════════════════════════════

    /** Текущее состояние TLS: свой сертификат, доступность и активность ACME. */
    @GetMapping
    public Map<String, Object> status(Authentication auth) {
        currentUser.requireAdmin(auth);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasCertificate", ssl.hasCertificate());
        result.put("acmeAvailable", ssl.isAcmeAvailable());
        result.put("acmeActive", ssl.isAcmeActive());
        return result;
    }

    // ═══════════════════════════════════════════════════════
    //  Загрузка / удаление
    // ═══════════════════════════════════════════════════════

    /**
     * Загрузить новый сертификат и ключ, применить его и перезапустить Angie.
     *
     * <p>Если Angie не поднялся с новым сертификатом, выполняется откат
     * на предыдущий и повторный перезапуск; клиент получает 409 с описанием.</p>
     */
    @PostMapping
    public Map<String, Object> upload(@RequestBody(required = false) CertificateRequest request,
                                      Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        if (request == null) {
            throw new IllegalArgumentException("Тело запроса пустое");
        }
        String certPem = request.certPem();
        String keyPem = request.keyPem();

        if (certPem == null || certPem.isBlank() || keyPem == null || keyPem.isBlank()) {
            throw new IllegalArgumentException("Сертификат и ключ обязательны");
        }
        if (certPem.length() > MAX_PEM_LENGTH || keyPem.length() > MAX_PEM_LENGTH) {
            throw new IllegalArgumentException("Файл слишком большой (макс. 1 МБ)");
        }
        if (!certPem.contains("BEGIN CERTIFICATE")) {
            throw new IllegalArgumentException(
                    "Не похоже на PEM-сертификат (нет BEGIN CERTIFICATE)");
        }
        if (!keyPem.contains("PRIVATE KEY")) {
            throw new IllegalArgumentException("Не похоже на PEM-ключ (нет PRIVATE KEY)");
        }

        SslCertificateService.ValidationResult validation = ssl.validate(certPem, keyPem);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Проверка не пройдена: " + validation.error());
        }

        try {
            ssl.save(certPem, keyPem);
        } catch (IOException e) {
            log.error("Не удалось сохранить SSL-сертификат", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось сохранить сертификат: " + e.getMessage());
        }

        try {
            ssl.restartAngie();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Перезапуск Angie прерван");
        } catch (Exception e) {
            log.error("Angie не перезапустился с новым сертификатом, откатываюсь", e);
            String rollbackMessage = rollbackAndRestart();
            throw new IllegalStateException("Сертификат сохранён, но Angie не перезапустился ("
                    + e.getMessage() + "). " + rollbackMessage);
        }

        audit.event("SSL_UPLOAD", "SUCCESS", admin.getEmail(),
                "SYSTEM", "ssl", validation.subject(),
                "Сертификат загружен, цепочка: " + validation.chainCount());
        log.warn("SSL-сертификат загружен администратором: {}", validation.subject());

        String chainInfo = validation.chainCount() <= 1
                ? " (только лист, без промежуточных)"
                : " (цепочка: " + validation.chainCount() + " серт.)";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        result.put("applied", true);
        result.put("subject", validation.subject());
        result.put("issuer", validation.issuer());
        result.put("notAfter", validation.notAfter());
        result.put("chainCount", validation.chainCount());
        result.put("message", "Сертификат загружен и применён." + chainInfo
                + " Subject: " + validation.subject());
        return result;
    }

    /** Удалить загруженный сертификат и перезапустить Angie с self-signed/ACME. */
    @DeleteMapping
    public Map<String, Object> delete(Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        try {
            ssl.delete();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Не удалось удалить сертификат: " + e.getMessage());
        }
        try {
            ssl.restartAngie();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Сертификат удалён, но перезапуск Angie прерван");
        } catch (Exception e) {
            throw new IllegalStateException("Сертификат удалён, но Angie не перезапустился: "
                    + e.getMessage());
        }

        audit.event("SSL_DELETE", "SUCCESS", admin.getEmail(),
                "SYSTEM", "ssl", null, "Сертификат удалён, Angie перезапущен");
        log.warn("SSL-сертификат удалён администратором");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        result.put("message", "Сертификат удалён, Angie перезапущен с self-signed");
        return result;
    }

    // ═══════════════════════════════════════════════════════
    //  Перезапуск / откат
    // ═══════════════════════════════════════════════════════

    /** Перезапустить Angie с текущей конфигурацией. */
    @PostMapping("/restart")
    public Map<String, Object> restart(Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        try {
            ssl.restartAngie();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Перезапуск Angie прерван");
        } catch (Exception e) {
            throw new IllegalStateException("Angie не перезапустился: " + e.getMessage());
        }
        audit.event("SSL_RESTART", "SUCCESS", admin.getEmail(),
                "SYSTEM", "ssl", null, "Angie перезапущен вручную");
        log.warn("Angie перезапущен администратором");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("restarted", true);
        result.put("message", "Angie перезапущен");
        return result;
    }

    /** Откатить сертификат на предыдущий (резервную копию) или на ACME. */
    @PostMapping("/rollback")
    public Map<String, Object> rollback(Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        boolean rolledBack = ssl.rollback();
        if (!rolledBack) {
            throw new IllegalStateException(
                    "Откат невозможен: резервного сертификата нет и ACME не настроен");
        }

        String restartMessage;
        boolean restarted = true;
        try {
            ssl.restartAngie();
            restartMessage = "Angie перезапущен";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            restarted = false;
            restartMessage = "перезапуск Angie прерван";
        } catch (Exception e) {
            throw new IllegalStateException("Откат выполнен, но Angie не перезапустился: "
                    + e.getMessage());
        }

        audit.event("SSL_ROLLBACK", "SUCCESS", admin.getEmail(),
                "SYSTEM", "ssl", null, "Выполнен откат сертификата; " + restartMessage);
        log.warn("Выполнен откат SSL-сертификата администратором");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rolledBack", true);
        result.put("restarted", restarted);
        result.put("message", "Выполнен откат на предыдущий сертификат; " + restartMessage);
        return result;
    }

    // ═══════════════════════════════════════════════════════
    //  Хелперы
    // ═══════════════════════════════════════════════════════

    /**
     * Откат после неудачного перезапуска: вернуть предыдущий сертификат
     * и попробовать поднять Angie ещё раз.
     *
     * @return сообщение для клиента о результате отката
     */
    private String rollbackAndRestart() {
        if (!ssl.rollback()) {
            return "не удалось откатить — проверьте angie-certs вручную";
        }
        try {
            ssl.restartAngie();
            return "выполнен откат на предыдущий сертификат, Angie работает";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "откат выполнен, но перезапуск Angie прерван";
        } catch (Exception e) {
            log.error("Angie не перезапустился после отката", e);
            return "откат выполнен, но Angie всё равно не перезапустился";
        }
    }

    // ═══════════════════════════════════════════════════════
    //  DTO запроса
    // ═══════════════════════════════════════════════════════

    /** Тело запроса на загрузку сертификата: PEM-сертификат и PEM-ключ. */
    public record CertificateRequest(String certPem, String keyPem) {
    }
}
