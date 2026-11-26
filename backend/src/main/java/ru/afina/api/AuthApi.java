package ru.afina.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.api.dto.UserDto;
import ru.afina.dto.CertLoginRequest;
import ru.afina.entity.Document;
import ru.afina.entity.User;
import ru.afina.repository.DocumentRepository;
import ru.afina.security.AuthUser;
import ru.afina.security.PiiMask;
import ru.afina.service.AdminCredentialsFileService;
import ru.afina.service.AuditService;
import ru.afina.service.ConsentService;
import ru.afina.service.EmailService;
import ru.afina.service.SettingsService;
import ru.afina.service.SignatureVerifier;
import ru.afina.service.UserService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Аутентификация Afina2.
 *
 * <p>Два способа входа:
 * <ol>
 *   <li><b>magic-link</b> — клиент вводит e-mail, получает ссылку с одноразовым
 *       токеном; переход по ссылке создаёт cookie-сессию;</li>
 *   <li><b>сертификат</b> — сотрудник/администратор подписывает одноразовый
 *       челлендж своей УКЭП; подпись проверяется КриптоПро (ГОСТ 34.10-2012),
 *       CN сверяется с карточкой.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthApi {

    private static final Logger log = LoggerFactory.getLogger(AuthApi.class);
    private static final String K_ERROR = "error";
    private static final long CHALLENGE_TTL_MS = Duration.ofMinutes(5).toMillis();

    private final UserService userService;
    private final EmailService emailService;
    private final AuditService audit;
    private final ConsentService consentService;
    private final SettingsService settings;
    private final SignatureVerifier signatureVerifier;
    private final AdminCredentialsFileService adminFile;
    private final SecurityContextRepository contextRepository;
    private final CurrentUser currentUser;
    private final ApiMapper mapper;
    private final DocumentRepository documentRepository;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.demo.url:}")
    private String demoUrl;

    /**
     * Требовать полную проверку цепочки и CRL при входе по сертификату.
     *
     * <p>Пока в truststore нет корневых сертификатов УЦ заказчика, строгая
     * проверка невозможна: подпись челленджа криптографически проверяется
     * в любом случае, а CN/СНИЛС сверяются с admins.env. После установки
     * корневых сертификатов флаг нужно вернуть в true.</p>
     */
    @Value("${app.auth.cert-login.require-chain:true}")
    private boolean certLoginRequireChain = true;

    /** Одноразовые челленджи: значение → время создания. */
    private final Map<String, Long> challenges = new ConcurrentHashMap<>();

    @SuppressWarnings("java:S107") // контроллер агрегирует зависимости сервисов
    public AuthApi(UserService userService, EmailService emailService, AuditService audit,
                   ConsentService consentService, SettingsService settings,
                   SignatureVerifier signatureVerifier, AdminCredentialsFileService adminFile,
                   SecurityContextRepository contextRepository, CurrentUser currentUser,
                   ApiMapper mapper, DocumentRepository documentRepository) {
        this.userService = userService;
        this.emailService = emailService;
        this.audit = audit;
        this.consentService = consentService;
        this.settings = settings;
        this.signatureVerifier = signatureVerifier;
        this.adminFile = adminFile;
        this.contextRepository = contextRepository;
        this.currentUser = currentUser;
        this.mapper = mapper;
        this.documentRepository = documentRepository;
    }

    // ═══════════════════════════════════════════════════════
    //  CSRF / текущий пользователь
    // ═══════════════════════════════════════════════════════

    /** Отдаёт CSRF-токен и выставляет cookie {@code XSRF-TOKEN}. */
    @GetMapping("/csrf")
    public Map<String, Object> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute("_csrf");
        Map<String, Object> body = new LinkedHashMap<>();
        if (token != null) {
            body.put("headerName", token.getHeaderName());
            body.put("parameterName", token.getParameterName());
            body.put("token", token.getToken());
        }
        body.put("demoUrl", demoUrl);
        return body;
    }

    /** Профиль текущего пользователя; 401 — если сессии нет. */
    @GetMapping("/me")
    public ResponseEntity<UserDto> me(Authentication auth) {
        User user = currentUser.entityOrNull(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        long total = documentRepository.countByOwner(user);
        long signed = documentRepository.countByOwnerAndSignedTrue(user);
        return ResponseEntity.ok(mapper.user(user, total, signed));
    }

    // ═══════════════════════════════════════════════════════
    //  Magic-link
    // ═══════════════════════════════════════════════════════

    /** Запрос ссылки для входа. Ответ не раскрывает, существует ли адрес. */
    @PostMapping("/login-link")
    public Map<String, Object> loginLink(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Укажите рабочую почту");
        }
        String link = userService.generateLoginLink(email, baseUrl);
        if (link != null) {
            String fullName = userService.findByEmail(email)
                    .map(User::getFullName).orElse(null);
            emailService.sendLoginLink(email, link, fullName);
            audit.event("LOGIN_LINK_SENT", "SUCCESS", PiiMask.email(email),
                    "USER", null, null, "Ссылка для входа отправлена");
        } else if (log.isInfoEnabled()) {
            log.info("Login link requested for unknown/blocked email: {}",
                    PiiMask.email(email));
        }
        return Map.of("sent", true,
                "message", "Если адрес есть в системе, ссылка для входа отправлена");
    }

    /** Подтверждение входа по ссылке из письма. Создаёт сессию и уводит в кабинет. */
    @GetMapping("/confirm")
    public void confirm(@RequestParam("token") String token,
                        HttpServletRequest request,
                        HttpServletResponse response) throws java.io.IOException {
        try {
            User user = userService.consumeLoginToken(token);
            if (user == null) {
                audit.loginFail("token:" + (token != null && token.length() > 8
                        ? token.substring(0, 8) + "..." : "?"),
                        "Недействительный/истёкший токен");
                response.sendRedirect(baseUrl + "/login?error=token");
                return;
            }
            establishSession(user, request, response);
            audit.loginSuccess(user.getEmail(), user.getRole().name());
            response.sendRedirect(baseUrl + "/dashboard");
        } catch (Exception e) {
            log.error("confirmLogin failed", e);
            audit.loginFail("token", "Внутренняя ошибка при входе: " + e.getMessage());
            response.sendRedirect(baseUrl + "/login?error=internal");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ═══════════════════════════════════════════════════════
    //  Вход по сертификату (сотрудники и администраторы)
    // ═══════════════════════════════════════════════════════

    /** Одноразовый челлендж для подписи. */
    @GetMapping("/admin/challenge")
    public Map<String, String> challenge() {
        cleanOldChallenges();
        String value = UUID.randomUUID().toString().replace("-", "")
                + "-" + System.currentTimeMillis();
        challenges.put(value, System.currentTimeMillis());
        return Map.of("challenge", value);
    }

    /** Вход по сертификату: подпись челленджа + сверка CN/СНИЛС. */
    @PostMapping("/admin/cert-login")
    public ResponseEntity<Map<String, Object>> certLogin(@RequestBody CertLoginRequest req,
                                                         HttpServletRequest request,
                                                         HttpServletResponse response) {
        try {
            if (!consumeChallenge(req.getChallenge())) {
                return ResponseEntity.badRequest()
                        .body(Map.of(K_ERROR, "Челлендж недействителен или истёк"));
            }

            User existingUser = null;
            String expectedCn;
            ru.afina.entity.Role expectedRole;

            var envAdmin = adminFile.find(req.getCn(), req.getSnils());
            if (envAdmin.isPresent()) {
                expectedCn = envAdmin.get().cn();
                expectedRole = envAdmin.get().role();
            } else {
                existingUser = userService.findEmployeeByCnAndSnils(req.getCn(), req.getSnils())
                        .orElse(null);
                if (existingUser == null) {
                    log.warn("Cert login rejected (not in env, not in DB): CN={}",
                            PiiMask.fio(req.getCn()));
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of(K_ERROR,
                                    "Пользователь не найден или не является сотрудником"));
                }
                expectedCn = existingUser.getFullName();
                expectedRole = existingUser.getRole();
            }

            byte[] challengeBytes = req.getChallenge().getBytes(StandardCharsets.UTF_8);
            // Строгий режим: подпись + цепочка + truststore + CRL
            Map<String, Object> result = certLoginRequireChain
                    ? signatureVerifier.verifyDetached(challengeBytes, req.getSignatureBase64())
                    : signatureVerifier.verifyDetachedBasic(challengeBytes,
                            req.getSignatureBase64());
            if (!certLoginRequireChain) {
                log.warn("Вход по сертификату в упрощённом режиме (без проверки цепочки/CRL): {}",
                        PiiMask.fio(expectedCn));
            }

            String signerCn = String.valueOf(result.getOrDefault("signerSubject", "")).trim();
            if (signerCn.isEmpty() || !signerCn.equalsIgnoreCase(expectedCn)) {
                log.warn("CN mismatch: signer='{}', expected='{}'",
                        PiiMask.fio(signerCn), PiiMask.fio(expectedCn));
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of(K_ERROR, "CN в сертификате не совпадает"));
            }

            User user = existingUser != null ? existingUser
                    : userService.upsertAdminByCert(expectedCn, req.getSnils(), expectedRole);

            establishSession(user, request, response);
            audit.adminLoginSuccess(expectedCn);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "role", user.getRole().name(),
                    "redirect", user.getRole() == ru.afina.entity.Role.ROLE_ADMIN
                            ? "/admin/clients" : "/dashboard"));
        } catch (Exception e) {
            log.error("Cert login failed", e);
            audit.adminLoginFail("unknown", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(K_ERROR, "Не удалось войти: " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Онбординг клиента
    // ═══════════════════════════════════════════════════════

    /** Заполнение профиля (ФИО, телефон) при первом входе. */
    @PostMapping("/profile")
    public UserDto completeProfile(@RequestBody Map<String, String> body,
                                   Authentication auth) {
        User user = currentUser.entity(auth);
        String fullName = body.get("fullName");
        String phone = body.get("phone");
        userService.applyClientProfile(user.getId(), Map.of(
                "fullName", fullName == null ? "" : fullName,
                "phone", phone == null ? "" : phone));
        User saved = userService.findByEmail(user.getEmail()).orElse(user);
        return mapper.user(saved);
    }

    /** Документ согласия на обработку ПДн (создаётся по шаблону). */
    @GetMapping("/consent")
    public Map<String, Object> consent(Authentication auth) {
        User user = currentUser.entity(auth);
        if (!consentService.consentConfigured()) {
            return Map.of("configured", false);
        }
        Document doc = consentService.ensureConsentDocument(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", true);
        body.put("signed", consentService.consentSigned(user));
        body.put("documentId", doc.getId());
        return body;
    }

    // ═══════════════════════════════════════════════════════
    //  Служебное
    // ═══════════════════════════════════════════════════════

    /** Настройки входа для экрана логина (демо-ссылка, доступность сертификата). */
    @GetMapping("/options")
    public Map<String, Object> options() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("demoUrl", demoUrl);
        body.put("certLoginEnabled", true);
        body.put("tokenTtlHours", settings.getIntOrDefault("auth.token_ttl_hours", 10));
        return body;
    }

    private void establishSession(User user, HttpServletRequest request,
                                  HttpServletResponse response) {
        AuthUser principal = new AuthUser(user.getId(), user.getEmail(),
                user.getFullName(), user.getRole().name());
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, request, response);
    }

    private boolean consumeChallenge(String challenge) {
        if (challenge == null || challenge.isBlank()) return false;
        Long createdAt = challenges.remove(challenge);
        return createdAt != null && System.currentTimeMillis() - createdAt <= CHALLENGE_TTL_MS;
    }

    private void cleanOldChallenges() {
        long now = System.currentTimeMillis();
        challenges.entrySet().removeIf(e -> now - e.getValue() > CHALLENGE_TTL_MS * 2);
    }

    /** Служебный метод для тестов: сброс выданных челленджей. */
    void clearChallenges() {
        challenges.clear();
    }

    /** Проверка, что сессия действительно создана (используется в тестах). */
    static void requireAuthenticated(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
