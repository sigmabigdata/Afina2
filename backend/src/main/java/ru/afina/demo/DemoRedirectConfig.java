package ru.afina.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * В демо нет входа по ссылке из письма — страница входа бессмысленна.
 * Любой GET на {@code /login} (в том числе редирект с корня {@code /})
 * уводим на авто-вход {@code /enter} → сразу демо-админка.
 */
@Configuration
@Profile("demo")
public class DemoRedirectConfig implements WebMvcConfigurer {

    /**
     * GET {@code /login} в демо уводим на авто-вход {@code /enter}.
     * Пакетная видимость — чтобы покрыть тестом.
     */
    static boolean demoLoginRedirect(HttpServletRequest req, HttpServletResponse res)
            throws java.io.IOException {
        String ctx = req.getContextPath() == null ? "" : req.getContextPath();
        String path = req.getRequestURI();
        if (!ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        if ("GET".equalsIgnoreCase(req.getMethod()) && "/login".equals(path)) {
            res.sendRedirect(ctx + "/enter");
            return false;
        }
        return true;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
                    throws java.io.IOException {
                return demoLoginRedirect(req, res);
            }
        }).addPathPatterns("/login");
    }
}
