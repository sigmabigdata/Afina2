package ru.afina.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import ru.afina.security.ClientIp;

/**
 * Безопасность Afina2: SPA (React) + REST API {@code /api/v1}.
 *
 * <p>Модель:
 * <ul>
 *   <li>сессия на сервере (cookie {@code AFINA2SESSION}, HttpOnly, SameSite=Lax);</li>
 *   <li>CSRF — double-submit cookie: SPA читает {@code XSRF-TOKEN} и отправляет
 *       заголовок {@code X-XSRF-TOKEN};</li>
 *   <li>неавторизованный запрос к API — JSON 401 (без редиректов);</li>
 *   <li>административные маршруты дополнительно закрыты IP-whitelist;</li>
 *   <li>CSP без {@code unsafe-eval}: плагин КриптоПро подключается с
 *       cryptopro.ru, инлайн-скрипты запрещены (Vite собирает бандл).</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** CSP для SPA: только self + cryptopro.ru (плагин подписи). */
    private static final String CSP = "default-src 'self'; "
            + "script-src 'self' https://www.cryptopro.ru; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; "
            + "font-src 'self' data:; "
            + "connect-src 'self'; "
            + "frame-src 'self' blob:; "
            + "object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'self'";

    @Value("${app.admin-ip-whitelist:*}")
    private String adminIpWhitelist;

    @Value("${app.demo.enabled:false}")
    private boolean demoMode;

    @Value("${app.cors-origins:}")
    private String corsOrigins;

    @org.springframework.beans.factory.annotation.Autowired
    private ru.afina.service.SettingsService settingsService;

    /**
     * Хранилище контекста безопасности в HTTP-сессии.
     * Нужно для программного входа (magic-link, сертификат): после установки
     * аутентификации контроллер явно сохраняет контекст.
     */
    @Bean
    public org.springframework.security.web.context.SecurityContextRepository
            securityContextRepository() {
        return new org.springframework.security.web.context.HttpSessionSecurityContextRepository();
    }

    // ═══════════════════════════════════════════════════════
    //  CORS (dev: Vite на 5173; prod: same-origin через Angie)
    // ═══════════════════════════════════════════════════════

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origins = Arrays.stream(corsOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (!origins.isEmpty()) {
            cfg.setAllowedOrigins(origins);
            cfg.setAllowCredentials(true);
            cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            cfg.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "X-Requested-With",
                    "Accept", "Origin", "Authorization"));
            cfg.setExposedHeaders(List.of("Content-Disposition"));
            cfg.setMaxAge(3600L);
        }
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    // ═══════════════════════════════════════════════════════
    //  IP-фильтр админки
    // ═══════════════════════════════════════════════════════

    private String currentWhitelist() {
        String fromDb = settingsService.getOrDefault("admin.ip_whitelist", "");
        return fromDb.isBlank() ? adminIpWhitelist : fromDb;
    }

    private Filter adminIpFilter() {
        return new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                if (demoMode) {
                    chain.doFilter(req, res);
                    return;
                }
                // Фильтр относится только к административным маршрутам:
                // health и публичные эндпоинты должны быть доступны мониторингу
                String path = ((HttpServletRequest) req).getRequestURI();
                if (!path.startsWith("/api/v1/admin")) {
                    chain.doFilter(req, res);
                    return;
                }
                String whitelistStr = currentWhitelist();
                List<String> allowed = Arrays.stream(whitelistStr.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
                if (allowed.contains("*")) {
                    chain.doFilter(req, res);
                    return;
                }
                String ip = ClientIp.resolve((HttpServletRequest) req);
                if (!isAllowedIp(ip, allowed)) {
                    log.warn("Blocked admin API access from IP: {} (whitelist: {})",
                            ip, whitelistStr);
                    HttpServletResponse response = (HttpServletResponse) res;
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"error\":\"forbidden\",\"message\":\"Доступ к админке "
                                    + "разрешён только с доверенных адресов\"}");
                    return;
                }
                chain.doFilter(req, res);
            }
        };
    }

    private static boolean isAllowedIp(String ip, List<String> allowed) {
        if (ip == null || ip.isBlank()) return false;
        for (String pattern : allowed) {
            if (pattern.equals(ip)) return true;
            if (pattern.indexOf('/') > 0 && isInCidr(ip, pattern)) return true;
        }
        return false;
    }

    private static boolean isInCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/", 2);
            if (parts.length != 2) return false;
            java.net.InetAddress network = java.net.InetAddress.getByName(parts[0]);
            java.net.InetAddress address = java.net.InetAddress.getByName(ip);
            byte[] networkBytes = network.getAddress();
            byte[] addressBytes = address.getAddress();
            if (networkBytes.length != addressBytes.length) return false;
            if (networkBytes.length != 4) return false;
            int prefixLen = Integer.parseInt(parts[1]);
            if (prefixLen < 0 || prefixLen > 32) return false;
            int fullBytes = prefixLen / 8;
            int remainingBits = prefixLen % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != addressBytes[i]) return false;
            }
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((networkBytes[fullBytes] & mask) != (addressBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Filter chain
    // ═══════════════════════════════════════════════════════

    @Bean
    public SecurityFilterChain apiChain(
            HttpSecurity http,
            org.springframework.security.web.context.SecurityContextRepository contextRepository)
            throws Exception {
        log.info("Afina2 admin IP whitelist: {}", currentWhitelist());

        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        // Не откладывать генерацию: SPA должна получить cookie XSRF-TOKEN сразу
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            .cors(c -> c.configurationSource(corsConfigurationSource()))
            .securityContext(sc -> sc
                    .securityContextRepository(contextRepository)
                    .requireExplicitSave(true))
            .sessionManagement(s -> s
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(f -> f.changeSessionId()))
            .headers(h -> h
                    .frameOptions(f -> f.sameOrigin())
                    .contentSecurityPolicy(c -> c.policyDirectives(CSP)))
            .csrf(c -> c
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(csrfHandler)
                    // Переход по ссылке из письма — GET без токена
                    .ignoringRequestMatchers(
                            "/api/v1/auth/confirm",
                            "/api/v1/auth/cert-login",
                            "/api/v1/auth/admin/challenge",
                            "/api/v1/auth/admin/cert-login",
                            // публичный приём диагностики плагина (только запись в лог)
                            "/api/v1/public/client-log",
                            "/actuator/**"))
            .addFilterBefore(adminIpFilter(), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                    // ── публичное ──
                    .requestMatchers(
                            "/actuator/health", "/actuator/health/**", "/actuator/info",
                            "/api/v1/health",
                            "/api/v1/auth/csrf", "/api/v1/auth/login-link",
                            // /dev-login существует только при app.dev-login.enabled=true
                            "/api/v1/auth/dev-login",
                            "/api/v1/auth/confirm", "/api/v1/auth/cert-login",
                            "/api/v1/auth/admin/challenge", "/api/v1/auth/admin/cert-login",
                            "/api/v1/auth/options",
                            "/api/v1/public/**",
                            "/api/v1/openapi/**", "/api/v1/swagger/**",
                            "/api/v1/swagger-ui/**", "/v3/api-docs/**")
                    .permitAll()
                    // ── админка ──
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    // ── всё остальное — только авторизованным ──
                    .anyRequest().authenticated())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .exceptionHandling(e -> e
                    .authenticationEntryPoint((req, res, ex) -> {
                        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        res.setContentType("application/json;charset=UTF-8");
                        res.getWriter().write(
                                "{\"error\":\"unauthorized\",\"message\":\"Требуется вход\"}");
                    })
                    .accessDeniedHandler((req, res, ex) -> {
                        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        res.setContentType("application/json;charset=UTF-8");
                        res.getWriter().write(
                                "{\"error\":\"forbidden\",\"message\":\"Недостаточно прав\"}");
                    }));

        return http.build();
    }
}
