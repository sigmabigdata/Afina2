package ru.afina.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import ru.afina.entity.User;
import ru.afina.service.EmailService;
import ru.afina.service.SettingsService;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты {@link AdminSettingsApi} без Spring-контекста.
 *
 * <p>Покрывают защитные ветки, недостижимые через REST: {@code null}-ключ
 * и {@code null}-карта в массовом обновлении, отправка письма и её ошибки,
 * выбор «настроенного» SMTP между БД и окружением, а также полная матрица
 * проверки IP/CIDR-whitelist (статические пакетные методы).</p>
 */
@DisplayName("AdminSettingsApi: ветки без Spring-контекста")
class AdminSettingsApiUnitTest {

    private SettingsService settings;
    private EmailService emailService;
    private CurrentUser currentUser;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        emailService = mock(EmailService.class);
        currentUser = mock(CurrentUser.class);
        auth = mock(Authentication.class);

        User admin = new User();
        admin.setEmail("admin@test.local");
        when(currentUser.requireAdmin(auth)).thenReturn(admin);
    }

    private AdminSettingsApi api() {
        return api("");
    }

    private AdminSettingsApi api(String mailHost) {
        return new AdminSettingsApi(settings, emailService, currentUser, mailHost);
    }

    // ════════════════════════════ массовое обновление ════════════════════════════

    @Test
    @DisplayName("Обновление: null-карта и null-ключ отклоняются")
    void updateRejectsNullMapAndNullKey() {
        assertThatThrownBy(() -> api().update(null, auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Список настроек пуст");

        // JSON не умеет null-ключи, но Jackson-мэппинг в Map<String,String>
        // теоретически может дать null — защита обязана сработать.
        Map<String, String> withNullKey = new HashMap<>();
        withNullKey.put(null, "значение");

        assertThatThrownBy(() -> api().update(withNullKey, auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Пустой ключ");
    }

    @Test
    @DisplayName("Обновление: null-значение у секрета пропускается, у обычной настройки — пустая строка")
    void updateHandlesNullValues() {
        Map<String, String> values = new HashMap<>();
        values.put("smtp.password", null);
        values.put("smtp.host", null);

        Map<String, Object> result = api().update(values, auth);

        assertThat(result).containsEntry("updated", 1).containsEntry("skipped", 1);
        verify(settings).set(eq("smtp.host"), eq(""), eq("admin@test.local"));
        verify(settings, never()).set(eq("smtp.password"), any(), anyString());
    }

    @Test
    @DisplayName("isSecretKey: null, пустая строка и регистр")
    void isSecretKeyMatrix() {
        assertThat(AdminSettingsApi.isSecretKey(null)).isFalse();
        assertThat(AdminSettingsApi.isSecretKey("")).isFalse();
        assertThat(AdminSettingsApi.isSecretKey("   ")).isFalse();
        assertThat(AdminSettingsApi.isSecretKey("smtp.host")).isFalse();
        assertThat(AdminSettingsApi.isSecretKey("admin.PASSWORD")).isTrue();
        assertThat(AdminSettingsApi.isSecretKey("some.Secret.Value")).isTrue();
        assertThat(AdminSettingsApi.isSecretKey("api.token")).isTrue();
        assertThat(AdminSettingsApi.isSecretKey("file.key")).isTrue();
    }

    // ════════════════════════════ SMTP ════════════════════════════

    @Test
    @DisplayName("SMTP: письмо уходит, если host задан в БД")
    void smtpTestSendsWhenDatabaseConfigured() {
        when(settings.isSmtpConfigured()).thenReturn(true);

        Map<String, Object> result = api().smtpTest(
                new AdminSettingsApi.SmtpTestRequest(" ops@test.local "), auth);

        assertThat(result).containsEntry("sent", true);
        assertThat(result).containsEntry("to", "ops@test.local");
        verify(emailService).sendTest("ops@test.local");
    }

    @Test
    @DisplayName("SMTP: ошибка отправки превращается в 409-совместимое исключение")
    void smtpTestWrapsSendErrors() {
        when(settings.isSmtpConfigured()).thenReturn(true);
        doThrow(new IllegalStateException("connection refused"))
                .when(emailService).sendTest(anyString());

        assertThatThrownBy(() -> api().smtpTest(
                new AdminSettingsApi.SmtpTestRequest("ops@test.local"), auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ошибка отправки письма")
                .hasMessageContaining("connection refused");
    }

    @Test
    @DisplayName("SMTP: host из окружения учитывается, localhost/пустой — нет")
    void smtpAvailabilityUsesEnvironment() {
        when(settings.isSmtpConfigured()).thenReturn(false);
        AdminSettingsApi.SmtpTestRequest request =
                new AdminSettingsApi.SmtpTestRequest("ops@test.local");

        // осмысленный внешний host — SMTP считается настроенным
        assertThat(api("smtp.example.ru").smtpTest(request, auth))
                .containsEntry("sent", true);

        // localhost, loopback, пустое значение и null не считаются настроенным SMTP
        for (String host : new String[]{"localhost", "127.0.0.1", "", "   ", null}) {
            assertThatThrownBy(() -> api(host).smtpTest(request, auth))
                    .as("host=%s", host)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SMTP не настроен");
        }
    }

    @Test
    @DisplayName("SMTP: некорректный адрес и отсутствие тела → 400-совместимое исключение")
    void smtpTestValidatesAddress() {
        assertThatThrownBy(() -> api().smtpTest(null, auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");

        for (String bad : new String[]{null, "", "   ", "без-собаки"}) {
            assertThatThrownBy(() -> api().smtpTest(
                    new AdminSettingsApi.SmtpTestRequest(bad), auth))
                    .as("to=%s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("корректный email");
        }
    }

    // ════════════════════════════ IP-whitelist ════════════════════════════

    @Test
    @DisplayName("IP-whitelist: матрица корректных значений")
    void ipWhitelistAcceptsValidValues() {
        for (String good : new String[]{
                "", "   ", "*", ",,", "127.0.0.1", "203.0.113.255",
                "10.0.0.0/8", "203.0.113.0/24", "203.0.113.5/32", "0.0.0.0/0",
                "::1", "2001:db8::1", "2001:db8::/32",
                "127.0.0.1, 203.0.113.0/24, *"}) {
            assertThat(AdminSettingsApi.isValidIpWhitelist(good))
                    .as("значение «%s» должно быть допустимым", good)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("IP-whitelist: матрица некорректных значений")
    void ipWhitelistRejectsInvalidValues() {
        for (String bad : new String[]{
                "999.999.999.999", "1.2.3", "1.2.3.4.5", "abc", "not-an-ip",
                "10.0.0.0/33", "10.0.0.0/-1", "10.0.0.0/abc", "::zz",
                "127.0.0.1, abc"}) {
            assertThat(AdminSettingsApi.isValidIpWhitelist(bad))
                    .as("значение «%s» должно быть отклонено", bad)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("IP-whitelist: чтение, нормализация и сохранение значения")
    void ipWhitelistReadAndNormalize() {
        when(settings.getOrDefault("admin.ip_whitelist", "")).thenReturn("10.0.0.0/8");
        assertThat(api().ipWhitelist(auth)).containsEntry("value", "10.0.0.0/8");

        // отсутствие тела и null-значение трактуются как «разрешены все»
        assertThat(api().saveIpWhitelist(null, auth)).containsEntry("value", "");
        assertThat(api().saveIpWhitelist(new AdminSettingsApi.IpWhitelistRequest(null), auth))
                .containsEntry("value", "");

        // пробелы обрезаются
        assertThat(api().saveIpWhitelist(
                new AdminSettingsApi.IpWhitelistRequest("  10.0.0.1  "), auth))
                .containsEntry("value", "10.0.0.1");

        // «*» проходит отдельной веткой с предупреждением в лог
        assertThat(api().saveIpWhitelist(new AdminSettingsApi.IpWhitelistRequest("*"), auth))
                .containsEntry("value", "*");

        assertThatThrownBy(() -> api().saveIpWhitelist(
                new AdminSettingsApi.IpWhitelistRequest("999.999.999.999"), auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Некорректный IP/CIDR");
    }
}
