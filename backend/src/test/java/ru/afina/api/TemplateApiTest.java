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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Шаблоны документов: список (только активные, без CONSENT), карточка,
 * генерация PDF и отказы при невалидном запросе.
 */
@DisplayName("TemplateApi: шаблоны и генерация документов")
class TemplateApiTest extends ApiTestBase {

    private static final String FIELDS_JSON = """
            [{"key":"fio","label":"ФИО","type":"text","required":true},
             {"key":"city","label":"Город","type":"text","required":false}]
            """;

    @Autowired
    private DocumentTemplateRepository templateRepository;

    // ──────────────────────────── список / карточка ────────────────────────────

    @Test
    @DisplayName("Список: только активные шаблоны и без CONSENT")
    void listOnlyActiveAndNoConsent() throws Exception {
        User client = createClient();
        DocumentTemplate active = createTemplate(TemplateKind.DOCUMENT, true);
        DocumentTemplate inactive = createTemplate(TemplateKind.DOCUMENT, false);
        DocumentTemplate consent = createTemplate(TemplateKind.CONSENT, true);

        JsonNode list = json(mockMvc.perform(asGet(client, get("/api/v1/templates")))
                .andExpect(status().isOk())
                .andReturn());

        List<Long> ids = ids(list);
        assertThat(ids).contains(active.getId());
        assertThat(ids).doesNotContain(inactive.getId(), consent.getId());

        for (JsonNode node : list) {
            assertThat(node.get("active").asBoolean())
                    .as("в списке только активные шаблоны").isTrue();
            assertThat(node.get("kind").asText())
                    .as("CONSENT-шаблоны клиенту не показываются").isNotEqualTo("CONSENT");
        }
    }

    @Test
    @DisplayName("Карточка шаблона: поля и количество полей")
    void get() throws Exception {
        User client = createClient();
        DocumentTemplate template = createTemplate(TemplateKind.DOCUMENT, true);

        mockMvc.perform(asGet(client, get("/api/v1/templates/" + template.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(template.getId()))
                .andExpect(jsonPath("$.name").value(template.getName()))
                .andExpect(jsonPath("$.kind").value("DOCUMENT"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.fieldsCount").value(2))
                .andExpect(jsonPath("$.htmlContent").value(template.getHtmlContent()));
    }

    @Test
    @DisplayName("CONSENT-шаблон по id → 404")
    void getConsentTemplateNotFound() throws Exception {
        User client = createClient();
        DocumentTemplate consent = createTemplate(TemplateKind.CONSENT, true);

        mockMvc.perform(asGet(client, get("/api/v1/templates/" + consent.getId())))
                .andExpect(status().isNotFound());
    }

    // ──────────────────────────── генерация ────────────────────────────

    @Test
    @DisplayName("Генерация: успешное создание PDF из шаблона")
    void generateSuccess() throws Exception {
        User client = createClient();
        DocumentTemplate template = createTemplate(TemplateKind.DOCUMENT, true);

        MvcResult result = mockMvc.perform(as(client,
                        post("/api/v1/templates/" + template.getId() + "/generate"))
                        .contentType("application/json")
                        .content(body(Map.of("values", Map.of(
                                "fio", "Иванов Иван Иванович",
                                "city", "Москва")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.extension").value("pdf"))
                .andExpect(jsonPath("$.signed").value(false))
                .andReturn();

        JsonNode dto = json(result);
        assertThat(dto.get("name").asText())
                .startsWith(template.getName())
                .endsWith(".pdf");

        long documentId = dto.get("id").asLong();
        assertThat(documentRepository.findById(documentId))
                .as("сгенерированный документ сохранён").isPresent();
    }

    @Test
    @DisplayName("Генерация: незаполненное обязательное поле → 400")
    void generateMissingRequiredField() throws Exception {
        User client = createClient();
        DocumentTemplate template = createTemplate(TemplateKind.DOCUMENT, true);

        mockMvc.perform(as(client, post("/api/v1/templates/" + template.getId() + "/generate"))
                        .contentType("application/json")
                        .content(body(Map.of("values", Map.of("city", "Москва")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message", containsString("обязательное поле")));
    }

    @Test
    @DisplayName("Генерация: пустые значения → 400")
    void generateEmptyValues() throws Exception {
        User client = createClient();
        DocumentTemplate template = createTemplate(TemplateKind.DOCUMENT, true);

        mockMvc.perform(as(client, post("/api/v1/templates/" + template.getId() + "/generate"))
                        .contentType("application/json")
                        .content("{\"values\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Генерация из CONSENT-шаблона → 400")
    void generateConsentTemplate() throws Exception {
        User client = createClient();
        DocumentTemplate consent = createTemplate(TemplateKind.CONSENT, true);

        mockMvc.perform(as(client, post("/api/v1/templates/" + consent.getId() + "/generate"))
                        .contentType("application/json")
                        .content(body(Map.of("values", Map.of("fio", "Иванов Иван")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("недоступен")));
    }

    @Test
    @DisplayName("Генерация из неактивного шаблона → 400")
    void generateInactiveTemplate() throws Exception {
        User client = createClient();
        DocumentTemplate inactive = createTemplate(TemplateKind.DOCUMENT, false);

        mockMvc.perform(as(client, post("/api/v1/templates/" + inactive.getId() + "/generate"))
                        .contentType("application/json")
                        .content(body(Map.of("values", Map.of("fio", "Иванов Иван")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("недоступен")));
    }

    @Test
    @DisplayName("Генерация: неизвестный шаблон → 404")
    void generateUnknownTemplate() throws Exception {
        User client = createClient();

        mockMvc.perform(as(client, post("/api/v1/templates/99999999/generate"))
                        .contentType("application/json")
                        .content(body(Map.of("values", Map.of("fio", "Иванов Иван")))))
                .andExpect(status().isNotFound());
    }

    // ──────────────────────────── helpers ────────────────────────────

    private DocumentTemplate createTemplate(TemplateKind kind, boolean active) {
        String name = (kind == TemplateKind.CONSENT ? "Согласие " : "Шаблон ") + UUID.randomUUID();
        DocumentTemplate template = new DocumentTemplate();
        template.setKind(kind);
        template.setName(name);
        template.setDescription("Тестовый шаблон " + name);
        template.setHtmlContent("<h1>" + name + "</h1>"
                + "<p>ФИО: {{fio}}</p><p>Город: {{city}}</p>");
        template.setFieldsJson(FIELDS_JSON);
        template.setCreatedBy("test");
        template.setActive(active);
        template.setSortOrder(0);
        return templateRepository.save(template);
    }

    private static List<Long> ids(JsonNode list) {
        List<Long> result = new ArrayList<>();
        for (JsonNode node : list) {
            result.add(node.get("id").asLong());
        }
        return result;
    }
}
