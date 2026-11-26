package ru.afina.gosklyuch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Госключ: диагностика конфигурации без сетевых запросов")
class GosklyuchDiagnosticsTest {

    private GosklyuchProperties properties;
    private GosklyuchService service;
    private GosklyuchSigner signer;
    private GosklyuchTokenProvider tokenProvider;
    private GosklyuchDiagnostics diagnostics;

    @BeforeEach
    void setUp() {
        properties = new GosklyuchProperties();
        service = mock(GosklyuchService.class);
        signer = (name, content) -> new byte[]{1, 2, 3};
        tokenProvider = () -> "secret-token-value";
        diagnostics = new GosklyuchDiagnostics(properties, service, signer, tokenProvider);
    }

    @Test
    @DisplayName("По умолчанию: выключено, провайдер disabled, подписант не настроен")
    void reportsDisabledDefaults() {
        when(service.isEnabled()).thenReturn(false);
        when(service.isReadyToSend()).thenReturn(false);
        var report = diagnostics.check();

        assertThat(report.enabled()).isFalse();
        assertThat(report.provider()).isEqualTo("disabled");
        assertThat(report.readyToSend()).isFalse();
        assertThat(check(report, "Интеграция включена").status())
                .isEqualTo(GosklyuchDiagnostics.Status.WARN);
        assertThat(check(report, "Провайдер API").status())
                .isEqualTo(GosklyuchDiagnostics.Status.WARN);
    }

    @Test
    @DisplayName("epgu с заполненными реквизитами: проверки OK, маркер не раскрывается")
    void reportsEpguReady() {
        properties.setEnabled(true);
        properties.setProvider("epgu");
        properties.setBaseUrl("https://svcdev-beta.test.gosuslugi.ru");
        properties.setRegion("45000000000");
        properties.setServiceCode("10000000374");
        properties.setTargetCode("-10000000374");
        properties.setMnemonic("MNSV03");
        properties.setOrgName("ООО «Тест»");
        properties.setToken("super-secret");
        when(service.isEnabled()).thenReturn(true);
        when(service.isReadyToSend()).thenReturn(true);

        var report = diagnostics.check();

        assertThat(report.checks())
                .allMatch(c -> c.status() != GosklyuchDiagnostics.Status.ERROR,
                        "нет ошибок в конфигурации");
        assertThat(check(report, "Адрес API ЕПГУ").status())
                .isEqualTo(GosklyuchDiagnostics.Status.OK);
        assertThat(check(report, "Маркер доступа").message()).contains("задан");
        assertThat(report.settings().get("token")).isEqualTo("задан");
        assertThat(report.settings().values()).noneMatch(v -> v.contains("super-secret"));
    }

    @Test
    @DisplayName("epgu без адреса API и маркера: ошибки")
    void reportsMissingEpguSettings() {
        properties.setEnabled(true);
        properties.setProvider("epgu");
        tokenProvider = () -> {
            throw new GosklyuchUnavailableException("Маркер доступа API ЕПГУ не настроен");
        };
        diagnostics = new GosklyuchDiagnostics(properties, service, signer, tokenProvider);

        var report = diagnostics.check();

        assertThat(check(report, "Адрес API ЕПГУ").status())
                .isEqualTo(GosklyuchDiagnostics.Status.ERROR);
        assertThat(check(report, "Маркер доступа").status())
                .isEqualTo(GosklyuchDiagnostics.Status.ERROR);
    }

    @Test
    @DisplayName("Проверка подписанта: заглушка и демо-подписант — предупреждение")
    void checkSignerVariants() {
        var unavailable = new GosklyuchDiagnostics(properties, service,
                new GosklyuchSignerUnavailable(), tokenProvider);
        assertThat(unavailable.checkSigner().status())
                .isEqualTo(GosklyuchDiagnostics.Status.WARN);

        var demo = new GosklyuchDiagnostics(properties, service,
                new FakeGosklyuchSigner(), tokenProvider);
        assertThat(demo.checkSigner().status())
                .isEqualTo(GosklyuchDiagnostics.Status.WARN);
        assertThat(demo.checkSigner().message()).contains("Демо");

        assertThat(diagnostics.checkSigner().status())
                .isEqualTo(GosklyuchDiagnostics.Status.OK);
    }

    @Test
    @DisplayName("Демо-провайдер помечается как безопасная заглушка")
    void reportsFakeProvider() {
        properties.setEnabled(true);
        properties.setProvider("fake");
        when(service.isEnabled()).thenReturn(true);
        when(service.isReadyToSend()).thenReturn(true);

        var report = diagnostics.check();
        assertThat(check(report, "Провайдер API").status())
                .isEqualTo(GosklyuchDiagnostics.Status.WARN);
        assertThat(check(report, "Провайдер API").message()).contains("демо");
    }

    @Test
    @DisplayName("Неизвестный провайдер — ошибка")
    void reportsUnknownProvider() {
        properties.setProvider("something");
        assertThat(check(diagnostics.check(), "Провайдер API").status())
                .isEqualTo(GosklyuchDiagnostics.Status.ERROR);
    }

    @Test
    @DisplayName("Таймауты и лимиты попадают в настройки")
    void exposesSettings() {
        properties.setMaxDocuments(15);
        properties.setMaxPackageBytes(100L * 1024 * 1024);
        properties.setSignExpiration(Duration.ofHours(24));
        var settings = diagnostics.check().settings();
        assertThat(settings).containsEntry("maxDocuments", "15")
                .containsEntry("maxPackageMb", "100")
                .containsEntry("signExpiration", "PT24H");
    }

    private static GosklyuchDiagnostics.Check check(GosklyuchDiagnostics.Report report, String name) {
        return report.checks().stream()
                .filter(c -> name.equals(c.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Нет проверки: " + name));
    }
}
