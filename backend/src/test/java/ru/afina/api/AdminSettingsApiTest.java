package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import ru.afina.entity.User;
import ru.afina.repository.AppSettingRepository;
import ru.afina.service.SettingsService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Админский API настроек: список с маскированием секретов, массовое обновление,
 * IP-whitelist, тест SMTP и права доступа.
 *
 * <p>Все ключи уникальны для теста ({@code test.*.<uuid>}), а IP-whitelist
 * сбрасывается в {@code @AfterEach} — иначе сохранённое значение заблокировало
 * бы админские запросы из остальных тестов.</p>
 */
class AdminSettingsApiTest extends ApiTestBase {

    private static final String BASE = "/api/v1/admin/settings";

    @Autowired
    private SettingsService settingsService;
    @Autowired
    private AppSettingRepository settingRepository;

    private User admin;
    private final List<String> createdKeys = new ArrayList<>();

    @BeforeEach
    void initAdmin() {
        admin = createAdmin();
    }

    @AfterEach
    void cleanup() {
        // Возвращаем «все IP разрешены», чтобы не сломать следующие тесты.
        settingsService.set("admin.ip_whitelist", "", "test");
        for (String key : createdKeys) {
            settingRepository.deleteById(key);
        }
        createdKeys.clear();
    }

    // ──────────────────────────── вспомогательное ────────────────────────────

    private static String suffix() {
        return UUID.randomUUID().toString();
    }

    private void putSettings(Map<String, String> values) throws Exception {
        for (String key : values.keySet()) createdKeys.add(key);
        mockMvc.perform(as(admin, put(BASE))
                        .contentType("application/json")
                        .content(body(values)))
                .andExpect(status().isOk());
    }

    /** Находит настройку по ключу в ответе GET /settings. */
    private static JsonNode findByKey(JsonNode array, String key) {
        for (JsonNode item : array) {
            if (key.equals(item.get("key").asText())) return item;
        }
        return null;
    }

    // ════════════════════════════ список и маскирование ════════════════════════════

    @Test
    @DisplayName("Список настроек: секретные значения маскируются, обычные видны")
    void listMasksSecrets() throws Exception {
        String secretKey = "test.secret.token." + suffix();
        String plainKey = "test.plain.host." + suffix();
        putSettings(Map.of(secretKey, "s3cr3t-value", plainKey, "visible-value"));

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        JsonNode list = json(result);
        JsonNode secret = findByKey(list, secretKey);
        JsonNode plain = findByKey(list, plainKey);

        assertThat(secret).isNotNull();
        assertThat(secret.get("value").asText()).isEqualTo("***");
        assertThat(secret.get("secret").asBoolean()).isTrue();

        assertThat(plain).isNotNull();
        assertThat(plain.get("value").asText()).isEqualTo("visible-value");
        assertThat(plain.get("secret").asBoolean()).isFalse();

        // само секретное значение наружу не уходит
        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("s3cr3t-value");
    }

    @Test
    @DisplayName("Массовое обновление пропускает секрет со значением «***»")
    void updateSkipsMaskedSecret() throws Exception {
        String secretKey = "test.secret.token." + suffix();
        String plainKey = "test.plain.host." + suffix();
        createdKeys.add(secretKey);
        createdKeys.add(plainKey);

        mockMvc.perform(as(admin, put(BASE))
                        .contentType("application/json")
                        .content(body(Map.of(secretKey, "real-secret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.skipped").value(0));

        // UI присылает «***» для неизменённых секретов
        mockMvc.perform(as(admin, put(BASE))
                        .contentType("application/json")
                        .content(body(Map.of(secretKey, "***", plainKey, "v2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.skipped").value(1));

        // в БД секрет остался прежним, наружу отдаётся маска
        assertThat(settingsService.get(secretKey)).contains("real-secret");
        assertThat(settingsService.get(plainKey)).contains("v2");

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secret = findByKey(json(result), secretKey);
        assertThat(secret.get("value").asText()).isEqualTo("***");
    }

    @Test
    @DisplayName("Пустой список настроек и пустой ключ → 400")
    void updateRejectsEmptyInput() throws Exception {
        mockMvc.perform(as(admin, put(BASE))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(as(admin, put(BASE))
                        .contentType("application/json")
                        .content("{\" \":\"значение\"}"))
                .andExpect(status().isBadRequest());
    }

    // ════════════════════════════ IP-whitelist ════════════════════════════

    @Test
    @DisplayName("IP-whitelist: чтение, сохранение и «*»")
    void ipWhitelistGetPut() throws Exception {
        MvcResult initial = mockMvc.perform(asGet(admin, get(BASE + "/ip-whitelist")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(initial).get("value").isTextual()).isTrue();

        mockMvc.perform(as(admin, put(BASE + "/ip-whitelist"))
                        .contentType("application/json")
                        .content(body(Map.of("value", "127.0.0.1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("127.0.0.1"));

        mockMvc.perform(asGet(admin, get(BASE + "/ip-whitelist")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("127.0.0.1"));

        mockMvc.perform(as(admin, put(BASE + "/ip-whitelist"))
                        .contentType("application/json")
                        .content(body(Map.of("value", "*"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("*"));
    }

    @Test
    @DisplayName("IP-whitelist: некорректные значения → 400")
    void ipWhitelistValidation() throws Exception {
        for (String bad : List.of("not-an-ip", "1.2.3", "example.com", "10.0.0.0/33",
                "999.999.999.999", "abc", "1.2.3.4/abc", "1.2.3.4/-1",
                "1.2.3.4.5", "::zz", "127.0.0.1, abc")) {
            mockMvc.perform(as(admin, put(BASE + "/ip-whitelist"))
                            .contentType("application/json")
                            .content(body(Map.of("value", bad))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"));
        }
    }

    @Test
    @DisplayName("IP-whitelist: IPv4, CIDR /24 и IPv6 сохраняются без искажений")
    void ipWhitelistAcceptsValidValues() throws Exception {
        for (String good : List.of("203.0.113.5", "203.0.113.0/24", "::1",
                "10.0.0.0/8", "192.168.1.1, 203.0.113.0/24")) {
            try {
                mockMvc.perform(as(admin, put(BASE + "/ip-whitelist"))
                                .contentType("application/json")
                                .content(body(Map.of("value", good))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.value").value(good));
            } finally {
                // Сбрасываем напрямую через сервис: при «чужом» whitelist
                // HTTP-запросы с 127.0.0.1 уже были бы запрещены (403).
                settingsService.set("admin.ip_whitelist", "", "test");
            }
        }
    }

    @Test
    @DisplayName("IP-whitelist: сохранённое значение читается обратно, если адрес разрешён")
    void ipWhitelistRoundTrip() throws Exception {
        for (String good : List.of("127.0.0.1", "127.0.0.1, 203.0.113.0/24", "*")) {
            try {
                mockMvc.perform(as(admin, put(BASE + "/ip-whitelist"))
                                .contentType("application/json")
                                .content(body(Map.of("value", good))))
                        .andExpect(status().isOk());

                mockMvc.perform(asGet(admin, get(BASE + "/ip-whitelist")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.value").value(good));
            } finally {
                settingsService.set("admin.ip_whitelist", "", "test");
            }
        }
    }

    @Test
    @DisplayName("IP-whitelist: пустое значение разрешает все адреса")
    void ipWhitelistEmptyValue() throws Exception {
        mockMvc.perform(as(admin, put(BASE + "/ip-whitelist"))
                        .contentType("application/json")
                        .content(body(Map.of("value", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(""));

        mockMvc.perform(asGet(admin, get(BASE + "/ip-whitelist")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(""));
    }

    @Test
    @DisplayName("Обновление: null-значение обычной настройки сохраняется как пустая строка")
    void updateNullValueBecomesEmpty() throws Exception {
        String plainKey = "test.null.value." + suffix();
        createdKeys.add(plainKey);

        mockMvc.perform(as(admin, put(BASE))
                        .contentType("application/json")
                        .content("{\"" + plainKey + "\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.skipped").value(0));

        assertThat(settingsService.get(plainKey)).contains("");
    }

    @Test
    @DisplayName("Обновление: null-значение секрета не стирает сохранённое")
    void updateNullSecretIsSkipped() throws Exception {
        String secretKey = "test.secret.null." + suffix();
        createdKeys.add(secretKey);
        settingsService.set(secretKey, "real-secret", "test");

        mockMvc.perform(as(admin, put(BASE))
                        .contentType("application/json")
                        .content("{\"" + secretKey + "\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(1));

        assertThat(settingsService.get(secretKey)).contains("real-secret");
    }

    // ════════════════════════════ SMTP ════════════════════════════

    @Test
    @DisplayName("Тест SMTP без настроенного сервера → 409")
    void smtpTestWithoutServer() throws Exception {
        mockMvc.perform(as(admin, post(BASE + "/smtp/test"))
                        .contentType("application/json")
                        .content(body(Map.of("to", "user@example.com"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("SMTP")));
    }

    @Test
    @DisplayName("Тест SMTP с некорректным email → 400")
    void smtpTestInvalidEmail() throws Exception {
        mockMvc.perform(as(admin, post(BASE + "/smtp/test"))
                        .contentType("application/json")
                        .content(body(Map.of("to", "без-собаки"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(as(admin, post(BASE + "/smtp/test"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ════════════════════════════ права доступа ════════════════════════════

    @Test
    @DisplayName("Не-администратор получает 403")
    void nonAdminForbidden() throws Exception {
        User client = createClient();

        mockMvc.perform(asGet(client, get(BASE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, put(BASE))
                        .contentType("application/json")
                        .content(body(Map.of("some.key", "value"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/ip-whitelist")))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, put(BASE + "/ip-whitelist"))
                        .contentType("application/json")
                        .content(body(Map.of("value", "*"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, post(BASE + "/smtp/test"))
                        .contentType("application/json")
                        .content(body(Map.of("to", "user@example.com"))))
                .andExpect(status().isForbidden());
    }
}
