package ru.afina.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.afina.entity.User;
import ru.afina.security.AuthUser;
import ru.afina.service.UserService;

import java.io.IOException;

/**
 * Упрощённый вход для локальной разработки и демонстрационного стенда.
 *
 * <p><b>Включается только явно</b> ({@code app.dev-login.enabled=true}) и никогда
 * не активируется в production-профиле: в проде вход выполняется по magic-link
 * или сертификату УКЭП. Бин не создаётся, если флаг выключен, поэтому endpoint
 * физически отсутствует.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "app.dev-login", name = "enabled", havingValue = "true")
public class DevLoginApi {

    private static final Logger log = LoggerFactory.getLogger(DevLoginApi.class);

    private final UserService userService;
    private final SecurityContextRepository contextRepository;

    public DevLoginApi(UserService userService, SecurityContextRepository contextRepository) {
        this.userService = userService;
        this.contextRepository = contextRepository;
        log.warn("=== DEV-LOGIN ВКЛЮЧЁН (app.dev-login.enabled=true) — только для разработки ===");
    }

    /** Вход под указанным e-mail без пароля (dev/demo). */
    @GetMapping("/dev-login")
    public void devLogin(@RequestParam("email") String email,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        User user = userService.findByEmail(email).orElse(null);
        if (user == null || !user.isEnabled()) {
            response.sendRedirect("/login?error=dev");
            return;
        }
        AuthUser principal = new AuthUser(user.getId(), user.getEmail(),
                user.getFullName(), user.getRole().name());
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, request, response);
        log.info("DEV-LOGIN: {} (роль {})", user.getFullName(), user.getRole());
        response.sendRedirect("/dashboard");
    }
}
