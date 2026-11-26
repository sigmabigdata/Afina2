package ru.afina.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.afina.entity.Document;
import ru.afina.entity.User;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Публичная проверка документа по QR-ссылке (без авторизации).
 */
@DisplayName("PublicApi: публичная информация о документе")
class PublicApiTest extends ApiTestBase {

    @Test
    @DisplayName("Существующий документ → 200 с именем и размером")
    void infoExisting() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Публичный договор.pdf");

        mockMvc.perform(get("/api/v1/public/info/" + doc.getPublicUuid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentName").value("Публичный договор.pdf"))
                .andExpect(jsonPath("$.sizeBytes").isNumber())
                .andExpect(jsonPath("$.signed").value(false))
                .andExpect(jsonPath("$.signatureCount").value(0))
                .andExpect(jsonPath("$.signatures").isArray())
                .andExpect(jsonPath("$.sha256").value(doc.getFileSha256()))
                .andExpect(jsonPath("$.uploadedAt").isNotEmpty());
    }

    @Test
    @DisplayName("Неизвестный UUID → 404")
    void infoUnknown() throws Exception {
        mockMvc.perform(get("/api/v1/public/info/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").value("Документ не найден"));
    }

    @Test
    @DisplayName("Невалидный UUID → 400")
    void infoInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/v1/public/info/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("Неверный формат идентификатора"));
    }
}
