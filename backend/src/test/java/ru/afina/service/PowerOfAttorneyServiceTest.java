package ru.afina.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import ru.afina.entity.PowerOfAttorney;
import ru.afina.error.NotFoundException;
import ru.afina.repository.PowerOfAttorneyRepository;
import ru.afina.security.PiiEncryptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Бизнес-логика МЧД: разбор XML при загрузке, включение/удаление,
 * поиск действующей доверенности по ФИО и ежедневные оповещения о сроке.
 *
 * <p>Репозитории и внешние сервисы — mock'и, поэтому тесты не зависят от БД,
 * часового пояса и SMTP.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PowerOfAttorneyService: загрузка, поиск и оповещения")
class PowerOfAttorneyServiceTest {

    private static final String ADMIN = "admin@test.local";

    private static final String VALID_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Доверенность xmlns="urn://x-artefacts/EMCHD_1">
              <СвДов ВнНомДовер="1042" НомДовер="2b1c6f44-6f1c-4c67-9c62-9d3f0e9f0001"
                     ДатаВыдДовер="2025-01-15" СрокДейст="2030-12-31"/>
              <СвУпПред>
                <ФИО Фамилия="Иванов" Имя="Иван" Отчество="Иванович"/>
                <СведФизЛ СНИЛС="12345678901" ИННФЛ="500100732259"/>
              </СвУпПред>
              <СвРосОрг НаимОрг="ООО Афина" ИННЮЛ="7701234567"/>
              <ФИОНотДейств Фамилия="Петрова" Имя="Анна" Отчество="Сергеевна"/>
            </Доверенность>
            """;

    @Mock private PowerOfAttorneyRepository repo;
    @Mock private PiiEncryptor pii;
    @Mock private AuditService audit;
    @Mock private SettingsService settings;
    @Mock private EmailService emailService;

    private PowerOfAttorneyService service;
    private Logger serviceLogger;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        service = new PowerOfAttorneyService(repo, pii, audit, settings, emailService);
        lenient().when(pii.hash(anyString())).thenAnswer(inv -> "hash:" + inv.getArgument(0));

        // DEBUG-ветка логирования в findEffectiveByFio включается только на уровне DEBUG
        serviceLogger = (Logger) LoggerFactory.getLogger(PowerOfAttorneyService.class);
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void restoreLogLevel() {
        serviceLogger.setLevel(previousLevel);
    }

    // ──────────────────────────── помощники ────────────────────────────

    private static byte[] xml(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static PowerOfAttorney poa(Long id, LocalDate validUntil, boolean active) {
        PowerOfAttorney p = new PowerOfAttorney();
        p.setId(id);
        p.setFio("Иванов Иван Иванович");
        p.setFioHash("hash:ИВАНОВ ИВАН ИВАНОВИЧ");
        p.setValidUntil(validUntil);
        p.setActive(active);
        p.setNotified30d(false);
        p.setNotified7d(false);
        return p;
    }

    // ──────────────────────────── загрузка ────────────────────────────

    @Test
    @DisplayName("Загрузка валидного XML: поля извлечены, запись активна, аудит записан")
    void uploadParsesXml() throws Exception {
        when(repo.existsByFileSha256(anyString())).thenReturn(false);
        when(repo.save(any())).thenAnswer(inv -> {
            PowerOfAttorney saved = inv.getArgument(0);
            saved.setId(42L);
            return saved;
        });

        PowerOfAttorney saved = service.upload(xml(VALID_XML), ADMIN);

        assertThat(saved.getId()).isEqualTo(42L);
        assertThat(saved.getFio()).isEqualTo("Иванов Иван Иванович");
        assertThat(saved.getFioHash()).isEqualTo("hash:ИВАНОВ ИВАН ИВАНОВИЧ");
        assertThat(saved.getSnils()).isEqualTo("12345678901");
        assertThat(saved.getInn()).isEqualTo("500100732259");
        assertThat(saved.getOrgName()).isEqualTo("ООО Афина");
        assertThat(saved.getOrgInn()).isEqualTo("7701234567");
        assertThat(saved.getDocNumber()).isEqualTo("1042");
        assertThat(saved.getStampNumber()).isEqualTo("2b1c6f44-6f1c-4c67-9c62-9d3f0e9f0001");
        assertThat(saved.getDocDate()).isEqualTo(LocalDate.of(2025, 1, 15));
        assertThat(saved.getValidFrom()).isEqualTo(LocalDate.of(2025, 1, 15));
        assertThat(saved.getValidUntil()).isEqualTo(LocalDate.of(2030, 12, 31));
        assertThat(saved.getNotaryName()).isEqualTo("Петрова Анна Сергеевна");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getCreatedBy()).isEqualTo(ADMIN);
        assertThat(saved.getFileSha256()).hasSize(64);
        assertThat(saved.getXmlContent()).contains("Доверенность");

        verify(audit).poaUpload(ADMIN, 42L, "Иванов Иван Иванович", "2030-12-31");
    }

    @Test
    @DisplayName("Повторная загрузка того же файла отклоняется по SHA-256")
    void uploadRejectsDuplicate() {
        when(repo.existsByFileSha256(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.upload(xml(VALID_XML), ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже загружена");

        verify(repo, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("Битый XML → ParseException, запись не создаётся")
    void uploadRejectsBrokenXml() {
        when(repo.existsByFileSha256(anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.upload(xml("это не XML"), ADMIN))
                .isInstanceOf(PowerOfAttorneyXmlParser.ParseException.class)
                .hasMessageContaining("Не удалось разобрать XML");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("XML без срока действия → ParseException")
    void uploadRejectsXmlWithoutValidity() {
        when(repo.existsByFileSha256(anyString())).thenReturn(false);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Доверенность xmlns="urn://x-artefacts/EMCHD_1">
                  <СвДов ВнНомДовер="1" ДатаВыдДовер="2025-01-15"/>
                  <СвУпПред><ФИО Фамилия="Иванов" Имя="Иван"/></СвУпПред>
                </Доверенность>
                """;

        assertThatThrownBy(() -> service.upload(xml(xml), ADMIN))
                .isInstanceOf(PowerOfAttorneyXmlParser.ParseException.class)
                .hasMessageContaining("срок действия");
    }

    @Test
    @DisplayName("listAll возвращает репозиторий как есть")
    void listAllDelegates() {
        List<PowerOfAttorney> expected = List.of(poa(1L, LocalDate.now().plusDays(5), true));
        when(repo.findAllByOrderByValidUntilAsc()).thenReturn(expected);

        assertThat(service.listAll()).isSameAs(expected);
    }

    // ──────────────────────────── активность и удаление ────────────────────────────

    @Test
    @DisplayName("setActive: несуществующая доверенность → NotFoundException")
    void setActiveUnknownIdFails() {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setActive(999L, false, ADMIN))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Доверенность не найдена");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("setActive: существующая доверенность сохраняется с новым признаком")
    void setActiveUpdatesFlag() {
        PowerOfAttorney stored = poa(7L, LocalDate.now().plusDays(10), true);
        when(repo.findById(7L)).thenReturn(Optional.of(stored));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setActive(7L, false, ADMIN);

        assertThat(stored.isActive()).isFalse();
        verify(repo).save(stored);
        verify(audit).poaToggle(ADMIN, 7L, false);
    }

    @Test
    @DisplayName("delete: несуществующая доверенность → NotFoundException")
    void deleteUnknownIdFails() {
        when(repo.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(404L, ADMIN))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Доверенность не найдена");

        verify(repo, never()).delete(any());
    }

    @Test
    @DisplayName("delete: доверенность удаляется, аудит фиксирует номер")
    void deleteRemovesEntity() {
        PowerOfAttorney stored = poa(8L, LocalDate.now().plusDays(10), true);
        stored.setDocNumber("1042");
        when(repo.findById(8L)).thenReturn(Optional.of(stored));

        service.delete(8L, ADMIN);

        verify(repo).delete(stored);
        verify(audit).poaDelete(ADMIN, 8L, "1042");
    }

    // ──────────────────────────── поиск по ФИО ────────────────────────────

    @Test
    @DisplayName("findEffectiveByFio: пустое ФИО → пусто без обращения к БД")
    void findEffectiveByFioRejectsBlank() {
        assertThat(service.findEffectiveByFio(null)).isEmpty();
        assertThat(service.findEffectiveByFio("   ")).isEmpty();
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("findEffectiveByFio: совпадение → доверенность, hash и дата нормализованы")
    void findEffectiveByFioMatches() {
        PowerOfAttorney stored = poa(15L, LocalDate.now().plusDays(30), true);
        when(repo.findFirstByFioHashAndActiveTrueAndValidUntilGreaterThanEqualOrderByValidUntilDesc(
                anyString(), any())).thenReturn(Optional.of(stored));

        Optional<PowerOfAttorney> found = service.findEffectiveByFio("иванов иван иванович");

        assertThat(found).contains(stored);

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(repo).findFirstByFioHashAndActiveTrueAndValidUntilGreaterThanEqualOrderByValidUntilDesc(
                hashCaptor.capture(), dateCaptor.capture());
        assertThat(hashCaptor.getValue()).isEqualTo("hash:ИВАНОВ ИВАН ИВАНОВИЧ");
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("findEffectiveByFio: другое ФИО → пусто")
    void findEffectiveByFioMismatch() {
        when(repo.findFirstByFioHashAndActiveTrueAndValidUntilGreaterThanEqualOrderByValidUntilDesc(
                anyString(), any())).thenReturn(Optional.empty());

        assertThat(service.findEffectiveByFio("Петров Пётр Петрович")).isEmpty();
    }

    @Test
    @DisplayName("findEffectiveByFio: просроченная доверенность не попадает в выборку")
    void findEffectiveByFioIgnoresExpired() {
        PowerOfAttorney expired = poa(16L, LocalDate.now().minusDays(1), true);
        when(repo.findFirstByFioHashAndActiveTrueAndValidUntilGreaterThanEqualOrderByValidUntilDesc(
                anyString(), any())).thenAnswer(inv -> {
                    LocalDate today = inv.getArgument(1);
                    return today.isAfter(expired.getValidUntil())
                            ? Optional.empty() : Optional.of(expired);
                });

        assertThat(service.findEffectiveByFio("Иванов Иван Иванович")).isEmpty();
    }

    // ──────────────────────────── ежедневные оповещения ────────────────────────────

    @Test
    @DisplayName("dailyExpiryCheck: письма за 30/7 дней и про истёкшую, флаги выставляются")
    void dailyExpiryCheckSendsNotifications() {
        LocalDate today = LocalDate.now();
        PowerOfAttorney poa30 = poa(21L, today.plusDays(20), true);
        PowerOfAttorney poa7 = poa(22L, today.plusDays(5), true);
        PowerOfAttorney expired = poa(23L, today.minusDays(1), true);
        stubExpiring(poa30, poa7, expired);

        when(settings.getOrDefault(eq("monitor.mail_to"), anyString()))
                .thenReturn("ops@test.local");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.dailyExpiryCheck();

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(4)).sendAlert(
                eq("ops@test.local"), subject.capture(), anyString());
        assertThat(subject.getAllValues())
                .anyMatch(s -> s.contains("истекает через 20 дн."))
                .anyMatch(s -> s.contains("истекает через 5 дн."))
                .anyMatch(s -> s.contains("ИСТЕКЛА"));

        assertThat(poa30.isNotified30d()).isTrue();
        assertThat(poa7.isNotified30d()).isTrue();
        assertThat(poa7.isNotified7d()).isTrue();
        // poa30 — флаг 30 дней; poa7 — сначала 30, затем 7 дней
        verify(repo, times(3)).save(any());

        // Повторный запуск: по 30/7 дням уже оповещены, истёкшая — оповещается снова
        clearInvocations(emailService);
        service.dailyExpiryCheck();

        verify(emailService, times(1)).sendAlert(
                eq("ops@test.local"), subject.capture(), anyString());
        assertThat(subject.getValue()).contains("ИСТЕКЛА");
    }

    @Test
    @DisplayName("dailyExpiryCheck: monitor.mail_to не задан → письма не отправляются")
    void dailyExpiryCheckSkipsWithoutRecipient() {
        LocalDate today = LocalDate.now();
        PowerOfAttorney poa30 = poa(31L, today.plusDays(10), true);
        stubExpiring(poa30, List.of(), List.of());

        when(settings.getOrDefault(eq("monitor.mail_to"), anyString())).thenReturn("");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.dailyExpiryCheck();

        verify(emailService, never()).sendAlert(anyString(), anyString(), anyString());
        assertThat(poa30.isNotified30d()).isTrue();
    }

    @Test
    @DisplayName("dailyExpiryCheck: ошибка SMTP не ломает планировщик")
    void dailyExpiryCheckSurvivesEmailFailure() {
        LocalDate today = LocalDate.now();
        PowerOfAttorney poa30 = poa(41L, today.plusDays(3), true);
        stubExpiring(poa30, List.of(), List.of());

        when(settings.getOrDefault(eq("monitor.mail_to"), anyString()))
                .thenReturn("ops@test.local");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new IllegalStateException("SMTP недоступен"))
                .when(emailService).sendAlert(anyString(), anyString(), anyString());

        assertThatCode(() -> service.dailyExpiryCheck()).doesNotThrowAnyException();
        assertThat(poa30.isNotified30d()).isTrue();
    }

    /** Заглушка выборок по срокам: различаем 30-дневное, 7-дневное окно и «вчера». */
    private void stubExpiring(PowerOfAttorney poa30, PowerOfAttorney poa7,
                              PowerOfAttorney expired) {
        when(repo.findAllByActiveTrueAndValidUntilBetween(any(), any())).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(0);
            LocalDate to = inv.getArgument(1);
            LocalDate today = LocalDate.now();
            if (from.equals(today.minusDays(1))) return List.of(expired);
            if (to.equals(today.plusDays(7))) return List.of(poa7);
            return List.of(poa7, poa30);
        });
    }

    /** Вариант заглушки, когда в окнах есть только 30-дневная доверенность. */
    private void stubExpiring(PowerOfAttorney poa30, List<PowerOfAttorney> poa7,
                              List<PowerOfAttorney> expired) {
        when(repo.findAllByActiveTrueAndValidUntilBetween(any(), any())).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(0);
            LocalDate to = inv.getArgument(1);
            LocalDate today = LocalDate.now();
            if (from.equals(today.minusDays(1))) return expired;
            if (to.equals(today.plusDays(7))) return poa7;
            return List.of(poa30);
        });
    }
}
