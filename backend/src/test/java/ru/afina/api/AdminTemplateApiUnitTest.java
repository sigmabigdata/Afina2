package ru.afina.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.entity.DocumentTemplate;
import ru.afina.entity.User;
import ru.afina.service.AuditService;
import ru.afina.service.DocumentTemplateService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты {@link AdminTemplateApi} без Spring-контекста.
 *
 * <p>Проверяют ветку ошибки рендера PDF: в реальном окружении openhtmltopdf
 * пишет кириллицу стабильно, поэтому {@link IOException} из сервиса можно
 * воспроизвести только подменой зависимости.</p>
 */
@DisplayName("AdminTemplateApi: ветки обработки ошибок")
class AdminTemplateApiUnitTest {

    private DocumentTemplateService templateService;
    private CurrentUser currentUser;
    private Authentication auth;
    private AdminTemplateApi api;

    @BeforeEach
    void setUp() {
        templateService = mock(DocumentTemplateService.class);
        currentUser = mock(CurrentUser.class);
        AuditService audit = mock(AuditService.class);
        ApiMapper mapper = mock(ApiMapper.class);
        auth = mock(Authentication.class);

        User admin = new User();
        admin.setEmail("admin@test.local");
        when(currentUser.requireAdmin(auth)).thenReturn(admin);

        api = new AdminTemplateApi(templateService, currentUser, mapper, audit);
    }

    @Test
    @DisplayName("Превью: ошибка рендера PDF → 500 с понятным сообщением")
    void previewWrapsRenderError() throws Exception {
        DocumentTemplate template = new DocumentTemplate();
        template.setId(7L);
        template.setHtmlContent("<p>{{fio}}</p>");
        when(templateService.getById(7L)).thenReturn(template);
        when(templateService.parseFields(template)).thenReturn(List.of());
        when(templateService.extractPlaceholders(any())).thenReturn(List.of());
        when(templateService.renderPdf(eq(template), anyMap()))
                .thenThrow(new IOException("шрифт не найден"));

        ResponseStatusException error = catchThrowableOfType(
                () -> api.preview(7L, null, auth),
                ResponseStatusException.class);

        assertThat(error).isNotNull();
        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(error.getReason()).contains("Не удалось сформировать PDF");
    }

    @Test
    @DisplayName("Превью: переданные значения полей попадают в рендер")
    void previewPassesRequestValues() throws Exception {
        DocumentTemplate template = new DocumentTemplate();
        template.setId(8L);
        template.setHtmlContent("<p>{{fio}}</p>");
        when(templateService.getById(8L)).thenReturn(template);
        when(templateService.parseFields(template)).thenReturn(List.of());
        when(templateService.extractPlaceholders(any())).thenReturn(List.of("fio"));

        java.util.concurrent.atomic.AtomicReference<Map<String, String>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(templateService.renderPdf(eq(template), anyMap())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return new byte[]{'%', 'P', 'D', 'F'};
        });

        ResponseEntity<byte[]> response = api.preview(8L,
                new AdminTemplateApi.PreviewRequest(Map.of("fio", "Петров Пётр")), auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentDisposition().isInline()).isTrue();
        assertThat(captured.get()).containsEntry("fio", "Петров Пётр");
    }

    @Test
    @DisplayName("Превью: несуществующий шаблон → 404")
    void previewMissingTemplate() {
        when(templateService.getById(99L))
                .thenThrow(new ru.afina.error.NotFoundException("Шаблон не найден"));

        assertThatThrownBy(() -> api.preview(99L, null, auth))
                .isInstanceOf(ru.afina.error.NotFoundException.class);
    }
}
