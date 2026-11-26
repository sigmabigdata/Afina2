package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import ru.afina.entity.DocumentTemplate;
import ru.afina.entity.TemplateKind;
import ru.afina.entity.User;
import ru.afina.repository.DocumentTemplateRepository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Админский API шаблонов документов: CRUD, переключение видимости,
 * PDF-превью, проверка JSON полей и права доступа.
 */
class AdminTemplateApiTest extends ApiTestBase {

    private static final String BASE = "/api/v1/admin/templates";

    /** Корректное описание полей: два поля. */
    private static final String FIELDS_JSON = """
            [{"key":"fio","label":"ФИО","type":"text","required":true},
             {"key":"date","label":"Дата","type":"date"}]
            """;

    @Autowired
    private DocumentTemplateRepository templateRepository;

    private User admin;
    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void initAdmin() {
        admin = createAdmin();
    }

    @AfterEach
    void cleanup() {
        for (Long id : createdIds) {
            templateRepository.deleteById(id);
        }
        createdIds.clear();
    }

    // ──────────────────────────── вспомогательное ────────────────────────────

    private JsonNode createTemplate(String name, String fieldsJson) throws Exception {
        MvcResult result = mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of(
                                "name", name,
                                "description", "Тестовый шаблон",
                                "htmlContent", "<h1>" + name + "</h1><p>{{fio}}</p><p>{{date}}</p>",
                                "fieldsJson", fieldsJson,
                                "sortOrder", 5,
                                "kind", "DOCUMENT"))))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode template = json(result);
        createdIds.add(template.get("id").asLong());
        return template;
    }

    private static JsonNode findById(JsonNode array, long id) {
        for (JsonNode item : array) {
            if (item.get("id").asLong() == id) return item;
        }
        return null;
    }

    // ════════════════════════════ CRUD ════════════════════════════

    @Test
    @DisplayName("Создание шаблона: 201, список и карточка")
    void createAndGet() throws Exception {
        String name = "Шаблон " + UUID.randomUUID();
        JsonNode created = createTemplate(name, FIELDS_JSON);

        long id = created.get("id").asLong();
        assertThat(created.get("name").asText()).isEqualTo(name);
        assertThat(created.get("kind").asText()).isEqualTo("DOCUMENT");
        assertThat(created.get("active").asBoolean()).isTrue();
        assertThat(created.get("fieldsCount").asInt()).isEqualTo(2);
        assertThat(created.get("createdBy").asText()).isEqualTo(admin.getEmail());

        mockMvc.perform(asGet(admin, get(BASE + "/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.htmlContent", containsString("{{fio}}")));

        MvcResult listResult = mockMvc.perform(asGet(admin, get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();
        assertThat(findById(json(listResult), id)).isNotNull();
    }

    @Test
    @DisplayName("Создание шаблона с некорректными данными → 400")
    void createValidatesInput() throws Exception {
        // нет названия
        mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of("htmlContent", "<p>x</p>"))))
                .andExpect(status().isBadRequest());

        // нет HTML
        mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of("name", "Без HTML"))))
                .andExpect(status().isBadRequest());

        // битый JSON полей
        mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of("name", "Битые поля",
                                "htmlContent", "<p>{{a}}</p>",
                                "fieldsJson", "{не json"))))
                .andExpect(status().isBadRequest());

        // неизвестное назначение
        mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of("name", "Кривой kind",
                                "htmlContent", "<p>x</p>",
                                "kind", "UNKNOWN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Обновление шаблона и переключение видимости")
    void updateAndToggle() throws Exception {
        String name = "Шаблон " + UUID.randomUUID();
        long id = createTemplate(name, FIELDS_JSON).get("id").asLong();

        mockMvc.perform(as(admin, put(BASE + "/" + id))
                        .contentType("application/json")
                        .content(body(Map.of(
                                "name", name + " (обновлён)",
                                "description", "Новое описание",
                                "htmlContent", "<h1>Новая версия</h1><p>{{fio}}</p>",
                                "fieldsJson", FIELDS_JSON,
                                "active", false,
                                "kind", "CONSENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name + " (обновлён)"))
                .andExpect(jsonPath("$.description").value("Новое описание"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.kind").value("CONSENT"));

        mockMvc.perform(as(admin, post(BASE + "/" + id + "/toggle")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(as(admin, post(BASE + "/" + id + "/toggle")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("Удаление шаблона: 204, затем 404")
    void deleteTemplate() throws Exception {
        long id = createTemplate("Шаблон " + UUID.randomUUID(), FIELDS_JSON).get("id").asLong();

        mockMvc.perform(as(admin, delete(BASE + "/" + id)))
                .andExpect(status().isNoContent());

        mockMvc.perform(asGet(admin, get(BASE + "/" + id)))
                .andExpect(status().isNotFound());

        mockMvc.perform(as(admin, delete(BASE + "/" + id)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Карточка несуществующего шаблона → 404")
    void missingTemplate() throws Exception {
        mockMvc.perform(asGet(admin, get(BASE + "/99999999")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    @DisplayName("Создание сразу отключённого шаблона: active=false сохраняется")
    void createInactiveTemplate() throws Exception {
        String name = "Отключённый шаблон " + UUID.randomUUID();
        MvcResult result = mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of(
                                "name", name,
                                "htmlContent", "<p>{{fio}}</p>",
                                "fieldsJson", FIELDS_JSON,
                                "active", false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.sortOrder").value(0))
                .andExpect(jsonPath("$.kind").value("DOCUMENT"))
                .andReturn();

        createdIds.add(json(result).get("id").asLong());
    }

    @Test
    @DisplayName("Пустое назначение шаблона трактуется как DOCUMENT")
    void blankKindDefaultsToDocument() throws Exception {
        MvcResult result = mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of(
                                "name", "Шаблон " + UUID.randomUUID(),
                                "htmlContent", "<p>{{fio}}</p>",
                                "kind", "   "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("DOCUMENT"))
                .andReturn();

        createdIds.add(json(result).get("id").asLong());
    }

    @Test
    @DisplayName("Частичное обновление: не переданные поля сохраняют прежние значения")
    void partialUpdateKeepsExistingValues() throws Exception {
        long id = createTemplate("Шаблон " + UUID.randomUUID(), FIELDS_JSON).get("id").asLong();

        // меняем только название: sortOrder/active/kind/HTML остаются прежними
        mockMvc.perform(as(admin, put(BASE + "/" + id))
                        .contentType("application/json")
                        .content(body(Map.of("name", "Только имя"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Только имя"))
                .andExpect(jsonPath("$.sortOrder").value(5))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.kind").value("DOCUMENT"))
                .andExpect(jsonPath("$.htmlContent", containsString("{{fio}}")));

        // пустое назначение не должно менять текущее
        mockMvc.perform(as(admin, put(BASE + "/" + id))
                        .contentType("application/json")
                        .content(body(Map.of("kind", " "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DOCUMENT"));
    }

    // ════════════════════════════ превью и валидация полей ════════════════════════════

    @Test
    @DisplayName("Превью шаблона отдаётся как PDF (inline)")
    void previewReturnsPdf() throws Exception {
        long id = createTemplate("Шаблон " + UUID.randomUUID(), FIELDS_JSON).get("id").asLong();

        MvcResult result = mockMvc.perform(as(admin, post(BASE + "/" + id + "/preview"))
                        .contentType("application/json")
                        .content(body(Map.of("values", Map.of("fio", "Иванов Иван Иванович")))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andReturn();

        assertThat(result.getResponse().getContentType()).contains("application/pdf");
        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertThat(pdf.length).isGreaterThan(500);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Превью несуществующего шаблона → 404")
    void previewMissingTemplate() throws Exception {
        mockMvc.perform(as(admin, post(BASE + "/99999999/preview"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("validate-fields: корректный JSON возвращает поля")
    void validateFieldsValid() throws Exception {
        mockMvc.perform(as(admin, post(BASE + "/validate-fields"))
                        .contentType("application/json")
                        .content(body(Map.of("fieldsJson", FIELDS_JSON))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.fields.length()").value(2))
                .andExpect(jsonPath("$.fields[0].key").value("fio"));

        // пустое описание полей допустимо
        mockMvc.perform(as(admin, post(BASE + "/validate-fields"))
                        .contentType("application/json")
                        .content(body(Map.of("fieldsJson", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.fields.length()").value(0));
    }

    @Test
    @DisplayName("validate-fields: битый JSON и неверные ключи → 400")
    void validateFieldsInvalid() throws Exception {
        mockMvc.perform(as(admin, post(BASE + "/validate-fields"))
                        .contentType("application/json")
                        .content(body(Map.of("fieldsJson", "это не json"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        // поле без key
        mockMvc.perform(as(admin, post(BASE + "/validate-fields"))
                        .contentType("application/json")
                        .content(body(Map.of("fieldsJson", "[{\"label\":\"Без ключа\"}]"))))
                .andExpect(status().isBadRequest());

        // недопустимые символы в key
        mockMvc.perform(as(admin, post(BASE + "/validate-fields"))
                        .contentType("application/json")
                        .content(body(Map.of("fieldsJson", "[{\"key\":\"bad-key\"}]"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("validate-fields: запрос без тела считается шаблоном без полей")
    void validateFieldsWithoutBody() throws Exception {
        mockMvc.perform(as(admin, post(BASE + "/validate-fields")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.fields.length()").value(0));

        mockMvc.perform(as(admin, post(BASE + "/validate-fields"))
                        .contentType("application/json")
                        .content("{\"fieldsJson\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.fields.length()").value(0));

        mockMvc.perform(as(admin, post(BASE + "/validate-fields"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.length()").value(0));
    }

    @Test
    @DisplayName("Превью без тела запроса: подставляются демонстрационные значения")
    void previewWithoutBody() throws Exception {
        long id = createTemplate("Шаблон " + UUID.randomUUID(), FIELDS_JSON).get("id").asLong();

        MvcResult result = mockMvc.perform(as(admin, post(BASE + "/" + id + "/preview")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType()).contains("application/pdf");
        assertThat(new String(result.getResponse().getContentAsByteArray(), 0, 4,
                StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Превью: все типы полей получают демо-значения (дата, email, телефон, ФИО, default)")
    void previewFillsDemoValuesByFieldType() throws Exception {
        String fieldsJson = """
                [{"key":"fio"},
                 {"key":"full_name"},
                 {"key":"email"},
                 {"key":"phone"},
                 {"key":"birth","type":"date","label":"Дата рождения"},
                 {"key":"contract_no","defaultValue":"№ 42/2026"},
                 {"key":"custom","label":"Своё поле"}]
                """;
        String html = "<h1>Превью типов полей</h1>"
                + "<p>{{fio}}</p><p>{{full_name}}</p><p>{{email}}</p><p>{{phone}}</p>"
                + "<p>{{birth}}</p><p>{{contract_no}}</p><p>{{custom}}</p>"
                + "<p>{{extra_placeholder}}</p>";

        MvcResult created = mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of("name", "Типы полей " + UUID.randomUUID(),
                                "htmlContent", html,
                                "fieldsJson", fieldsJson))))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json(created).get("id").asLong();
        createdIds.add(id);

        MvcResult result = mockMvc.perform(as(admin, post(BASE + "/" + id + "/preview"))
                        .contentType("application/json")
                        .content(body(Map.of("values", Map.of("fio", "Петров Пётр")))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(new String(result.getResponse().getContentAsByteArray(), 0, 4,
                StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Превью переживает повреждённое описание полей (поле без ключа)")
    void previewToleratesDamagedFieldsJson() throws Exception {
        // Такое значение не прошло бы валидацию API, но могло остаться в БД
        // из старой версии — превью не должно падать.
        DocumentTemplate damaged = new DocumentTemplate();
        damaged.setName("Повреждённый " + UUID.randomUUID());
        damaged.setHtmlContent("<p>{{email}}</p><p>{{unknown}}</p>");
        damaged.setFieldsJson("[{\"label\":\"Без ключа\"},{\"key\":\"email\"}]");
        damaged.setCreatedBy(admin.getEmail());
        damaged.setKind(TemplateKind.DOCUMENT);
        damaged.setActive(true);
        DocumentTemplate saved = templateRepository.save(damaged);
        createdIds.add(saved.getId());

        MvcResult result = mockMvc.perform(as(admin, post(BASE + "/" + saved.getId() + "/preview"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(new String(result.getResponse().getContentAsByteArray(), 0, 4,
                StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    // ════════════════════════════ права доступа ════════════════════════════

    @Test
    @DisplayName("Не-администратор получает 403")
    void nonAdminForbidden() throws Exception {
        User client = createClient();
        long id = createTemplate("Шаблон " + UUID.randomUUID(), FIELDS_JSON).get("id").asLong();

        mockMvc.perform(asGet(client, get(BASE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/" + id)))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of("name", "Нельзя", "htmlContent", "<p>x</p>"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, put(BASE + "/" + id))
                        .contentType("application/json")
                        .content(body(Map.of("name", "Нельзя"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, delete(BASE + "/" + id)))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, post(BASE + "/" + id + "/toggle")))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, post(BASE + "/" + id + "/preview"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, post(BASE + "/validate-fields"))
                        .contentType("application/json")
                        .content(body(Map.of("fieldsJson", FIELDS_JSON))))
                .andExpect(status().isForbidden());
    }
}
