package ru.afina.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.afina.entity.User;
import ru.afina.repository.UserRepository;
import ru.afina.security.AuthUser;
import ru.afina.service.ConsentService;

import java.io.IOException;

/**
 * Ограничения для клиента до прохождения онбординга.
 *
 * <p>Два уровня:
 * <ol>
 *   <li>профиль не заполнен (ФИО + телефон) — доступны только
 *       {@code /api/v1/auth/**} и публичные маршруты; остальное — 403
 *       с кодом {@code profile_required};</li>
 *   <li>профиль заполнен, но согласие на обработку ПДн не подписано —
 *       запрещено создание разделов и загрузка документов
 *       ({@code consent_required}).</li>
 * </ol>
 * Администраторов фильтр не касается.</p>
 */
@Component
public class ClientOnboardingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientOnboardingFilter.class);

    private final ConsentService consentService;
    private final UserRepository userRepository;

    public ClientOnboardingFilter(ConsentService consentService,
                                  UserRepository userRepository) {
        this.consentService = consentService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof AuthUser au)
                || !"ROLE_USER".equals(au.role())) {
            chain.doFilter(req, resp);
            return;
        }

        User user = userRepository.findById(au.id()).orElse(null);
        if (user == null) {
            chain.doFilter(req, resp);
            return;
        }

        String path = req.getRequestURI();

        if (consentService.needsProfile(user)) {
            if (isOnboardingAllowed(path)) {
                chain.doFilter(req, resp);
                return;
            }
            log.debug("Онбординг: клиент {} направлен на заполнение профиля (path={})",
                    user.getId(), path);
            deny(resp, "profile_required",
                    "Заполните профиль (ФИО и телефон), чтобы продолжить");
            return;
        }

        if (!consentService.consentSigned(user) && isDocumentCreation(path, req.getMethod())) {
            log.debug("Онбординг: клиенту {} запрещено создание документов (path={})",
                    user.getId(), path);
            deny(resp, "consent_required",
                    "Подпишите согласие на обработку персональных данных — "
                            + "без него нельзя создавать разделы и загружать документы");
            return;
        }

        chain.doFilter(req, resp);
    }

    /** Маршруты, доступные клиенту до заполнения профиля. */
    private static boolean isOnboardingAllowed(String path) {
        return path.startsWith("/api/v1/auth/")
                || path.startsWith("/api/v1/public/")
                || path.startsWith("/api/v1/health")
                || path.startsWith("/actuator");
    }

    /** Создание разделов и документов закрыто до подписания согласия. */
    private static boolean isDocumentCreation(String path, String method) {
        if (!"POST".equalsIgnoreCase(method)) return false;
        if (path.equals("/api/v1/folders")) return true;
        if (path.equals("/api/v1/documents")) return true;
        return path.matches("^/api/v1/templates/\\d+/generate$");
    }

    private static void deny(HttpServletResponse resp, String code, String message)
            throws IOException {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
