package ru.afina.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Каждый посетитель демо получает СВОЙ тенант (сессию → отдельная H2 в памяти).
 * Тенант кладётся в HTTP-сессию и в ThreadLocal на время запроса, поэтому
 * пользователи демо не видят данные друг друга.
 */
@Component
@Profile("demo")
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class DemoTenantFilter extends OncePerRequestFilter {

    public static final String TENANT_ATTR = "DEMO_TENANT";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String tenant = null;
        try {
            HttpSession session = req.getSession(false);
            if (session != null) {
                Object v = session.getAttribute(TENANT_ATTR);
                if (v instanceof String s) {
                    tenant = s;
                }
            }
            DemoTenantContext.set(tenant);
            chain.doFilter(req, resp);
        } finally {
            DemoTenantContext.clear();
        }
    }
}
