package ru.afina.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Защита демо-инстанса: блокирует то, что взаимодействует с инфраструктурой
 * (Docker, angie/TLS, ключи шифрования, бэкапы). Всё остальное (клиенты,
 * документы, подписи, доверенности, мониторинг, аудит, настройки) работает
 * по-настоящему — но на одноразовой H2-базе в памяти.
 */
@Component
@Profile("demo")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DemoGuardFilter extends OncePerRequestFilter {

    /** Префиксы, недоступные в демо (инфраструктура и секреты). */
    private static final List<String> BLOCKED_PREFIXES = List.of(
            "/admin/system",     // бэкапы, очистка, docker, рестарт приложения
            "/admin/ssl",        // TLS-сертификат, перезапуск Angie
            "/admin/keys",       // ключи шифрования, ротация, скачивание
            "/setup"             // мастер первичной настройки
    );

    /** Вход в админку основного приложения в демо недоступен — только авто-вход. */
    private static final List<String> ADMIN_LOGIN_PATHS = List.of(
            "/admin/login", "/admin/challenge", "/admin/cert-login",
            "/admin/logout"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String ctx = req.getContextPath() == null ? "" : req.getContextPath();
        String path = req.getRequestURI();
        if (!ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }

        // Страницы входа в «настоящую» админку в демо не показываем
        for (String loginPath : ADMIN_LOGIN_PATHS) {
            if (path.equals(loginPath) || path.startsWith(loginPath + "/")) {
                resp.sendRedirect(ctx + "/enter");
                return;
            }
        }

        for (String prefix : BLOCKED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.setContentType("text/html;charset=UTF-8");
                resp.getWriter().write("""
                        <!DOCTYPE html><html lang="ru"><head><meta charset="UTF-8">
                        <title>Афина · Демо</title>
                        <link rel="stylesheet" href="%s/css/style.css"></head>
                        <body class="auth-page"><div class="auth-card">
                        <h2>В демо-версии недоступно</h2>
                        <p class="subtitle">Раздел «%s» работает с инфраструктурой сервера
                        (Docker, TLS, ключи, бэкапы), поэтому в демо он отключён.
                        В полной версии — доступен.</p>
                        <p style="margin-top:20px"><a class="btn" href="%s/admin">← В демо-админку</a></p>
                        </div></body></html>
                        """.formatted(ctx, prefix, ctx));
                return;
            }
        }
        chain.doFilter(req, resp);
    }
}
