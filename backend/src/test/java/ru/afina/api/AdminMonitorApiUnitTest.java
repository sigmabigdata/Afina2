package ru.afina.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.entity.MonitorEvent;
import ru.afina.entity.User;
import ru.afina.repository.MonitorEventRepository;
import ru.afina.service.LogService;
import ru.afina.service.MonitoringService;
import ru.afina.service.SystemInfoService;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты {@link AdminMonitorApi} без Spring-контекста.
 *
 * <p>Покрывают успешную отправку тестового алерта (в тестовом профиле SMTP
 * не настроен, поэтому через REST ветка недостижима), прерывание отправки,
 * ошибку SMTP-шлюза и форматирование длительности uptime, а также маппинг
 * событий истории.</p>
 */
@DisplayName("AdminMonitorApi: ветки без Spring-контекста")
class AdminMonitorApiUnitTest {

    private MonitoringService monitoring;
    private MonitorEventRepository events;
    private LogService logService;
    private SystemInfoService systemInfo;
    private AdminMonitorApi api;

    @BeforeEach
    void setUp() {
        monitoring = mock(MonitoringService.class);
        events = mock(MonitorEventRepository.class);
        logService = mock(LogService.class);
        systemInfo = mock(SystemInfoService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        Authentication auth = mock(Authentication.class);

        User admin = new User();
        admin.setEmail("admin@test.local");
        when(currentUser.requireAdmin(auth)).thenReturn(admin);

        api = new AdminMonitorApi(monitoring, events, logService, systemInfo, currentUser);
    }

    // ════════════════════════════ история ════════════════════════════

    @Test
    @DisplayName("История: событие раскладывается по полям ответа")
    void historyMapsEventFields() {
        MonitorEvent event = new MonitorEvent();
        event.setId(42L);
        event.setCheckedAt(Instant.parse("2026-01-02T03:04:05Z"));
        event.setComponent("smtp");
        event.setStatus("FAIL");
        event.setReason("connection refused");
        event.setAlertSent(true);

        when(events.findAllByOrderByCheckedAtDesc(any())).thenReturn(List.of(event));

        List<Map<String, Object>> result = api.history(100, null);

        assertThat(result).hasSize(1);
        Map<String, Object> item = result.get(0);
        assertThat(item).containsEntry("id", 42L)
                .containsEntry("component", "smtp")
                .containsEntry("status", "FAIL")
                .containsEntry("reason", "connection refused")
                .containsEntry("alertSent", true)
                .containsEntry("checkedAt", Instant.parse("2026-01-02T03:04:05Z"));
    }

    @Test
    @DisplayName("История: пустая выборка → пустой список")
    void historyEmpty() {
        when(events.findAllByOrderByCheckedAtDesc(any())).thenReturn(List.of());

        assertThat(api.history(10, null)).isEmpty();
    }

    // ════════════════════════════ журнал ════════════════════════════

    @Test
    @DisplayName("Журнал: количество строк и запрошенный лимит возвращаются в ответе")
    void logsReturnTail() {
        when(logService.tail(anyInt())).thenReturn(List.of("строка 1", "строка 2"));

        Map<String, Object> result = api.logs(10, null);

        assertThat(result).containsEntry("count", 2).containsEntry("requested", 10);
        assertThat((List<?>) result.get("lines")).hasSize(2);
    }

    // ════════════════════════════ тестовый алерт ════════════════════════════

    @Test
    @DisplayName("Тестовый алерт: успешная отправка → sent=true")
    void testAlertSends() throws Exception {
        when(monitoring.alertEmail()).thenReturn("ops@test.local");
        doNothing().when(monitoring).sendTestAlert();

        Map<String, Object> result = api.testAlert(null);

        assertThat(result).containsEntry("sent", true).containsEntry("to", "ops@test.local");
        assertThat((String) result.get("message")).contains("ops@test.local");
    }

    @Test
    @DisplayName("Тестовый алерт: адрес не задан → 409-совместимое исключение")
    void testAlertWithoutAddress() {
        for (String empty : new String[]{null, "", "   "}) {
            when(monitoring.alertEmail()).thenReturn(empty);
            assertThatThrownBy(() -> api.testAlert(null))
                    .as("адрес «%s»", empty)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Email для алертов не задан");
        }
    }

    @Test
    @DisplayName("Тестовый алерт: прерывание отправки → 409 и восстановленный флаг прерывания")
    void testAlertInterrupted() throws Exception {
        when(monitoring.alertEmail()).thenReturn("ops@test.local");
        doThrow(new InterruptedException("stop")).when(monitoring).sendTestAlert();

        try {
            assertThatThrownBy(() -> api.testAlert(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("прервана");
            // контроллер обязан восстановить флаг прерывания потока
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Тестовый алерт: ошибка SMTP → 502 Bad Gateway")
    void testAlertWrapsSmtpError() throws Exception {
        when(monitoring.alertEmail()).thenReturn("ops@test.local");
        doThrow(new IllegalStateException("smtp down")).when(monitoring).sendTestAlert();

        // IllegalStateException пробрасывается как конфликт состояния (409)
        assertThatThrownBy(() -> api.testAlert(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("smtp down");

        doThrow(new RuntimeException("timeout")).when(monitoring).sendTestAlert();
        assertThatThrownBy(() -> api.testAlert(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Не удалось отправить тестовое оповещение");
        try {
            api.testAlert(null);
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(502);
            assertThat(e.getReason()).contains("timeout");
        }
    }

    // ════════════════════════════ uptime ════════════════════════════

    @Test
    @DisplayName("Форматирование uptime: дни, часы, минуты и секунды")
    void formatsUptime() throws Exception {
        assertThat(formatDuration(0)).isEqualTo("0с");
        assertThat(formatDuration(45)).isEqualTo("45с");
        assertThat(formatDuration(3661)).isEqualTo("1ч 1м 1с");
        assertThat(formatDuration(90_061)).isEqualTo("1д 1ч 1м 1с");
        assertThat(formatDuration(172_800)).isEqualTo("2д 0с");
    }

    /** Приватный форматтер длительности: проверяем напрямую, без ожидания реального uptime. */
    private static String formatDuration(long seconds) throws Exception {
        Method method = AdminMonitorApi.class.getDeclaredMethod("formatDuration", long.class);
        method.setAccessible(true);
        return (String) method.invoke(null, seconds);
    }
}
