package ru.afina.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import ru.afina.entity.User;
import ru.afina.service.UserService;

import java.util.List;

/**
 * Вход в демо-версию без сертификата: сессия создаётся сразу как ROLE_ADMIN
 * демо-администратора (только при активном профиле {@code demo}).
 *
 * <p>Демо-инстанс работает на отдельной H2-базе в памяти — реальная БД и
 * инфраструктура недоступны (см. {@link DemoGuardFilter}).
 */
@Controller
@Profile("demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final UserService userService;
    private final HttpSessionSecurityContextRepository adminContextRepository;
    private final DemoTenantService tenantService;

    @Value("${app.demo.admin-email:demo-admin@afina.local}")
    private String demoAdminEmail;

    public DemoController(UserService userService,
                          @Qualifier("adminContextRepository")
                          HttpSessionSecurityContextRepository adminContextRepository,
                          DemoTenantService tenantService) {
        this.userService = userService;
        this.adminContextRepository = adminContextRepository;
        this.tenantService = tenantService;
    }

    @GetMapping("/enter")
    public String enter(HttpServletRequest request, HttpServletResponse response) {
        // Каждому посетителю — своя сессия и своя база данных
        var session = request.getSession(true);
        String tenant = (String) session.getAttribute(DemoTenantFilter.TENANT_ATTR);
        if (tenant == null) {
            tenant = DemoTenantContext.newTenantId();
            session.setAttribute(DemoTenantFilter.TENANT_ATTR, tenant);
        }
        DemoTenantContext.set(tenant);
        tenantService.ensureSeeded(tenant);

        User admin = userService.findByEmail(demoAdminEmail).orElse(null);
        if (admin == null) {
            log.warn("demo: демо-администратор {} не найден — проверьте DemoDataInitializer", demoAdminEmail);
            return "redirect:/login?expired";
        }

        var details = org.springframework.security.core.userdetails.User
                .withUsername(admin.getEmail())
                .password("{noop}demo")
                .authorities(List.of(new SimpleGrantedAuthority(admin.getRole().name())))
                .build();

        var auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        adminContextRepository.saveContext(ctx, request, response);

        log.info("demo: вход открыт для {}", demoAdminEmail);
        return "redirect:/admin";
    }
}
