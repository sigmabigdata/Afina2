package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import ru.afina.entity.DocumentTemplate;
import ru.afina.entity.TemplateKind;
import ru.afina.entity.User;
import ru.afina.repository.DocumentTemplateRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Аутентификация: CSRF, текущий пользователь, magic-link, профиль и настройки входа.
 */
@DisplayName("AuthApi: CSRF, профиль, magic-link, настройки входа")
class AuthApiTest extends ApiTestBase {

    @Autowired
    private DocumentTemplateRepository templateRepository;

    // ──────────────────────────── CSRF / me ────────────────────────────

    @Test
    @DisplayName("csrf: 200, выдан токен")
    void csrfEndpoint() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.parameterName").value("_csrf"))
                .andReturn();

        JsonNode payload = json(result);
        assertThat(payload.get("token").asText()).isNotBlank();
        assertThat(payload.get("headerName").asText()).isNotBlank();
    }

    @Test
    @DisplayName("me без сессии → 401")
    void meAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("me с сессией → профиль текущего пользователя")
    void meAuthenticated() throws Exception {
        User client = createClient();

        mockMvc.perform(asGet(client, get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(client.getId()))
                .andExpect(jsonPath("$.email").value(client.getEmail()))
                .andExpect(jsonPath("$.fullName").value(client.getFullName()))
                .andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.documentsTotal").value(0))
                .andExpect(jsonPath("$.documentsSigned").value(0));
    }

    // ──────────────────────────── magic-link ────────────────────────────

    @Test
    @DisplayName("login-link для неизвестного адреса → 200 и не раскрывает существование")
    void loginLinkUnknownEmail() throws Exception {
        String unknown = "nobody-" + UUID.randomUUID() + "@test.local";

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login-link").with(csrf())
                        .contentType("application/json")
                        .content(body(Map.of("email", unknown))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andReturn();

        JsonNode payload = json(result);
        assertThat(payload.get("message").asText())
                .contains("Если адрес есть в системе");
        assertThat(payload.has("user")).isFalse();
        assertThat(payload.get("message").asText()).doesNotContain(unknown);
    }

    @Test
    @DisplayName("login-link без email → 400")
    void loginLinkWithoutEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login-link").with(csrf())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    @DisplayName("login-link с пустым email → 400")
    void loginLinkBlankEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login-link").with(csrf())
                        .contentType("application/json")
                        .content(body(Map.of("email", "   "))))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────────────── профиль ────────────────────────────

    @Test
    @DisplayName("profile: POST обновляет ФИО")
    void profileUpdatesFullName() throws Exception {
        User client = createClient();

        mockMvc.perform(as(client, post("/api/v1/auth/profile"))
                        .contentType("application/json")
                        .content(body(Map.of(
                                "fullName", "иванов иван иванович",
                                "phone", "+79991234567"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Иванов Иван Иванович"));

        assertThat(userRepository.findById(client.getId()).orElseThrow().getFullName())
                .isEqualTo("Иванов Иван Иванович");
    }

    /**
     * Найденный баг: {@code AuthApi.completeProfile} прокидывает {@code phone}
     * в {@code UserService.applyClientProfile}, но этот метод не содержит ветки
     * для ключа {@code phone} — телефон молча игнорируется и в БД не сохраняется.
     */
    @Test
    @DisplayName("profile: POST обновляет телефон")
    void profileUpdatesPhone() throws Exception {
        User client = createClient();

        mockMvc.perform(as(client, post("/api/v1/auth/profile"))
                        .contentType("application/json")
                        .content(body(Map.of(
                                "fullName", "петров петр петрович",
                                "phone", "+79990001122"))))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(client.getId()).orElseThrow().getPhone())
                .isEqualTo("+79990001122");
    }

    // ──────────────────────────── согласие ────────────────────────────

    @Test
    @DisplayName("consent без шаблона согласия → configured=false")
    void consentNotConfigured() throws Exception {
        // Шаблоны глобальны и могли быть созданы другими тестами — убираем CONSENT.
        List<DocumentTemplate> consents = templateRepository.findAll().stream()
                .filter(t -> t.getKind() == TemplateKind.CONSENT)
                .toList();
        if (!consents.isEmpty()) {
            templateRepository.deleteAll(consents);
        }

        User client = createClient();
        mockMvc.perform(asGet(client, get("/api/v1/auth/consent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.documentId").doesNotExist());
    }

    // ──────────────────────────── служебное ────────────────────────────

    @Test
    @DisplayName("options: настройки экрана входа")
    void options() throws Exception {
        User client = createClient();

        mockMvc.perform(asGet(client, get("/api/v1/auth/options")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certLoginEnabled").value(true))
                .andExpect(jsonPath("$.tokenTtlHours").value(10))
                .andExpect(jsonPath("$.demoUrl").exists());
    }

    @Test
    @DisplayName("logout → 200 success")
    void logout() throws Exception {
        User client = createClient();

        mockMvc.perform(as(client, post("/api/v1/auth/logout")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
