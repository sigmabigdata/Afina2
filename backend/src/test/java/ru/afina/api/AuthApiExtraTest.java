package ru.afina.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import ru.afina.entity.DocumentTemplate;
import ru.afina.entity.TemplateKind;
import ru.afina.entity.User;
import ru.afina.repository.DocumentTemplateRepository;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Дополнительные сценарии аутентификации: ссылка входа, согласие, опции. */
class AuthApiExtraTest extends ApiTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    private DocumentTemplateRepository templateRepository;

    @Test
    @DisplayName("login-link: неизвестный адрес не раскрывает существование")
    void loginLinkUnknownEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login-link").with(csrf())
                        .contentType("application/json")
                        .content(body(Map.of("email", "unknown@nowhere.local"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));
    }

    @Test
    @DisplayName("login-link: без адреса → 400")
    void loginLinkWithoutEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login-link").with(csrf())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("login-link: существующий клиент — ссылка создаётся")
    void loginLinkKnownEmail() throws Exception {
        User client = createClient();
        mockMvc.perform(post("/api/v1/auth/login-link").with(csrf())
                        .contentType("application/json")
                        .content(body(Map.of("email", client.getEmail()))))
                .andExpect(status().isOk());
        assertThat(userRepository.findById(client.getId()).orElseThrow().getLoginToken())
                .isNotNull();
    }

    @Test
    @DisplayName("confirm: недействительный токен → редирект на форму входа с ошибкой")
    void confirmInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/confirm").param("token", "invalid-token"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login?error=token"));
    }

    @Test
    @DisplayName("confirm: валидный токен → сессия и редирект в кабинет")
    void confirmValidToken() throws Exception {
        User client = createClient();
        String token = userServiceLink(client);
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/api/v1/auth/confirm").param("token", token).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard"));
        assertThat(session.getId()).isNotEmpty();
    }

    /** Генерирует ссылку входа и возвращает токен. */
    private String userServiceLink(User client) {
        var userService = applicationContext.getBean(ru.afina.service.UserService.class);
        String link = userService.generateLoginLink(client.getEmail(), "http://localhost");
        assertThat(link).isNotNull();
        return link.substring(link.indexOf("token=") + 6);
    }

    @Test
    @DisplayName("options: настройки экрана входа")
    void options() throws Exception {
        mockMvc.perform(get("/api/v1/auth/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certLoginEnabled").value(true))
                .andExpect(jsonPath("$.tokenTtlHours").isNumber());
    }

    @Test
    @DisplayName("consent: без шаблона согласия — configured=false")
    void consentNotConfigured() throws Exception {
        User client = createClient();
        mockMvc.perform(asGet(client, get("/api/v1/auth/consent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    @DisplayName("consent: с шаблоном — документ создаётся и связан с клиентом")
    void consentConfigured() throws Exception {
        User client = createClient();
        // согласие формируется только при заполненном профиле (ФИО + телефон)
        client.setPhone("+79001234567");
        client.setPhoneHash(pii.hash("+79001234567"));
        userRepository.save(client);
        createConsentTemplate();

        mockMvc.perform(asGet(client, get("/api/v1/auth/consent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.signed").value(false))
                .andExpect(jsonPath("$.documentId").isNumber());
    }

    @Test
    @DisplayName("challenge: выдаётся одноразовое значение")
    void challenge() throws Exception {
        mockMvc.perform(get("/api/v1/auth/admin/challenge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").isNotEmpty());
    }

    @Test
    @DisplayName("cert-login: без челленджа → 400")
    void certLoginWithoutChallenge() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/cert-login").with(csrf())
                        .contentType("application/json")
                        .content(body(Map.of("cn", "Тест", "snils", "12345678901",
                                "challenge", "unknown", "signatureBase64", "AAAA"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("logout: сессия завершается")
    void logout() throws Exception {
        User client = createClient();
        mockMvc.perform(as(client, post("/api/v1/auth/logout")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private void createConsentTemplate() {
        DocumentTemplate t = new DocumentTemplate();
        t.setKind(TemplateKind.CONSENT);
        t.setName("Согласие на обработку ПДн");
        t.setHtmlContent("<h1>Согласие</h1><p>{{fullName}}</p><p>{{date}}</p>");
        t.setFieldsJson("[{\"key\":\"fullName\",\"label\":\"ФИО\",\"type\":\"text\",\"required\":true},"
                + "{\"key\":\"date\",\"label\":\"Дата\",\"type\":\"date\",\"required\":true}]");
        t.setActive(true);
        templateRepository.save(t);
    }
}
