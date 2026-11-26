package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import ru.afina.entity.User;
import ru.afina.repository.PowerOfAttorneyRepository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Админский API машиночитаемых доверенностей (МЧД): список, загрузка XML,
 * включение/отключение, скачивание исходника, удаление и права доступа.
 */
class AdminPoaApiTest extends ApiTestBase {

    private static final String BASE = "/api/v1/admin/poa";

    @Autowired
    private PowerOfAttorneyRepository poaRepository;

    private User admin;
    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void initAdmin() {
        admin = createAdmin();
    }

    @AfterEach
    void cleanup() {
        for (Long id : createdIds) {
            poaRepository.deleteById(id);
        }
        createdIds.clear();
    }

    // ──────────────────────────── вспомогательное ────────────────────────────

    /** Валидный XML МЧД (формат ФНС) с уникальным GUID — чтобы не было дубликата. */
    private static String validXml(String guid) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Доверенность xmlns="urn://x-artefacts/EMCHD_1">
                  <СвДов ВнНомДовер="1042" НомДовер="%s"
                         ДатаВыдДовер="2025-01-15" СрокДейст="2030-12-31"/>
                  <СвУпПред>
                    <ФИО Фамилия="Иванов" Имя="Иван" Отчество="Иванович"/>
                    <СведФизЛ СНИЛС="12345678901" ИННФЛ="500100732259"/>
                  </СвУпПред>
                  <СвРосОрг НаимОрг="ООО Афина" ИННЮЛ="7701234567"/>
                  <ФИОНотДейств Фамилия="Петрова" Имя="Анна" Отчество="Сергеевна"/>
                </Доверенность>
                """.formatted(guid);
    }

    private MockMultipartFile xmlFile(String fileName, String content) {
        return new MockMultipartFile("file", fileName, "application/xml",
                content.getBytes(StandardCharsets.UTF_8));
    }

    /** Загружает валидную доверенность и запоминает её id для уборки. */
    private JsonNode uploadPoa() throws Exception {
        MvcResult result = mockMvc.perform(as(admin, multipart(BASE)
                        .file(xmlFile("poa.xml", validXml(UUID.randomUUID().toString())))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode poa = json(result);
        createdIds.add(poa.get("id").asLong());
        return poa;
    }

    private static JsonNode findById(JsonNode array, long id) {
        for (JsonNode item : array) {
            if (item.get("id").asLong() == id) return item;
        }
        return null;
    }

    // ════════════════════════════ список и загрузка ════════════════════════════

    @Test
    @DisplayName("Загрузка валидного XML: 201 и разобранные поля")
    void uploadValidXml() throws Exception {
        JsonNode poa = uploadPoa();

        assertThat(poa.get("id").asLong()).isPositive();
        assertThat(poa.get("fio").asText()).isEqualTo("Иванов Иван Иванович");
        assertThat(poa.get("snils").asText()).isEqualTo("***-8901");
        assertThat(poa.get("orgName").asText()).isEqualTo("ООО Афина");
        assertThat(poa.get("docNumber").asText()).isEqualTo("1042");
        assertThat(poa.get("validUntil").asText()).isEqualTo("2030-12-31");
        assertThat(poa.get("active").asBoolean()).isTrue();
        assertThat(poa.get("effective").asBoolean()).isTrue();
        assertThat(poa.get("createdBy").asText()).isEqualTo(admin.getEmail());
    }

    @Test
    @DisplayName("Список доверенностей содержит загруженную")
    void listContainsUploadedPoa() throws Exception {
        long id = uploadPoa().get("id").asLong();

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        assertThat(findById(json(result), id)).isNotNull();
    }

    @Test
    @DisplayName("Битый XML → 400")
    void uploadBrokenXml() throws Exception {
        mockMvc.perform(as(admin, multipart(BASE)
                        .file(xmlFile("broken.xml", "<Доверенность><СвДов"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    @DisplayName("Файл не .xml, пустой файл и запрос без файла → 400")
    void uploadRejectsWrongFiles() throws Exception {
        mockMvc.perform(as(admin, multipart(BASE)
                        .file(xmlFile("poa.txt", validXml(UUID.randomUUID().toString())))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(as(admin, multipart(BASE)
                        .file(new MockMultipartFile("file", "empty.xml", "application/xml", new byte[0]))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(as(admin, multipart(BASE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Повторная загрузка того же XML → 400 (дубликат)")
    void uploadDuplicate() throws Exception {
        String xml = validXml(UUID.randomUUID().toString());

        MvcResult first = mockMvc.perform(as(admin, multipart(BASE).file(xmlFile("poa.xml", xml))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        createdIds.add(json(first).get("id").asLong());

        mockMvc.perform(as(admin, multipart(BASE).file(xmlFile("poa.xml", xml))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("уже загружена")));
    }

    // ════════════════════════════ активность и скачивание ════════════════════════════

    @Test
    @DisplayName("Включение и отключение доверенности")
    void setActive() throws Exception {
        long id = uploadPoa().get("id").asLong();

        mockMvc.perform(as(admin, patch(BASE + "/" + id + "/active"))
                        .contentType("application/json")
                        .content(body(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.effective").value(false));

        mockMvc.perform(as(admin, patch(BASE + "/" + id + "/active"))
                        .contentType("application/json")
                        .content(body(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        // без признака active — 400
        mockMvc.perform(as(admin, patch(BASE + "/" + id + "/active"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Скачивание исходного XML доверенности")
    void downloadXml() throws Exception {
        JsonNode poa = uploadPoa();
        long id = poa.get("id").asLong();

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE + "/" + id + "/xml")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".xml")))
                .andReturn();

        assertThat(result.getResponse().getContentType()).contains("xml");
        String xml = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(xml).contains("Доверенность").contains("Иванов");
    }

    @Test
    @DisplayName("Удаление доверенности: 204, исходник больше недоступен")
    void deletePoa() throws Exception {
        long id = uploadPoa().get("id").asLong();

        mockMvc.perform(as(admin, delete(BASE + "/" + id)))
                .andExpect(status().isNoContent());

        assertThat(poaRepository.findById(id)).isEmpty();
        mockMvc.perform(asGet(admin, get(BASE + "/" + id + "/xml")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Удаление несуществующей доверенности → 404 (ожидаемое поведение)")
    void deleteMissingPoaReturns404() throws Exception {
        mockMvc.perform(as(admin, delete(BASE + "/99999999")))
                .andExpect(status().isNotFound());
    }

    // ════════════════════════════ права доступа ════════════════════════════

    @Test
    @DisplayName("Не-администратор получает 403")
    void nonAdminForbidden() throws Exception {
        User client = createClient();
        long id = uploadPoa().get("id").asLong();

        mockMvc.perform(asGet(client, get(BASE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, multipart(BASE)
                        .file(xmlFile("poa.xml", validXml(UUID.randomUUID().toString())))))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, patch(BASE + "/" + id + "/active"))
                        .contentType("application/json")
                        .content(body(Map.of("active", false))))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, delete(BASE + "/" + id)))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/" + id + "/xml")))
                .andExpect(status().isForbidden());
    }
}
