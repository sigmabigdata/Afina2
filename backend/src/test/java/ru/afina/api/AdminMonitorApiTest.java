package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import ru.afina.entity.MonitorEvent;
import ru.afina.entity.User;
import ru.afina.repository.MonitorEventRepository;
import ru.afina.service.SettingsService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Админский API мониторинга: живой прогон проверок, история событий с
 * граничными {@code limit}, журнал приложения с граничными {@code lines},
 * тестовое оповещение без настроенного адреса и права доступа.
 *
 * <p>События мониторинга создаются напрямую через репозиторий и удаляются
 * в {@code @AfterEach} — иначе они влияли бы на историю в других тестах.</p>
 */
class AdminMonitorApiTest extends ApiTestBase {

    private static final String BASE = "/api/v1/admin";

    @Autowired
    private MonitorEventRepository eventRepository;
    @Autowired
    private SettingsService settingsService;

    private User admin;
    private final List<Long> createdEvents = new ArrayList<>();

    @BeforeEach
    void initAdmin() {
        admin = createAdmin();
    }

    @AfterEach
    void cleanup() {
        for (Long id : createdEvents) {
            eventRepository.deleteById(id);
        }
        createdEvents.clear();
    }

    // ──────────────────────────── вспомогательное ────────────────────────────

    private MonitorEvent event(String component, String status, String reason, boolean alertSent) {
        MonitorEvent e = new MonitorEvent();
        e.setCheckedAt(Instant.now());
        e.setComponent(component);
        e.setStatus(status);
        e.setReason(reason);
        e.setAlertSent(alertSent);
        MonitorEvent saved = eventRepository.save(e);
        createdEvents.add(saved.getId());
        return saved;
    }

    // ════════════════════════════ живой прогон ════════════════════════════

    @Test
    @DisplayName("Проверки компонентов: список с названиями и признаком здоровья")
    void checks() throws Exception {
        MvcResult result = mockMvc.perform(asGet(admin, get(BASE + "/monitor/checks")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].component").isNotEmpty())
                .andExpect(jsonPath("$[0].title").isNotEmpty())
                .andExpect(jsonPath("$[0].healthy").isBoolean())
                .andExpect(jsonPath("$[0].latencyMs").isNumber())
                .andReturn();

        JsonNode items = json(result);
        assertThat(items.size()).isGreaterThanOrEqualTo(5);
        for (JsonNode item : items) {
            assertThat(item.hasNonNull("component")).isTrue();
            assertThat(item.has("message")).isTrue();
        }
    }

    // ════════════════════════════ история ════════════════════════════

    @Test
    @DisplayName("История: сохранённые события отдаются с полями и новые сверху")
    void historyReturnsEvents() throws Exception {
        event("db", "OK", "подключение установлено", false);
        event("smtp", "FAIL", "connection refused", true);

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE + "/monitor/history"))
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        JsonNode items = json(result);
        assertThat(items.size()).isGreaterThanOrEqualTo(2);

        JsonNode first = items.get(0);
        assertThat(first.get("id").asLong()).isPositive();
        assertThat(first.get("status").asText()).isNotBlank();
        assertThat(first.get("component").asText()).isNotBlank();
        assertThat(first.hasNonNull("checkedAt")).isTrue();
        assertThat(first.has("reason")).isTrue();
        assertThat(first.has("alertSent")).isTrue();

        // записи идут в обратном хронологическом порядке
        Instant previous = null;
        for (JsonNode item : items) {
            Instant checkedAt = Instant.parse(item.get("checkedAt").asText());
            if (previous != null) {
                assertThat(checkedAt).isBeforeOrEqualTo(previous);
            }
            previous = checkedAt;
        }
    }

    @Test
    @DisplayName("История: граничные limit (0 и 1000) не ломают выборку")
    void historyClampsLimit() throws Exception {
        event("db", "OK", "граница", false);

        // limit=0 поднимается до 1
        MvcResult zero = mockMvc.perform(asGet(admin, get(BASE + "/monitor/history"))
                        .param("limit", "0"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(zero).size()).isLessThanOrEqualTo(1);

        // limit=1000 опускается до 200
        MvcResult huge = mockMvc.perform(asGet(admin, get(BASE + "/monitor/history"))
                        .param("limit", "1000"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(huge).size()).isLessThanOrEqualTo(200);
    }

    // ════════════════════════════ журнал ════════════════════════════

    @Test
    @DisplayName("Журнал: граничные lines (1, 0, 100000) приводятся к допустимым")
    void logsClampLines() throws Exception {
        MvcResult tiny = mockMvc.perform(asGet(admin, get(BASE + "/monitor/logs"))
                        .param("lines", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isArray())
                .andExpect(jsonPath("$.requested").value(10))
                .andExpect(jsonPath("$.count").isNumber())
                .andReturn();
        assertThat(json(tiny).get("count").asInt())
                .isEqualTo(json(tiny).get("lines").size());

        mockMvc.perform(asGet(admin, get(BASE + "/monitor/logs")).param("lines", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(10));

        mockMvc.perform(asGet(admin, get(BASE + "/monitor/logs")).param("lines", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2000));

        mockMvc.perform(asGet(admin, get(BASE + "/monitor/logs")).param("lines", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(50));
    }

    // ════════════════════════════ тестовый алерт ════════════════════════════

    @Test
    @DisplayName("Тестовый алерт без адреса в настройках → 409")
    void testAlertWithoutEmail() throws Exception {
        Optional<String> savedTest = settingsService.get("monitor.test_email");
        Optional<String> savedMailTo = settingsService.get("monitor.mail_to");
        try {
            settingsService.set("monitor.test_email", "", "test");
            settingsService.set("monitor.mail_to", "", "test");

            mockMvc.perform(as(admin, post(BASE + "/monitor/test-alert")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message", containsString("Email для алертов")));
        } finally {
            restore("monitor.test_email", savedTest);
            restore("monitor.mail_to", savedMailTo);
        }
    }

    private void restore(String key, Optional<String> saved) {
        settingsService.set(key, saved.orElse(""), "test");
    }

    // ════════════════════════════ сведения о сервере ════════════════════════════

    @Test
    @DisplayName("Сведения о системе: uptime, память, диск и диагностика")
    void systemInfo() throws Exception {
        mockMvc.perform(asGet(admin, get(BASE + "/system")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uptimeSeconds").isNumber())
                .andExpect(jsonPath("$.uptimePretty", containsString("с")))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.memory").exists())
                .andExpect(jsonPath("$.cpu").exists())
                .andExpect(jsonPath("$.disk").exists())
                .andExpect(jsonPath("$.storage").exists())
                .andExpect(jsonPath("$.database").exists())
                .andExpect(jsonPath("$.runtime").exists())
                .andExpect(jsonPath("$.diagnostics").exists());
    }

    // ════════════════════════════ права доступа ════════════════════════════

    @Test
    @DisplayName("Не-администратор получает 403")
    void nonAdminForbidden() throws Exception {
        User client = createClient();

        mockMvc.perform(asGet(client, get(BASE + "/monitor/history")))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/monitor/logs")))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/monitor/checks")))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, post(BASE + "/monitor/test-alert")))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/system")))
                .andExpect(status().isForbidden());
    }
}
