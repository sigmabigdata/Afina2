package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.DocumentTemplate;
import ru.afina.entity.Role;
import ru.afina.entity.TemplateKind;
import ru.afina.entity.User;
import ru.afina.repository.DocumentRepository;
import ru.afina.repository.DocumentTemplateRepository;
import ru.afina.repository.UserRepository;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsentService: согласие на обработку ПДн")
class ConsentServiceTest {

    @Mock private DocumentTemplateRepository templateRepository;
    @Mock private FolderService folderService;
    @Mock private DocumentTemplateService templateService;
    @Mock private DocumentService documentService;
    @Mock private DocumentRepository documentRepository;
    @Mock private UserRepository userRepository;
    @Mock private ru.afina.security.PiiEncryptor pii;

    private ConsentService service;
    private User user;
    private DocumentTemplate template;

    @BeforeEach
    void setUp() {
        service = new ConsentService(templateRepository, folderService, templateService,
                documentService, documentRepository, userRepository, pii);
        user = new User();
        user.setId(3L);
        user.setEmail("client@test.ru");
        user.setRole(Role.ROLE_USER);
        user.setFullName("Старое ФИО");

        template = new DocumentTemplate();
        template.setId(11L);
        template.setName("Согласие");
        template.setKind(TemplateKind.CONSENT);
        template.setActive(true);
        template.setHtmlContent("<html><body>{{fio}} {{date}}</body></html>");
    }

    private void stubTemplate() {
        lenient().when(templateRepository.findFirstByActiveTrueAndKindOrderBySortOrderAscNameAsc(
                TemplateKind.CONSENT)).thenReturn(Optional.of(template));
    }

    @Test
    @DisplayName("шаблон согласия и признак настройки")
    void configured() {
        stubTemplate();
        assertThat(service.consentTemplate()).contains(template);
        assertThat(service.consentConfigured()).isTrue();
    }

    @Test
    @DisplayName("шаблона нет → гейт онбординга выключен")
    void notConfigured() {
        when(templateRepository.findFirstByActiveTrueAndKindOrderBySortOrderAscNameAsc(
                TemplateKind.CONSENT)).thenReturn(Optional.empty());

        assertThat(service.consentConfigured()).isFalse();
        assertThat(service.needsProfile(user)).isFalse();
    }

    @Test
    @DisplayName("администратор онбординг не проходит")
    void adminIsNotGated() {
        stubTemplate();
        user.setRole(Role.ROLE_ADMIN);

        assertThat(service.needsProfile(user)).isFalse();
        assertThat(service.needsProfile(null)).isFalse();
        assertThat(service.consentSigned(null)).isFalse();
    }

    @Test
    @DisplayName("нужен профиль до подтверждения ФИО, подпись — отдельный признак")
    void gate() {
        stubTemplate();

        assertThat(service.needsProfile(user)).isTrue();
        assertThat(service.consentSigned(user)).isFalse();

        user.setFullNameConfirmedAt(Instant.now());
        assertThat(service.needsProfile(user)).isFalse();
        assertThat(service.consentSigned(user)).isFalse();

        user.setConsentSignedAt(Instant.now());
        assertThat(service.consentSigned(user)).isTrue();
    }

    @Test
    @DisplayName("онбординг: ФИО перекрывает карточку, PDF попадает в раздел «Согласия»")
    void completesOnboarding() throws Exception {
        stubTemplate();
        DocumentFolder folder = new DocumentFolder();
        folder.setId(21L);
        folder.setName(ConsentService.CONSENT_FOLDER_NAME);
        Document doc = new Document();
        doc.setId(31L);
        when(folderService.findOrCreate(user, ConsentService.CONSENT_FOLDER_NAME)).thenReturn(folder);
        when(templateService.renderPdf(eq(template), anyMap())).thenReturn("%PDF-1.7".getBytes());
        when(documentService.createDocument(any(byte[].class), anyString(), anyString(),
                eq(user), eq(folder))).thenReturn(doc);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        Document result = service.completeProfileAndCreateConsent(user, "петров пётр петрович", "+7 900 111-22-33");

        assertThat(result).isSameAs(doc);
        assertThat(user.getFullName()).isEqualTo("Петров Пётр Петрович");
        assertThat(user.getPhone()).isEqualTo("+79001112233");
        assertThat(user.getFullNameConfirmedAt()).isNotNull();
        assertThat(user.getConsentDocumentId()).isEqualTo(31L);
        assertThat(user.getConsentSignedAt()).isNull();

        String expectedName = "Согласие на обработку персональных данных от "
                + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                + ".pdf";
        verify(documentService).createDocument(any(byte[].class), eq(expectedName),
                eq("application/pdf"), eq(user), eq(folder));
    }

    @Test
    @DisplayName("повторный онбординг удаляет старое согласие и создаёт новое")
    void regeneratesConsent() throws Exception {
        stubTemplate();
        DocumentFolder folder = new DocumentFolder();
        folder.setId(21L);
        Document old = new Document();
        old.setId(30L);
        old.setFolder(folder);
        user.setConsentDocumentId(30L);

        when(documentRepository.findById(30L)).thenReturn(Optional.of(old));
        when(folderService.findOrCreate(user, ConsentService.CONSENT_FOLDER_NAME)).thenReturn(folder);
        when(templateService.renderPdf(eq(template), anyMap())).thenReturn("%PDF".getBytes());
        Document fresh = new Document();
        fresh.setId(32L);
        when(documentService.createDocument(any(byte[].class), anyString(), anyString(),
                eq(user), eq(folder))).thenReturn(fresh);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        Document result = service.completeProfileAndCreateConsent(user, "Иванов Иван Иванович", "+79001112233");

        verify(documentRepository).delete(old);
        assertThat(result).isSameAs(fresh);
        assertThat(user.getConsentDocumentId()).isEqualTo(32L);
    }

    @Test
    @DisplayName("некорректное ФИО → ошибка валидации, документ не создаётся")
    void rejectsBadFullName() {
        assertThatThrownBy(() -> service.completeProfileAndCreateConsent(user, "Иванов", "+79001112233"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Укажите фамилию, имя и отчество");
        verifyNoInteractions(documentService);
    }

    @Test
    @DisplayName("некорректный телефон → ошибка валидации")
    void rejectsBadPhone() {
        assertThatThrownBy(() -> service.completeProfileAndCreateConsent(
                user, "Иванов Иван Иванович", "123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Укажите телефон в формате +7XXXXXXXXXX");
        verifyNoInteractions(documentService);
    }

    @Test
    @DisplayName("ensureConsentDocument: существующий документ возвращается как есть")
    void ensuresExistingConsent() {
        Document doc = new Document();
        doc.setId(31L);
        doc.setFolder(new DocumentFolder());
        user.setConsentDocumentId(31L);
        when(documentRepository.findById(31L)).thenReturn(Optional.of(doc));

        assertThat(service.ensureConsentDocument(user)).isSameAs(doc);
        verifyNoInteractions(documentService);
    }

    @Test
    @DisplayName("ensureConsentDocument: удалённый документ формируется заново")
    void regeneratesMissingConsent() throws Exception {
        stubTemplate();
        user.setConsentDocumentId(31L);
        user.setPhone("+79001112233");
        DocumentFolder folder = new DocumentFolder();
        when(documentRepository.findById(31L)).thenReturn(Optional.empty());
        when(folderService.findOrCreate(user, ConsentService.CONSENT_FOLDER_NAME)).thenReturn(folder);
        when(templateService.renderPdf(eq(template), anyMap())).thenReturn("%PDF".getBytes());
        Document fresh = new Document();
        fresh.setId(33L);
        when(documentService.createDocument(any(), anyString(), anyString(), eq(user), eq(folder)))
                .thenReturn(fresh);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.ensureConsentDocument(user)).isSameAs(fresh);
        assertThat(user.getConsentDocumentId()).isEqualTo(33L);
    }

    @Test
    @DisplayName("шаблон не настроен → понятная ошибка")
    void templateMissing() {
        when(templateRepository.findFirstByActiveTrueAndKindOrderBySortOrderAscNameAsc(
                TemplateKind.CONSENT)).thenReturn(Optional.empty());
        DocumentFolder folder = new DocumentFolder();
        when(folderService.findOrCreate(user, ConsentService.CONSENT_FOLDER_NAME)).thenReturn(folder);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        assertThatThrownBy(() -> service.completeProfileAndCreateConsent(user, "Иванов Иван Иванович", "+79001112233"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Шаблон согласия");
    }

    @Test
    @DisplayName("сбой сохранения PDF → понятная ошибка")
    void renderFailure() throws Exception {
        stubTemplate();
        DocumentFolder folder = new DocumentFolder();
        when(folderService.findOrCreate(user, ConsentService.CONSENT_FOLDER_NAME)).thenReturn(folder);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(templateService.renderPdf(eq(template), anyMap()))
                .thenThrow(new IllegalStateException("нет шрифта"));

        assertThatThrownBy(() -> service.completeProfileAndCreateConsent(user, "Иванов Иван Иванович", "+79001112233"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не удалось сформировать согласие");
    }

    @Test
    @DisplayName("ошибка записи документа → ISE")
    void documentWriteFailure() throws Exception {
        stubTemplate();
        DocumentFolder folder = new DocumentFolder();
        when(folderService.findOrCreate(user, ConsentService.CONSENT_FOLDER_NAME)).thenReturn(folder);
        when(templateService.renderPdf(eq(template), anyMap())).thenReturn("%PDF".getBytes());
        when(documentService.createDocument(any(), anyString(), anyString(), eq(user), eq(folder)))
                .thenThrow(new IOException("диск полон"));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        assertThatThrownBy(() -> service.completeProfileAndCreateConsent(user, "Иванов Иван Иванович", "+79001112233"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не удалось сохранить согласие");
    }

    @Test
    @DisplayName("в согласие подставляются ФИО, дата, паспорт и пол")
    void fillsPlaceholders() throws Exception {
        stubTemplate();
        DocumentFolder folder = new DocumentFolder();
        user.setPassportSeries("1234");
        user.setPassportNumber("567890");
        user.setPassportDeptCode("770-001");
        user.setPassportIssuedBy("ОВД Арбат");
        user.setBirthDate(LocalDate.of(1985, 8, 7));
        user.setBirthPlace("Москва");
        user.setRegistrationAddress("Москва, ул. Тверская, 1");
        user.setSex("M");
        user.setInn("7700000001");
        user.setSnils("000-000-000 00");
        user.setPhone("+79001112233");
        when(folderService.findOrCreate(user, ConsentService.CONSENT_FOLDER_NAME)).thenReturn(folder);
        when(templateService.renderPdf(eq(template), anyMap())).thenReturn("%PDF".getBytes());
        when(documentService.createDocument(any(), anyString(), anyString(), eq(user), eq(folder)))
                .thenReturn(new Document());
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.completeProfileAndCreateConsent(user, "Иванов Иван Иванович", "+79001112233");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
        verify(templateService).renderPdf(eq(template), values.capture());
        Map<String, String> v = values.getValue();
        assertThat(v).containsEntry("fio", "Иванов Иван Иванович")
                .containsEntry("passport", "1234 567890")
                .containsEntry("passport_series", "1234")
                .containsEntry("passport_number", "567890")
                .containsEntry("passport_dept_code", "770-001")
                .containsEntry("passport_issued_by", "ОВД Арбат")
                .containsEntry("passport_issue_date", "")
                .containsEntry("birth_date", "07.08.1985")
                .containsEntry("birth_place", "Москва")
                .containsEntry("address", "Москва, ул. Тверская, 1")
                .containsEntry("sex", "мужской")
                .containsEntry("inn", "7700000001")
                .containsEntry("email", "client@test.ru")
                .containsEntry("phone", "+79001112233");
        assertThat(v.get("date")).matches("\\d{2}\\.\\d{2}\\.\\d{4}");
    }

    @Test
    @DisplayName("«У меня нет паспорта» → в согласии особая пометка")
    void noPassport() throws Exception {
        stubTemplate();
        DocumentFolder folder = new DocumentFolder();
        user.setNoPassport(true);
        user.setSex("F");
        when(folderService.findOrCreate(user, ConsentService.CONSENT_FOLDER_NAME)).thenReturn(folder);
        when(templateService.renderPdf(eq(template), anyMap())).thenReturn("%PDF".getBytes());
        when(documentService.createDocument(any(), anyString(), anyString(), eq(user), eq(folder)))
                .thenReturn(new Document());
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.completeProfileAndCreateConsent(user, "Иванова Анна Сергеевна", "+79001112233");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
        verify(templateService).renderPdf(eq(template), values.capture());
        assertThat(values.getValue())
                .containsEntry("passport", "паспорт отсутствует")
                .containsEntry("sex", "женский");
    }

    @Test
    @DisplayName("подпись согласия: отметка ставится, null игнорируется")
    void markSigned() {
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.markSigned(null);
        verify(userRepository, never()).save(any());

        service.markSigned(user);
        assertThat(user.getConsentSignedAt()).isNotNull();
    }

    @Test
    @DisplayName("isConsentDocument сравнивает id документа клиента")
    void isConsentDocument() {
        Document doc = new Document();
        doc.setId(31L);

        assertThat(service.isConsentDocument(user, doc)).isFalse();
        user.setConsentDocumentId(31L);
        assertThat(service.isConsentDocument(user, doc)).isTrue();
        assertThat(service.isConsentDocument(null, doc)).isFalse();
        assertThat(service.isConsentDocument(user, null)).isFalse();
    }
}
