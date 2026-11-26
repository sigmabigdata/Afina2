package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import javax.sql.DataSource;
import org.springframework.test.util.ReflectionTestUtils;
import ru.afina.repository.MonitorEventRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MonitoringService: шаблоны алертов")
class MonitoringAlertTemplateTest {

    @Mock private MonitorEventRepository eventRepo;
    @Mock private SettingsService settings;
    @Mock private EmailService emailService;
    @Mock private DataSource dataSource;

    private MonitoringService service;

    @BeforeEach
    void setUp() {
        service = new MonitoringService(eventRepo, settings, emailService, dataSource);
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://afina.test");
    }

    @Test
    @DisplayName("по умолчанию тема без «ПАО», с подстановкой {status}/{components}")
    void defaultSubject() {
        when(settings.getOrDefault(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        String subject = ReflectionTestUtils.invokeMethod(service, "alertSubject",
                "Сервис недоступен", "SMTP, База данных");

        assertThat(subject)
                .isEqualTo("Афина · Сервис недоступен: SMTP, База данных")
                .doesNotContain("ПАО");
    }

    @Test
    @DisplayName("тело по умолчанию: {details}/{fails}/{url} подставлены, без «ПАО»")
    void defaultBody() {
        when(settings.getOrDefault(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        String body = ReflectionTestUtils.invokeMethod(service, "buildAlertBody",
                List.of(), 3);

        assertThat(body)
                .doesNotContain("ПАО")
                .contains("Сбоев подряд: 3")
                .contains("https://afina.test/admin/monitor");
    }

    @Test
    @DisplayName("тема из настроек переопределяет шаблон")
    void customSubjectTemplate() {
        when(settings.getOrDefault(org.mockito.ArgumentMatchers.eq("alert.subject"),
                anyString())).thenReturn("АЛЕРТ [{status}] {components}");

        String subject = ReflectionTestUtils.invokeMethod(service, "alertSubject",
                "ВОССТАНОВЛЕНО", "все компоненты");

        assertThat(subject).isEqualTo("АЛЕРТ [ВОССТАНОВЛЕНО] все компоненты");
    }
}
