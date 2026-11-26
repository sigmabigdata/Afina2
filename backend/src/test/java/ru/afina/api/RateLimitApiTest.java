package ru.afina.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сквозная проверка rate-limit через MockMvc.
 *
 * <p>Лимиты включаются свойством {@code app.rate-limit.enabled=true}: в общем
 * тестовом профиле они выключены, чтобы тесты не мешали друг другу. IP клиента
 * передаётся через {@code X-Forwarded-For} — MockMvc видит loopback-адрес,
 * который считается доверенным прокси, поэтому лимит считается по нашему IP.</p>
 */
@SpringBootTest(properties = "app.rate-limit.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("RateLimitFilter: 429 на /api/v1/auth/login-link")
class RateLimitApiTest {

    private static final String LOGIN_LINK = "/api/v1/auth/login-link";
    private static final String BODY = "{\"email\":\"nobody@test.local\"}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("6-й POST с одного IP → 429 и Retry-After")
    void sixthLoginLinkAttemptIsRejected() throws Exception {
        String clientIp = "198.51.100.77";

        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post(LOGIN_LINK)
                            .with(csrf())
                            .header("X-Forwarded-For", clientIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post(LOGIN_LINK)
                        .with(csrf())
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("Другой IP не блокируется, обычные запросы тоже")
    void otherIpAndOrdinaryRequestsAreNotLimited() throws Exception {
        for (int i = 0; i < 8; i++) {
            mockMvc.perform(get("/api/v1/public/info/" + UUID.randomUUID())
                            .header("X-Forwarded-For", "198.51.100.78"))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(post(LOGIN_LINK)
                        .with(csrf())
                        .header("X-Forwarded-For", "198.51.100.78")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());
    }
}
