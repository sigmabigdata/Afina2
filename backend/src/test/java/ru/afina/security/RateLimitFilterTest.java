package ru.afina.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.afina.service.SettingsService;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Поведение фильтра rate-limit без поднятия Spring-контекста.
 *
 * <p>Фильтр проверяется напрямую: в тестовом профиле лимиты могут быть
 * выключены настройкой {@code app.rate-limit.enabled}, поэтому интеграционный
 * прогон через MockMvc нестабилен. Здесь фильтр создаётся конструктором —
 * это ровно тот же объект, который регистрирует Spring.</p>
 */
@DisplayName("RateLimitFilter: лимиты по IP для критичных endpoint")
class RateLimitFilterTest {

    private RateLimitService rateLimit;
    private RateLimitFilter filter;

    /** Результат одного прогона фильтра. */
    private record Run(MockHttpServletResponse response, boolean passed) {
    }

    @BeforeEach
    void setUp() {
        rateLimit = new RateLimitService();
        filter = new RateLimitFilter(rateLimit, null);
    }

    // ──────────────────────────── помощники ────────────────────────────

    /** Прогоняет запрос через фильтр; remoteAddr — доверенный прокси (loopback). */
    private Run call(String method, String path, String clientIp) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr("127.0.0.1");
        // Фильтр берёт реальный IP из X-Forwarded-For, т.к. remoteAddr — loopback
        req.addHeader("X-Forwarded-For", clientIp);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        boolean passed = chain.getRequest() != null;
        return new Run(resp, passed);
    }

    private Run post(String path, String clientIp) throws Exception {
        return call("POST", path, clientIp);
    }

    // ──────────────────────────── сценарии ────────────────────────────

    @Test
    @DisplayName("login-link: 5 попыток проходят, 6-я с того же IP → 429")
    void loginLinkLimitedAfterFiveAttempts() throws Exception {
        String ip = "203.0.113.10";

        for (int i = 1; i <= 5; i++) {
            Run run = post("/api/v1/auth/login-link", ip);
            assertThat(run.passed()).as("попытка %d должна пройти", i).isTrue();
            assertThat(run.response().getStatus()).isEqualTo(200);
        }

        Run blocked = post("/api/v1/auth/login-link", ip);

        assertThat(blocked.passed()).isFalse();
        assertThat(blocked.response().getStatus()).isEqualTo(429);
        assertThat(blocked.response().getHeader("Retry-After")).isNotNull();
        assertThat(Long.parseLong(blocked.response().getHeader("Retry-After"))).isGreaterThan(0);
        assertThat(blocked.response().getContentType()).contains("application/json");
        assertThat(blocked.response().getContentAsString())
                .contains("Слишком много попыток");
    }

    @Test
    @DisplayName("login-link: лимит считается отдельно для каждого IP")
    void loginLinkLimitIsPerIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/v1/auth/login-link", "203.0.113.11").passed()).isTrue();
        }
        assertThat(post("/api/v1/auth/login-link", "203.0.113.11").passed()).isFalse();
        // Другой клиент не страдает от чужого лимита
        assertThat(post("/api/v1/auth/login-link", "203.0.113.12").passed()).isTrue();
    }

    @Test
    @DisplayName("Обычные запросы не ограничиваются")
    void ordinaryRequestsAreNotLimited() throws Exception {
        for (int i = 0; i < 30; i++) {
            Run run = call("GET", "/api/v1/documents", "203.0.113.13");
            assertThat(run.passed()).isTrue();
            assertThat(run.response().getStatus()).isEqualTo(200);
        }
        Run unknown = post("/api/v1/unknown-endpoint", "203.0.113.13");
        assertThat(unknown.passed()).isTrue();
    }

    @Test
    @DisplayName("login-link ограничивается только для POST")
    void loginLinkGetIsNotLimited() throws Exception {
        for (int i = 0; i < 8; i++) {
            assertThat(call("GET", "/api/v1/auth/login-link", "203.0.113.14").passed())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Челлендж сертификата: лимит 10 на IP")
    void challengeLimitedAfterTenAttempts() throws Exception {
        String ip = "203.0.113.15";
        for (int i = 1; i <= 10; i++) {
            assertThat(post("/api/v1/auth/admin/challenge", ip).passed())
                    .as("попытка %d", i).isTrue();
        }
        Run blocked = post("/api/v1/auth/admin/challenge", ip);
        assertThat(blocked.passed()).isFalse();
        assertThat(blocked.response().getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("Вход по сертификату: лимит 5 на IP (только POST)")
    void certLoginLimitedAfterFiveAttempts() throws Exception {
        String ip = "203.0.113.16";
        for (int i = 0; i < 5; i++) {
            assertThat(call("GET", "/api/v1/auth/admin/cert-login", ip).passed())
                    .as("GET не ограничивается").isTrue();
        }
        for (int i = 1; i <= 5; i++) {
            assertThat(post("/api/v1/auth/admin/cert-login", ip).passed())
                    .as("POST №%d", i).isTrue();
        }
        assertThat(post("/api/v1/auth/admin/cert-login", ip).passed()).isFalse();
    }

    @Test
    @DisplayName("Переход по ссылке из письма: лимит 20 на IP")
    void confirmLimitedAfterTwentyAttempts() throws Exception {
        String ip = "203.0.113.17";
        for (int i = 1; i <= 20; i++) {
            assertThat(call("GET", "/api/v1/auth/confirm", ip).passed())
                    .as("переход №%d", i).isTrue();
        }
        assertThat(call("GET", "/api/v1/auth/confirm", ip).passed()).isFalse();
    }

    @Test
    @DisplayName("Лимит и окно берутся из настроек и ограничиваются clamp'ом")
    void limitsComeFromSettingsWithClamp() throws Exception {
        SettingsService settings = Mockito.mock(SettingsService.class);
        when(settings.getIntOrDefault(anyString(), anyInt())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return switch (key) {
                case "auth.rate_limit.login" -> 2;        // свой лимит
                case "auth.rate_limit.window_min" -> 0;   // clamp снизу до 1 минуты
                default -> inv.getArgument(1);
            };
        });
        filter = new RateLimitFilter(rateLimit, settings);

        String ip = "203.0.113.18";
        assertThat(post("/api/v1/auth/login-link", ip).passed()).isTrue();
        assertThat(post("/api/v1/auth/login-link", ip).passed()).isTrue();
        Run blocked = post("/api/v1/auth/login-link", ip);
        assertThat(blocked.passed()).isFalse();
        assertThat(blocked.response().getStatus()).isEqualTo(429);
        // окно 1 минута → Retry-After не превышает 60 секунд
        assertThat(Long.parseLong(blocked.response().getHeader("Retry-After")))
                .isBetween(1L, 60L);

        assertThat(rateLimit.retryAfterSeconds("login:" + ip)).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("Слишком большой лимит из настроек ограничивается сверху 100")
    void limitIsClampedToHundred() throws Exception {
        SettingsService settings = Mockito.mock(SettingsService.class);
        when(settings.getIntOrDefault(eq("auth.rate_limit.login"), anyInt())).thenReturn(500);
        when(settings.getIntOrDefault(eq("auth.rate_limit.window_min"), anyInt()))
                .thenReturn(24 * 60 + 100);
        filter = new RateLimitFilter(rateLimit, settings);

        String ip = "203.0.113.19";
        for (int i = 1; i <= 100; i++) {
            assertThat(post("/api/v1/auth/login-link", ip).passed())
                    .as("попытка %d должна пройти", i).isTrue();
        }
        assertThat(post("/api/v1/auth/login-link", ip).passed()).isFalse();
    }

    @Test
    @DisplayName("Context path (демо-режим) снимается перед сравнением пути")
    void contextPathIsStripped() throws Exception {
        String ip = "203.0.113.20";
        for (int i = 1; i <= 5; i++) {
            MockHttpServletRequest req =
                    new MockHttpServletRequest("POST", "/demo/api/v1/auth/login-link");
            req.setContextPath("/demo");
            req.setRemoteAddr("127.0.0.1");
            req.addHeader("X-Forwarded-For", ip);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertThat(chain.getRequest()).as("попытка %d", i).isNotNull();
        }

        MockHttpServletRequest req =
                new MockHttpServletRequest("POST", "/demo/api/v1/auth/login-link");
        req.setContextPath("/demo");
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Forwarded-For", ip);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(resp.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("Сервис лимитов: tryConsume и retryAfterSeconds согласованы")
    void rateLimitServiceContract() {
        String key = "unit:" + System.nanoTime();
        Duration window = Duration.ofMinutes(1);

        assertThat(rateLimit.tryConsume(key, 1, window)).isTrue();
        assertThat(rateLimit.tryConsume(key, 1, window)).isFalse();
        assertThat(rateLimit.retryAfterSeconds(key)).isBetween(1L, 60L);
        // ключ без корзины → 0 секунд ожидания
        assertThat(rateLimit.retryAfterSeconds("absent:" + key)).isZero();
    }
}
