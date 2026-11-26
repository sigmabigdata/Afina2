package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import ru.afina.entity.AuditEvent;
import ru.afina.entity.User;
import ru.afina.repository.AuditEventRepository;
import ru.afina.service.AuditService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Админский API журнала аудита: постраничный список, фильтры, CSV-выгрузка,
 * сводка, очистка и проверка прав доступа.
 *
 * <p>Тесты не зависят от порядка: каждое событие получает уникальный тип
 * ({@code TST_XXXXXXXX}), поэтому выборки не пересекаются с другими тестами,
 * работающими на том же контексте.</p>
 */
class AdminAuditApiTest extends ApiTestBase {

    private static final String BASE = "/api/v1/admin/audit";
    private static final String CSV_HEADER = "Время;Тип;Результат;Субъект;Роль;IP;Объект;ID;Инфо;Детали";

    @Autowired
    private AuditService auditService;
    @Autowired
    private AuditEventRepository auditRepository;

    private User admin;
    private String type;

    @BeforeEach
    void setUp() {
        admin = createAdmin();
        type = "TST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** Пишет событие аудита с уникальным типом текущего теста. */
    private void event(String result) {
        auditService.event(type, result, admin.getEmail(), "USER", "1", "тестовое событие", null);
    }

    // ════════════════════════════ список и фильтры ════════════════════════════

    @Test
    @DisplayName("Список событий: пагинация в формате PageDto")
    void listPagination() throws Exception {
        for (int i = 0; i < 5; i++) {
            auditService.event(type, "SUCCESS", admin.getEmail(), "USER",
                    String.valueOf(i), "событие " + i, null);
        }

        mockMvc.perform(asGet(admin, get(BASE)
                        .param("type", type)
                        .param("page", "0")
                        .param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.pages").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].eventType").value(type));

        // последняя страница: 5 событий по 2 на страницу → на третьей одно
        mockMvc.perform(asGet(admin, get(BASE)
                        .param("type", type)
                        .param("page", "2")
                        .param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        // алиас /events отдаёт ту же выдачу
        mockMvc.perform(asGet(admin, get(BASE + "/events").param("type", type)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5));
    }

    @Test
    @DisplayName("Фильтр по результату SUCCESS/FAIL")
    void filterByResult() throws Exception {
        event("SUCCESS");
        event("SUCCESS");
        event("FAIL");

        mockMvc.perform(asGet(admin, get(BASE)
                        .param("type", type)
                        .param("result", "FAIL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].result").value("FAIL"));

        mockMvc.perform(asGet(admin, get(BASE)
                        .param("type", type)
                        .param("result", "SUCCESS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(asGet(admin, get(BASE)
                        .param("type", type)
                        .param("result", "WARN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("Фильтр по субъекту (подстрока email, без учёта регистра)")
    void filterByActor() throws Exception {
        String uniqueActor = "audit-" + UUID.randomUUID() + "@test.local";
        auditService.event(type, "SUCCESS", uniqueActor, "USER", "1", "наше событие", null);
        auditService.event(type, "SUCCESS", "other-" + UUID.randomUUID() + "@test.local",
                "USER", "2", "чужое событие", null);

        String needle = uniqueActor.substring(0, uniqueActor.indexOf('@'));
        mockMvc.perform(asGet(admin, get(BASE).param("actor", needle)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].actorEmail").value(uniqueActor));
    }

    // ════════════════════════════ CSV и сводка ════════════════════════════

    @Test
    @DisplayName("Экспорт CSV: BOM, заголовок и данные события")
    void exportCsv() throws Exception {
        event("SUCCESS");

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE + "/export.csv")
                        .param("type", type)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".csv")))
                .andReturn();

        assertThat(result.getResponse().getContentType()).contains("text/csv");

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);
        assertThat(bytes[1] & 0xFF).isEqualTo(0xBB);
        assertThat(bytes[2] & 0xFF).isEqualTo(0xBF);

        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF" + CSV_HEADER);
        assertThat(csv).contains(type);
        assertThat(csv).contains(admin.getEmail());

        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).startsWith("\"");
    }

    @Test
    @DisplayName("Сводка: total/today/failures и счётчик по типу")
    void stats() throws Exception {
        event("SUCCESS");

        mockMvc.perform(asGet(admin, get(BASE + "/stats")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.today").isNumber())
                .andExpect(jsonPath("$.failures").isNumber())
                .andExpect(jsonPath("$.byType." + type).value(1));
    }

    // ════════════════════════════ очистка ════════════════════════════

    @Test
    @DisplayName("Очистка удаляет события старше указанного срока")
    void cleanupDeletesOldEvents() throws Exception {
        AuditEvent old = new AuditEvent();
        old.setEventTime(Instant.now().minus(400, ChronoUnit.DAYS));
        old.setEventType(type);
        old.setResult("SUCCESS");
        long oldId = auditRepository.save(old).getId();

        event("SUCCESS");

        mockMvc.perform(as(admin, delete(BASE + "/cleanup").param("days", "365")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").isNumber());

        assertThat(auditRepository.findById(oldId)).isEmpty();
    }

    @Test
    @DisplayName("Валидация срока очистки 1..3650: 0 и 3651 → 400, 3650 → 200")
    void cleanupValidation() throws Exception {
        mockMvc.perform(as(admin, delete(BASE + "/cleanup").param("days", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(as(admin, delete(BASE + "/cleanup").param("days", "3651")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(as(admin, delete(BASE + "/cleanup").param("days", "-1")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(as(admin, delete(BASE + "/cleanup").param("days", "3650")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").isNumber());
    }

    @Test
    @DisplayName("Битая дата в фильтре from/to → 400")
    void brokenDateReturns400() throws Exception {
        mockMvc.perform(asGet(admin, get(BASE).param("from", "вчера")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message", containsString("from")));

        mockMvc.perform(asGet(admin, get(BASE).param("to", "2024-13-45T00:00:00Z")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(asGet(admin, get(BASE + "/export.csv").param("from", "31.01.2024")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Корректные границы from/to принимаются")
    void validDateRange() throws Exception {
        event("SUCCESS");

        String from = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        String to = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE)
                        .param("type", type)
                        .param("from", from)
                        .param("to", to)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andReturn();

        JsonNode page = json(result);
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("eventType").asText()).isEqualTo(type);
    }

    // ════════════════════════════ права доступа ════════════════════════════

    @Test
    @DisplayName("Не-администратор получает 403")
    void nonAdminForbidden() throws Exception {
        User client = createClient();

        mockMvc.perform(asGet(client, get(BASE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/events")))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/export.csv")))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/stats")))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, delete(BASE + "/cleanup").param("days", "365")))
                .andExpect(status().isForbidden());
    }
}
