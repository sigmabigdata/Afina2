package ru.afina.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import ru.afina.api.dto.CrlDto;
import ru.afina.entity.User;
import ru.afina.service.CrlOnDemandService;
import ru.afina.service.CrlService;
import ru.afina.service.CrlStatusService;

import java.io.IOException;
import java.security.cert.CRLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты {@link AdminCrlApi} без Spring-контекста.
 *
 * <p>Покрывают защитные ветки, недостижимые через REST: ошибки ввода-вывода
 * сервиса CRL, {@link SecurityException} при недопустимом имени, пустой отчёт
 * {@link CrlStatusService} и границы расчёта статуса срока действия.</p>
 */
@DisplayName("AdminCrlApi: ветки без Spring-контекста")
@SuppressWarnings("java:S5778") // несколько assertThatThrownBy подряд читаются лучше
class AdminCrlApiUnitTest {

    private CrlService crlService;
    private CrlOnDemandService crlOnDemandService;
    private CrlStatusService crlStatusService;
    private ApiMapper mapper;
    private Authentication auth;
    private AdminCrlApi api;

    @BeforeEach
    void setUp() {
        crlService = mock(CrlService.class);
        crlOnDemandService = mock(CrlOnDemandService.class);
        crlStatusService = mock(CrlStatusService.class);
        mapper = mock(ApiMapper.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        auth = mock(Authentication.class);

        User admin = new User();
        admin.setEmail("admin@test.local");
        when(currentUser.requireAdmin(auth)).thenReturn(admin);

        api = new AdminCrlApi(crlService, crlOnDemandService, crlStatusService,
                currentUser, mapper);
    }

    private static CrlService.CrlInfo crlInfo(String name, long daysToExpiry) {
        Instant now = Instant.now();
        return new CrlService.CrlInfo(name, 1024, "1,0 КБ", "CN=Test CA",
                now.minusSeconds(3600), now.plusSeconds(daysToExpiry * 86_400),
                now, daysToExpiry, true);
    }

    // ════════════════════════════ список и сводка ════════════════════════════

    @Test
    @DisplayName("Список: статус считается по дням до nextUpdate (границы -1, 0, 29, 30)")
    void listComputesStatusBoundaries() {
        when(crlService.listAll()).thenReturn(List.of(
                crlInfo("expired.crl", -1),
                crlInfo("today.crl", 0),
                crlInfo("soon.crl", 29),
                crlInfo("ok.crl", 30)));
        when(mapper.crl(any(), anyString())).thenAnswer(invocation -> {
            CrlService.CrlInfo info = invocation.getArgument(0);
            String status = invocation.getArgument(1);
            return new CrlDto(info.fileName(), info.sizeBytes(), info.sizePretty(),
                    info.issuer(), info.lastUpdate(), info.nextUpdate(),
                    info.downloadedAt(), info.daysToExpiry(), info.manual(), status);
        });

        List<CrlDto> list = api.list(auth);

        assertThat(list).extracting(CrlDto::status)
                .containsExactly("expired", "expiring", "expiring", "ok");
    }

    @Test
    @DisplayName("Сводка: количество истёкших/истекающих считается тем же статусом")
    void summaryCountsStatuses() {
        when(crlService.listAll()).thenReturn(List.of(
                crlInfo("expired.crl", -5),
                crlInfo("soon.crl", 3),
                crlInfo("ok.crl", 100)));
        when(crlService.getTotalSize()).thenReturn(3072L);
        when(crlService.getCount()).thenReturn(3L);

        Map<String, Object> summary = api.summary(auth);

        assertThat(summary).containsEntry("count", 3L)
                .containsEntry("totalSize", 3072L)
                .containsEntry("expiring", 1L)
                .containsEntry("expired", 1L);
        assertThat((String) summary.get("totalSizePretty")).isNotBlank();
    }

    // ════════════════════════════ обновление ════════════════════════════

    @Test
    @DisplayName("Обновление: сервис вернул null → 409-совместимое исключение")
    void refreshWithoutResult() {
        when(crlOnDemandService.refreshAll(anyString())).thenReturn(null);

        assertThatThrownBy(() -> api.refresh(auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не удалось выполнить обновление CRL");
    }

    @Test
    @DisplayName("Обновление: все счётчики отчёта попадают в ответ")
    void refreshMapsReport() {
        CrlOnDemandService.RefreshResult result = new CrlOnDemandService.RefreshResult(
                5, 4, 1, 3, 2, 1, 1, List.of("CN=Bad CA: timeout"));
        when(crlOnDemandService.refreshAll("admin@test.local")).thenReturn(result);

        Map<String, Object> body = api.refresh(auth);

        assertThat(body).containsEntry("downloaded", 2)
                .containsEntry("failed", 1)
                .containsEntry("deleted", 1)
                .containsEntry("totalSignatures", 5)
                .containsEntry("parsedSignatures", 4)
                .containsEntry("parseErrors", 1)
                .containsEntry("uniqueCerts", 3)
                .containsEntry("crlDownloaded", 2);
        assertThat((List<?>) body.get("failures")).hasSize(1);
    }

    @Test
    @DisplayName("Обновление: null-список ошибок превращается в пустой")
    void refreshWithNullFailures() {
        when(crlOnDemandService.refreshAll(anyString()))
                .thenReturn(new CrlOnDemandService.RefreshResult(0, 0, 0, 0, 0, 0, 0, null));

        Map<String, Object> body = api.refresh(auth);

        assertThat((List<?>) body.get("failures")).isEmpty();
    }

    // ════════════════════════════ загрузка ════════════════════════════

    @Test
    @DisplayName("Загрузка: нет файла и пустой файл → 400-совместимое исключение")
    void uploadRejectsMissingAndEmpty() {
        assertThatThrownBy(() -> api.upload(null, auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Не передан файл CRL");

        assertThatThrownBy(() -> api.upload(
                new MockMultipartFile("file", "empty.crl", "application/pkix-crl", new byte[0]),
                auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Файл пустой");
    }

    @Test
    @DisplayName("Загрузка: успешный сценарий возвращает имя и размер")
    void uploadSavesFile() throws Exception {
        byte[] data = {0x30, (byte) 0x82, 0x01, 0x02};

        Map<String, Object> body = api.upload(
                new MockMultipartFile("file", "manual.crl", "application/pkix-crl", data), auth);

        assertThat(body).containsEntry("uploaded", true)
                .containsEntry("fileName", "manual.crl")
                .containsEntry("sizeBytes", 4L);
        assertThat((String) body.get("sizePretty")).isNotBlank();
    }

    @Test
    @DisplayName("Загрузка: ошибка чтения и ошибка записи → 500-совместимое исключение")
    void uploadWrapsIoErrors() throws Exception {
        MultipartFile unreadable = mock(MultipartFile.class);
        when(unreadable.isEmpty()).thenReturn(false);
        when(unreadable.getOriginalFilename()).thenReturn("bad.crl");
        when(unreadable.getBytes()).thenThrow(new IOException("диск недоступен"));

        assertThatThrownBy(() -> api.upload(unreadable, auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не удалось прочитать файл");

        MockMultipartFile file = new MockMultipartFile(
                "file", "save.crl", "application/pkix-crl", new byte[]{0x01});
        doThrow(new IOException("нет места")).when(crlService)
                .saveManual(eq("save.crl"), any());

        assertThatThrownBy(() -> api.upload(file, auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не удалось сохранить CRL");
    }

    @Test
    @DisplayName("Загрузка: битый CRL (CRLException) → 400 с понятным сообщением")
    void uploadRejectsBrokenCrl() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "broken.crl", "application/pkix-crl", new byte[]{0x01, 0x02});
        doThrow(new CRLException("not a CRL")).when(crlService)
                .saveManual(eq("broken.crl"), any());

        assertThatThrownBy(() -> api.upload(file, auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не является корректным CRL");
    }

    // ════════════════════════════ удаление ════════════════════════════

    @Test
    @DisplayName("Удаление: ошибка ввода-вывода → 500-совместимое исключение")
    void deleteWrapsIoError() throws Exception {
        doThrow(new IOException("занято")).when(crlService).delete("busy.crl");

        assertThatThrownBy(() -> api.delete("busy.crl", auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не удалось удалить CRL");
    }

    @Test
    @DisplayName("Удаление: SecurityException сервиса → 400 «Недопустимое имя файла»")
    void deleteWrapsSecurityError() throws Exception {
        doThrow(new SecurityException("path traversal")).when(crlService).delete("evil.crl");

        assertThatThrownBy(() -> api.delete("evil.crl", auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Недопустимое имя файла");
    }

    @Test
    @DisplayName("safeFileName: пустое и недопустимое имя отклоняются, корректное — без изменений")
    void safeFileNameRules() {
        assertThatThrownBy(() -> AdminCrlApi.safeFileName(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Не указано имя файла");
        assertThatThrownBy(() -> AdminCrlApi.safeFileName("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Не указано имя файла");

        for (String bad : List.of("a/b.crl", "a\\b.crl", "..crl", ".hidden.crl", "a..b.crl")) {
            assertThatThrownBy(() -> AdminCrlApi.safeFileName(bad))
                    .as("имя «%s»", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Недопустимое имя файла");
        }

        assertThat(AdminCrlApi.safeFileName("  manual-1.crl  ")).isEqualTo("manual-1.crl");
    }

    // ════════════════════════════ отчёт ════════════════════════════

    @Test
    @DisplayName("Отчёт: сервис вернул null → 409-совместимое исключение")
    void statusWithoutReport() {
        when(crlStatusService.report()).thenReturn(null);

        assertThatThrownBy(() -> api.status(auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Отчёт по CRL недоступен");
    }

    @Test
    @DisplayName("Отчёт: null-элементы и null-сводка превращаются в пустые значения")
    void statusToleratesNullParts() {
        when(crlStatusService.report())
                .thenReturn(new CrlStatusService.Report(null, null));

        Map<String, Object> body = api.status(auth);

        assertThat((List<?>) body.get("items")).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) body.get("summary");
        assertThat(summary)
                .containsEntry("total", 0)
                .containsEntry("ok", 0)
                .containsEntry("expiring", 0)
                .containsEntry("expired", 0)
                .containsEntry("untrusted", 0);
    }

    @Test
    @DisplayName("Отчёт: null-статус элемента отображается как ok")
    void statusItemDefaultsToOk() {
        Instant now = Instant.now();
        List<CrlStatusService.CrlStatusInfo> items = new ArrayList<>();
        items.add(new CrlStatusService.CrlStatusInfo(
                "manual.crl", "CN=Test CA", now, now, "1,0 КБ", true, null, "замечание"));
        when(crlStatusService.report()).thenReturn(new CrlStatusService.Report(
                items, new CrlStatusService.Summary(1, 1, 0, 0, 0)));

        Map<String, Object> body = api.status(auth);

        List<?> result = (List<?>) body.get("items");
        assertThat(result).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) result.get(0);
        assertThat(item).containsEntry("status", "ok")
                .containsEntry("fileName", "manual.crl")
                .containsEntry("note", "замечание");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) body.get("summary");
        assertThat(summary).containsEntry("total", 1);
    }
}
