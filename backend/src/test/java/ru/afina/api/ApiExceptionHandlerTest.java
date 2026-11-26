package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import ru.afina.entity.Document;
import ru.afina.entity.User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Единый формат ошибок REST API: 400/401/403/404/409/413.
 */
@DisplayName("ApiExceptionHandler: единый формат ошибок")
class ApiExceptionHandlerTest extends ApiTestBase {

    @Autowired
    private ApiExceptionHandler exceptionHandler;

    @Test
    @DisplayName("NotFoundException → 404 not_found")
    void notFound() throws Exception {
        User client = createClient();
        String path = "/api/v1/documents/99999999";

        MvcResult result = mockMvc.perform(asGet(client, get(path)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.time").isNotEmpty())
                .andReturn();

        JsonNode payload = json(result);
        assertThat(payload.get("message").asText()).contains("найден");
    }

    @Test
    @DisplayName("IllegalArgumentException → 400 bad_request")
    void badRequest() throws Exception {
        User client = createClient();

        mockMvc.perform(as(client, post("/api/v1/folders"))
                        .contentType("application/json")
                        .content(body(Map.of("name", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("Укажите название раздела"))
                .andExpect(jsonPath("$.path").value("/api/v1/folders"))
                .andExpect(jsonPath("$.time").isNotEmpty());
    }

    @Test
    @DisplayName("IllegalStateException → 409 conflict")
    void conflict() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Без подписи.txt");

        mockMvc.perform(asGet(client,
                        get("/api/v1/documents/" + doc.getId() + "/visualization")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("не подписан")));
    }

    @Test
    @DisplayName("Запрос без аутентификации → 401 unauthorized")
    void unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    @DisplayName("Клиент на админском маршруте → 403 forbidden")
    void forbidden() throws Exception {
        User client = createClient();

        mockMvc.perform(asGet(client, get("/api/v1/admin/clients")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    /**
     * 413 нельзя стабильно получить через MockMvc без реального multipart-лимита,
     * поэтому маппинг проверяется прямым вызовом обработчика с mock-запросом.
     */
    @Test
    @DisplayName("MaxUploadSizeExceededException → 413 file_too_large")
    void payloadTooLarge() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/documents");

        ResponseEntity<Map<String, Object>> response = exceptionHandler
                .handleTooLarge(new MaxUploadSizeExceededException(1024), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody())
                .containsEntry("error", "file_too_large")
                .containsEntry("path", "/api/v1/documents")
                .containsKey("message")
                .containsKey("time");
    }
}
