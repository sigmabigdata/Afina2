package ru.afina.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.afina.entity.User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Операционные разделы админки: TLS, ключи шифрования, резервные копии,
 * мониторинг. Проверяются и рабочие сценарии, и валидация запросов —
 * именно она защищает от опасных операций.
 */
class AdminOpsApiTest extends ApiTestBase {

    // ═════════════════════════════ TLS ═════════════════════════════

    @Test
    @DisplayName("SSL: статус без сертификата")
    void sslStatus() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(asGet(admin, get("/api/v1/admin/ssl")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCertificate").isBoolean())
                .andExpect(jsonPath("$.acmeAvailable").isBoolean());
    }

    @Test
    @DisplayName("SSL: пустой запрос на загрузку отклоняется")
    void sslUploadEmpty() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, post("/api/v1/admin/ssl"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SSL: загрузка не-PEM отклоняется")
    void sslUploadInvalidPem() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, post("/api/v1/admin/ssl"))
                        .contentType("application/json")
                        .content(body(Map.of("certPem", "не сертификат", "keyPem", "не ключ"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SSL: ключ без маркера PRIVATE KEY отклоняется")
    void sslUploadKeyWithoutMarker() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, post("/api/v1/admin/ssl"))
                        .contentType("application/json")
                        .content(body(Map.of(
                                "certPem", "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----",
                                "keyPem", "-----BEGIN SOMETHING-----\nAAAA\n-----END SOMETHING-----"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("PRIVATE KEY")));
    }

    @Test
    @DisplayName("SSL: удаление без сертификата — понятная ошибка, не 500")
    void sslDeleteWithoutCertificate() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, delete("/api/v1/admin/ssl")))
                .andExpect(status().is4xxClientError());
    }

    // ═════════════════════════════ КЛЮЧИ ═════════════════════════════

    @Test
    @DisplayName("Ключи: список с версиями и метаданными")
    void keysList() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(asGet(admin, get("/api/v1/admin/keys")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.piiVersion").isNumber())
                .andExpect(jsonPath("$.fileVersion").isNumber())
                .andExpect(jsonPath("$.minPasswordLength").value(12))
                .andExpect(jsonPath("$.keys").isArray());
    }

    @Test
    @DisplayName("Ключи: выгрузка без пароля отклоняется")
    void keysDownloadNoPassword() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, post("/api/v1/admin/keys/download"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Ключи: короткий пароль отклоняется")
    void keysDownloadShortPassword() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, post("/api/v1/admin/keys/download"))
                        .contentType("application/json")
                        .content(body(Map.of("password", "short", "confirmPassword", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("12")));
    }

    @Test
    @DisplayName("Ключи: несовпадающие пароли отклоняются")
    void keysDownloadPasswordMismatch() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, post("/api/v1/admin/keys/download"))
                        .contentType("application/json")
                        .content(body(Map.of("password", "very-long-password-1",
                                "confirmPassword", "very-long-password-2"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("совпадают")));
    }

    @Test
    @DisplayName("Ключи: выгрузка ZIP с корректным паролем")
    void keysDownloadZip() throws Exception {
        User admin = createAdmin();
        byte[] zip = mockMvc.perform(as(admin, post("/api/v1/admin/keys/download"))
                        .contentType("application/json")
                        .content(body(Map.of("password", "correct-horse-battery",
                                "confirmPassword", "correct-horse-battery"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(zip.length).isGreaterThan(100);
        assertThat(zip[0]).isEqualTo((byte) 'P');
        assertThat(zip[1]).isEqualTo((byte) 'K');
    }

    @Test
    @DisplayName("Ключи: ротация PII и файлового ключа увеличивает версию")
    void keysRotation() throws Exception {
        User admin = createAdmin();
        int before = json(mockMvc.perform(asGet(admin, get("/api/v1/admin/keys")))
                .andReturn()).get("piiVersion").asInt();

        mockMvc.perform(as(admin, post("/api/v1/admin/keys/rotate/pii")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(before + 1));

        mockMvc.perform(as(admin, post("/api/v1/admin/keys/rotate/file")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("file"));
    }

    // ═════════════════════════════ БЭКАПЫ ═════════════════════════════

    @Test
    @DisplayName("Бэкапы: список доступен")
    void backupsList() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(asGet(admin, get("/api/v1/admin/backups")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Бэкапы: имя с path traversal отклоняется")
    void backupsTraversal() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, delete("/api/v1/admin/backups/..%2F..%2Fetc%2Fpasswd")))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(asGet(admin, get("/api/v1/admin/backups/bad%20name.zip/download")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Бэкапы: восстановление несуществующего файла → 404")
    void backupsRestoreMissing() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, post("/api/v1/admin/backups/nonexistent.tar.gz.enc/restore")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Бэкапы: скачивание несуществующего → 404")
    void backupsDownloadMissing() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(asGet(admin, get("/api/v1/admin/backups/nonexistent.tar.gz.enc/download")))
                .andExpect(status().isNotFound());
    }

    // ═════════════════════════════ МОНИТОРИНГ ═════════════════════════════

    @Test
    @DisplayName("Мониторинг: проверки компонентов возвращают список")
    void monitorChecks() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(asGet(admin, get("/api/v1/admin/monitor/checks")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].component").isNotEmpty())
                .andExpect(jsonPath("$[0].healthy").isBoolean());
    }

    @Test
    @DisplayName("Мониторинг: история проверок и журнал приложения")
    void monitorHistoryAndLogs() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(asGet(admin, get("/api/v1/admin/monitor/history").param("limit", "10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(asGet(admin, get("/api/v1/admin/monitor/logs").param("lines", "50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isArray());
    }

    @Test
    @DisplayName("Мониторинг: сведения о системе")
    void systemInfo() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(asGet(admin, get("/api/v1/admin/system")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtime").exists())
                .andExpect(jsonPath("$.memory").exists());
    }

    @Test
    @DisplayName("Мониторинг: тестовое оповещение без SMTP → 409")
    void monitorTestAlertWithoutSmtp() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, post("/api/v1/admin/monitor/test-alert")))
                .andExpect(status().is4xxClientError());
    }

    // ═════════════════════════════ ДОСТУП ═════════════════════════════

    @Test
    @DisplayName("Операционные разделы закрыты для клиента")
    void opsForbiddenForClient() throws Exception {
        User client = createClient();
        for (String path : new String[]{"/api/v1/admin/keys", "/api/v1/admin/backups",
                "/api/v1/admin/ssl", "/api/v1/admin/monitor/checks", "/api/v1/admin/system"}) {
            mockMvc.perform(asGet(client, get(path)))
                    .andExpect(status().isForbidden());
        }
    }
}
