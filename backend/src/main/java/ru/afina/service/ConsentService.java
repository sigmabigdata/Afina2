package ru.afina.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.DocumentTemplate;
import ru.afina.entity.TemplateKind;
import ru.afina.entity.User;
import ru.afina.repository.DocumentTemplateRepository;
import ru.afina.repository.DocumentRepository;
import ru.afina.repository.UserRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Согласие на обработку персональных данных.
 *
 * <p>Администратор создаёт в разделе «Шаблоны» шаблон с назначением {@code CONSENT}
 * (клиентам он не виден). При первом входе клиент вводит ФИО, отмечает согласие —
 * система генерирует PDF по этому шаблону (текущая дата, ФИО, паспортные данные),
 * кладёт его в автоматически созданный раздел «Согласия» и просит клиента
 * подписать документ УКЭП. До подписания доступ к карточке закрыт.
 */
@Service
public class ConsentService {

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    /** Раздел карточки клиента, куда попадает подписанное согласие. */
    public static final String CONSENT_FOLDER_NAME = "Согласия";

    private static final DateTimeFormatter DATE_RU = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final DocumentTemplateRepository templateRepository;
    private final FolderService folderService;
    private final DocumentTemplateService templateService;
    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final ru.afina.security.PiiEncryptor pii;

    public ConsentService(DocumentTemplateRepository templateRepository,
                          FolderService folderService,
                          DocumentTemplateService templateService,
                          DocumentService documentService,
                          DocumentRepository documentRepository,
                          UserRepository userRepository,
                          ru.afina.security.PiiEncryptor pii) {
        this.templateRepository = templateRepository;
        this.folderService = folderService;
        this.templateService = templateService;
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.pii = pii;
    }

    /** Активный шаблон согласия (если администратор его создал). */
    public Optional<DocumentTemplate> consentTemplate() {
        return templateRepository
                .findFirstByActiveTrueAndKindOrderBySortOrderAscNameAsc(TemplateKind.CONSENT);
    }

    /** Настроено ли согласие в системе (есть активный шаблон CONSENT). */
    public boolean consentConfigured() {
        return consentTemplate().isPresent();
    }

    /** Нужно ли клиенту заполнить профиль (ФИО и телефон) при первом входе. */
    public boolean needsProfile(User user) {
        if (user == null || user.getRole() != ru.afina.entity.Role.ROLE_USER) return false;
        if (!consentConfigured()) return false;      // согласие не настроено — не блокируем
        return user.getFullNameConfirmedAt() == null;
    }

    /** Подписано ли клиентом согласие на обработку персональных данных. */
    public boolean consentSigned(User user) {
        return user != null && user.getConsentSignedAt() != null;
    }

    /**
     * Завершает первый шаг онбординга: сохраняет ФИО и телефон клиента
     * (перекрывают введённое администратором), создаёт раздел «Согласия»
     * и генерирует документ согласия.
     *
     * @return документ согласия, который клиент должен подписать
     */
    @Transactional
    public Document completeProfileAndCreateConsent(User user, String fullName, String phone) {
        String normalized = normalizeFullName(fullName);
        if (normalized == null) {
            throw new IllegalArgumentException("Укажите фамилию, имя и отчество");
        }
        String normalizedPhone = ru.afina.util.PersonData.normalizePhone(phone);
        if (normalizedPhone == null) {
            throw new IllegalArgumentException("Укажите телефон в формате +7XXXXXXXXXX");
        }
        user.setFullName(normalized);
        user.setPhone(normalizedPhone);
        user.setPhoneHash(pii.hashPhone(normalizedPhone));
        user.setFullNameConfirmedAt(Instant.now());
        userRepository.save(user);

        DocumentFolder folder = folderService.findOrCreate(user, CONSENT_FOLDER_NAME);

        // Переиспользуем уже созданный документ согласия, если он есть и не подписан
        if (user.getConsentDocumentId() != null) {
            Optional<Document> existing = documentRepository.findById(user.getConsentDocumentId());
            if (existing.isPresent() && existing.get().getFolder() != null) {
                documentRepository.delete(existing.get());   // перегенерируем с новым ФИО
            }
        }

        DocumentTemplate template = consentTemplate()
                .orElseThrow(() -> new IllegalStateException(
                        "Шаблон согласия на обработку персональных данных не настроен администратором"));

        byte[] pdf = renderConsent(template, user);
        String fileName = "Согласие на обработку персональных данных от "
                + LocalDate.now().format(FILE_TS) + ".pdf";
        Document doc;
        try {
            doc = documentService.createDocument(pdf, fileName, "application/pdf", user, folder);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Не удалось сохранить согласие: " + e.getMessage(), e);
        }

        user.setConsentDocumentId(doc.getId());
        user.setConsentSignedAt(null);
        userRepository.save(user);

        log.info("Согласие сформировано: user={}, doc={}", user.getId(), doc.getId());
        return doc;
    }

    /**
     * Документ согласия клиента; если он был удалён — формирует заново
     * по уже сохранённым ФИО и телефону.
     */
    @Transactional
    public Document ensureConsentDocument(User user) {
        if (user.getConsentDocumentId() != null) {
            Optional<Document> existing = documentRepository.findById(user.getConsentDocumentId());
            if (existing.isPresent() && existing.get().getFolder() != null) {
                return existing.get();
            }
        }
        return completeProfileAndCreateConsent(user, user.getFullName(), user.getPhone());
    }

    /** Отмечает согласие подписанным (вызывается после успешной подписи документа-согласия). */
    @Transactional
    public void markSigned(User user) {
        if (user == null) return;
        user.setConsentSignedAt(Instant.now());
        userRepository.save(user);
        log.info("Согласие подписано клиентом: user={}, doc={}", user.getId(), user.getConsentDocumentId());
    }

    /** Документ согласия клиента (для проверки в SignApiController). */
    public boolean isConsentDocument(User user, Document doc) {
        return user != null && doc != null
                && user.getConsentDocumentId() != null
                && user.getConsentDocumentId().equals(doc.getId());
    }

    private byte[] renderConsent(DocumentTemplate template, User user) {
        Map<String, String> values = new HashMap<>();
        String fio = user.getFullName() == null ? "" : user.getFullName();
        String today = LocalDate.now().format(DATE_RU);

        values.put("fio", fio);
        values.put("full_name", fio);
        values.put("user_name", fio);
        values.put("date", today);
        values.put("today", today);
        values.put("email", nvl(user.getEmail()));
        values.put("phone", nvl(user.getPhone()));
        values.put("snils", nvl(user.getSnils()));
        values.put("inn", nvl(user.getInn()));
        values.put("sex", sexLabel(user.getSex()));
        values.put("birth_date", user.getBirthDate() == null ? "" : user.getBirthDate().format(DATE_RU));
        values.put("birth_place", nvl(user.getBirthPlace()));
        values.put("address", nvl(user.getRegistrationAddress()));
        values.put("registration_address", nvl(user.getRegistrationAddress()));
        values.put("passport_series", nvl(user.getPassportSeries()));
        values.put("passport_number", nvl(user.getPassportNumber()));
        values.put("passport", passportLabel(user));
        values.put("passport_issue_date",
                user.getPassportIssueDate() == null ? "" : user.getPassportIssueDate().format(DATE_RU));
        values.put("passport_dept_code", nvl(user.getPassportDeptCode()));
        values.put("passport_issued_by", nvl(user.getPassportIssuedBy()));

        try {
            return templateService.renderPdf(template, values);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сформировать согласие: " + e.getMessage(), e);
        }
    }

    private static String passportLabel(User user) {
        if (user.isNoPassport()) return "паспорт отсутствует";
        String series = nvl(user.getPassportSeries());
        String number = nvl(user.getPassportNumber());
        if (series.isBlank() && number.isBlank()) return "";
        return (series + " " + number).trim();
    }

    private static String sexLabel(String sex) {
        if (sex == null) return "";
        return switch (sex) {
            case "M" -> "мужской";
            case "F" -> "женский";
            default -> sex;
        };
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    /** ФИО: 2–4 слова, только буквы/дефис/пробелы; приводим к «Фамилия Имя Отчество». */
    public static String normalizeFullName(String raw) {
        return ru.afina.util.PersonData.normalizeFullName(raw);
    }
}
