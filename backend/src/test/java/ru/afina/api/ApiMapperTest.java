package ru.afina.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.api.dto.DocumentDto;
import ru.afina.api.dto.FolderDto;
import ru.afina.api.dto.TemplateDto;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.DocumentSignature;
import ru.afina.entity.DocumentTemplate;
import ru.afina.entity.DocumentVisualization;
import ru.afina.entity.TemplateKind;
import ru.afina.entity.User;
import ru.afina.repository.DocumentSignatureRepository;
import ru.afina.repository.DocumentVisualizationRepository;
import ru.afina.service.ConsentService;
import ru.afina.service.DocumentTemplateService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты {@link ApiMapper}: утилиты форматирования и маппинг сущностей в DTO.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiMapper: преобразование сущностей в DTO")
class ApiMapperTest {

    @Mock
    private DocumentVisualizationRepository visualizationRepository;
    @Mock
    private DocumentSignatureRepository signatureRepository;
    @Mock
    private DocumentTemplateService templateService;
    @Mock
    private ConsentService consentService;

    private ApiMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ApiMapper(visualizationRepository, signatureRepository,
                templateService, consentService);
    }

    // ──────────────────────────── утилиты ────────────────────────────

    @Test
    @DisplayName("humanSize: байты")
    void humanSizeBytes() {
        assertThat(ApiMapper.humanSize(0)).isEqualTo("0 Б");
        assertThat(ApiMapper.humanSize(1)).isEqualTo("1 Б");
        assertThat(ApiMapper.humanSize(512)).isEqualTo("512 Б");
        assertThat(ApiMapper.humanSize(1023)).isEqualTo("1023 Б");
    }

    @Test
    @DisplayName("humanSize: килобайты и мегабайты с запятой")
    void humanSizeKbMb() {
        assertThat(ApiMapper.humanSize(1024)).isEqualTo("1,0 КБ");
        assertThat(ApiMapper.humanSize(1536)).isEqualTo("1,5 КБ");
        assertThat(ApiMapper.humanSize(1024 * 1024)).isEqualTo("1,0 МБ");
        assertThat(ApiMapper.humanSize(1024 * 1024 * 3 / 2)).isEqualTo("1,5 МБ");
        assertThat(ApiMapper.humanSize(1024L * 1024 * 1024)).isEqualTo("1,0 ГБ");
    }

    @Test
    @DisplayName("extensionOf: регистр, отсутствие и null")
    void extensionOf() {
        assertThat(ApiMapper.extensionOf("Договор.PDF")).isEqualTo("pdf");
        assertThat(ApiMapper.extensionOf("archive.tar.gz")).isEqualTo("gz");
        assertThat(ApiMapper.extensionOf("README")).isEmpty();
        assertThat(ApiMapper.extensionOf(null)).isEmpty();
        assertThat(ApiMapper.extensionOf(".hidden")).isEmpty();
        assertThat(ApiMapper.extensionOf("файл.")).isEmpty();
    }

    @Test
    @DisplayName("maskSnils: null, пустой и короткий СНИЛС")
    void maskSnils() {
        assertThat(ApiMapper.maskSnils(null)).isNull();
        assertThat(ApiMapper.maskSnils("   ")).isNull();
        assertThat(ApiMapper.maskSnils("123")).isEqualTo("***");
        assertThat(ApiMapper.maskSnils("12-34")).isEqualTo("***-1234");
        assertThat(ApiMapper.maskSnils("123-456-789 01")).isEqualTo("***-8901");
    }

    // ──────────────────────────── документы ────────────────────────────

    @Test
    @DisplayName("document: поля, владелец, раздел и подписи")
    void documentMapping() {
        User owner = user(10L, "Иванов Иван Иванович");
        DocumentFolder folder = folder(20L, "Договоры");
        UUID publicUuid = UUID.randomUUID();
        Instant uploadedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant signedAt = Instant.parse("2026-01-02T03:04:05Z");

        Document doc = new Document();
        doc.setId(30L);
        doc.setOriginalName("Договор.PDF");
        doc.setContentType("application/pdf");
        doc.setSize(2048);
        doc.setFileSha256("abc123");
        doc.setSigned(true);
        doc.setSignedAt(signedAt);
        doc.setSignerSubject("CN=Иванов Иван");
        doc.setUploadedAt(uploadedAt);
        doc.setApplyPoa(true);
        doc.setPublicUuid(publicUuid);
        doc.setOwner(owner);
        doc.setFolder(folder);

        DocumentSignature signature = new DocumentSignature();
        signature.setId(40L);
        signature.setSignerSubject("CN=Иванов Иван");
        signature.setSignerSerial("AB12");
        signature.setSignedAt(signedAt);
        signature.setApplyPoa(true);
        signature.setSignerUser(owner);
        doc.setSignatures(List.of(signature));

        when(visualizationRepository.findByDocument(doc)).thenReturn(Optional.empty());

        DocumentDto dto = mapper.document(doc);

        assertThat(dto.id()).isEqualTo(30L);
        assertThat(dto.name()).isEqualTo("Договор.PDF");
        assertThat(dto.contentType()).isEqualTo("application/pdf");
        assertThat(dto.size()).isEqualTo(2048);
        assertThat(dto.sizePretty()).isEqualTo("2,0 КБ");
        assertThat(dto.extension()).isEqualTo("pdf");
        assertThat(dto.fileSha256()).isEqualTo("abc123");
        assertThat(dto.signed()).isTrue();
        assertThat(dto.signedAt()).isEqualTo(signedAt);
        assertThat(dto.signerSubject()).isEqualTo("CN=Иванов Иван");
        assertThat(dto.uploadedAt()).isEqualTo(uploadedAt);
        assertThat(dto.applyPoa()).isTrue();
        assertThat(dto.publicUuid()).isEqualTo(publicUuid);
        assertThat(dto.folderId()).isEqualTo(20L);
        assertThat(dto.folderName()).isEqualTo("Договоры");
        assertThat(dto.ownerId()).isEqualTo(10L);
        assertThat(dto.ownerName()).isEqualTo("Иванов Иван Иванович");
        assertThat(dto.signaturesCount()).isEqualTo(1);
        assertThat(dto.visualization()).isNull();
        assertThat(dto.gosklyuchOrder()).isNull();

        assertThat(dto.signatures()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo(40L);
            assertThat(s.signerSubject()).isEqualTo("CN=Иванов Иван");
            assertThat(s.signerSerial()).isEqualTo("AB12");
            assertThat(s.signedAt()).isEqualTo(signedAt);
            assertThat(s.applyPoa()).isTrue();
            assertThat(s.signerUserId()).isEqualTo(10L);
            assertThat(s.signerName()).isEqualTo("Иванов Иван Иванович");
        });
    }

    @Test
    @DisplayName("document: сбой чтения визуализации не ломает карточку")
    void documentVisualizationFailureDoesNotBreakMapping() {
        Document doc = minimalDocument();
        when(visualizationRepository.findByDocument(doc))
                .thenThrow(new IllegalStateException("БД недоступна"));

        DocumentDto dto = mapper.document(doc);

        assertThat(dto.id()).isEqualTo(100L);
        assertThat(dto.visualization()).isNull();
        assertThat(dto.ownerId()).isNull();
        assertThat(dto.folderId()).isNull();
        assertThat(dto.signaturesCount()).isZero();
        assertThat(dto.signatures()).isEmpty();
    }

    @Test
    @DisplayName("document: визуализация попадает в DTO")
    void documentWithVisualization() {
        Document doc = minimalDocument();
        Instant created = Instant.parse("2026-02-01T10:00:00Z");
        DocumentVisualization visualization = new DocumentVisualization();
        visualization.setVersion(3);
        visualization.setSize(1234);
        visualization.setCreatedAt(created);
        visualization.setUpdatedAt(created.plusSeconds(60));
        when(visualizationRepository.findByDocument(doc)).thenReturn(Optional.of(visualization));

        DocumentDto dto = mapper.document(doc);

        assertThat(dto.visualization()).isNotNull();
        assertThat(dto.visualization().version()).isEqualTo(3);
        assertThat(dto.visualization().size()).isEqualTo(1234);
        assertThat(dto.visualization().createdAt()).isEqualTo(created);
        assertThat(dto.visualization().updatedAt()).isEqualTo(created.plusSeconds(60));
    }

    // ──────────────────────────── разделы ────────────────────────────

    @Test
    @DisplayName("folder: DTO с названием и счётчиками")
    void folderMapping() {
        DocumentFolder folder = folder(7L, "Акты");
        Instant created = Instant.parse("2026-02-03T04:05:06Z");
        folder.setCreatedAt(created);

        FolderDto dto = mapper.folder(folder, 5, 2);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.name()).isEqualTo("Акты");
        assertThat(dto.documentsCount()).isEqualTo(5);
        assertThat(dto.signedCount()).isEqualTo(2);
        assertThat(dto.createdAt()).isEqualTo(created);
    }

    // ──────────────────────────── шаблоны ────────────────────────────

    @Test
    @DisplayName("template: DTO и количество полей")
    void templateMapping() {
        DocumentTemplate template = new DocumentTemplate();
        template.setId(9L);
        template.setKind(TemplateKind.DOCUMENT);
        template.setName("Заявление");
        template.setDescription("Описание");
        template.setHtmlContent("<p>{{fio}}</p>");
        template.setFieldsJson("[{\"key\":\"fio\"}]");
        template.setActive(true);
        template.setSortOrder(3);
        template.setCreatedBy("admin");
        Instant created = Instant.parse("2026-03-04T05:06:07Z");
        template.setCreatedAt(created);
        template.setUpdatedAt(created);

        when(templateService.parseFields(template)).thenReturn(List.of(
                new DocumentTemplateService.TemplateField("fio", "ФИО", "text", true, null),
                new DocumentTemplateService.TemplateField("city", "Город", "text", false, null)));

        TemplateDto dto = mapper.template(template);

        assertThat(dto.id()).isEqualTo(9L);
        assertThat(dto.kind()).isEqualTo("DOCUMENT");
        assertThat(dto.name()).isEqualTo("Заявление");
        assertThat(dto.description()).isEqualTo("Описание");
        assertThat(dto.htmlContent()).isEqualTo("<p>{{fio}}</p>");
        assertThat(dto.fieldsJson()).isEqualTo("[{\"key\":\"fio\"}]");
        assertThat(dto.active()).isTrue();
        assertThat(dto.sortOrder()).isEqualTo(3);
        assertThat(dto.createdBy()).isEqualTo("admin");
        assertThat(dto.createdAt()).isEqualTo(created);
        assertThat(dto.updatedAt()).isEqualTo(created);
        assertThat(dto.fieldsCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("template: и пустой kind, и ошибка парсинга полей обрабатываются")
    void templateNullKindAndBrokenFields() {
        DocumentTemplate template = new DocumentTemplate();
        template.setId(11L);
        template.setKind(null);
        template.setName("Без полей");
        template.setActive(false);
        when(templateService.parseFields(template))
                .thenThrow(new IllegalStateException("битый fieldsJson"));

        TemplateDto dto = mapper.template(template);

        assertThat(dto.kind()).isEqualTo("DOCUMENT");
        assertThat(dto.fieldsCount()).isZero();
        assertThat(dto.active()).isFalse();
    }

    // ──────────────────────────── helpers ────────────────────────────

    private static User user(Long id, String fullName) {
        User user = new User();
        user.setId(id);
        user.setFullName(fullName);
        return user;
    }

    private static DocumentFolder folder(Long id, String name) {
        DocumentFolder folder = new DocumentFolder();
        folder.setId(id);
        folder.setName(name);
        return folder;
    }

    private static Document minimalDocument() {
        Document doc = new Document();
        doc.setId(100L);
        doc.setOriginalName("file.txt");
        doc.setContentType("text/plain");
        doc.setSize(10);
        doc.setUploadedAt(Instant.parse("2026-01-01T00:00:00Z"));
        doc.setPublicUuid(UUID.randomUUID());
        return doc;
    }
}
