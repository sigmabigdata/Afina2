package ru.afina.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import ru.afina.entity.User;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Госключ и CRL в админке: состояния, ошибки, защитные проверки. */
class AdminGosklyuchCrlApiTest extends ApiTestBase {

    // ─────────────────────────── Госключ ───────────────────────────

    @Test
    @DisplayName("Госключ: статус интеграции и пустой список заявок")
    void gosklyuchStatusAndOrders() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(asGet(admin, get("/api/v1/admin/gosklyuch/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.maxDocuments").isNumber());

        mockMvc.perform(asGet(admin, get("/api/v1/admin/gosklyuch/orders")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Госключ: действия при выключенной интеграции → 404")
    void gosklyuchActionsDisabled() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(asGet(admin, get("/api/v1/admin/gosklyuch/orders/999")))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(admin, post("/api/v1/admin/gosklyuch/orders/999/refresh")))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(admin, post("/api/v1/admin/gosklyuch/orders/999/apply")))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(admin, post("/api/v1/admin/gosklyuch/refresh-all")))
                .andExpect(status().isNotFound());
        mockMvc.perform(asGet(admin, get("/api/v1/admin/gosklyuch/orders/999/signature")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Госключ: заявки недоступны клиенту")
    void gosklyuchForbiddenForClient() throws Exception {
        User client = createClient();
        mockMvc.perform(asGet(client, get("/api/v1/admin/gosklyuch/status")))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get("/api/v1/admin/gosklyuch/orders")))
                .andExpect(status().isForbidden());
    }

    // ─────────────────────────── CRL ───────────────────────────

    @Test
    @DisplayName("CRL: список, сводка и отчёт о доверии")
    void crlListAndSummary() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(asGet(admin, get("/api/v1/admin/crl")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(asGet(admin, get("/api/v1/admin/crl/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").isNumber());
        mockMvc.perform(asGet(admin, get("/api/v1/admin/crl/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").exists());
    }

    @Test
    @DisplayName("CRL: загрузка мусора отклоняется")
    void crlUploadGarbage() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, multipart("/api/v1/admin/crl")
                        .file(new MockMultipartFile("file", "bad.crl",
                                "application/pkix-crl", "не CRL".getBytes(StandardCharsets.UTF_8)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CRL: удаление несуществующего файла → 404")
    void crlDeleteMissing() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, delete("/api/v1/admin/crl/nonexistent.crl")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CRL: удаление всех автоматических файлов")
    void crlDeleteAuto() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, delete("/api/v1/admin/crl/auto")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").isNumber());
    }

    @Test
    @DisplayName("CRL: доступ закрыт клиенту")
    void crlForbiddenForClient() throws Exception {
        User client = createClient();
        mockMvc.perform(asGet(client, get("/api/v1/admin/crl")))
                .andExpect(status().isForbidden());
    }
}
