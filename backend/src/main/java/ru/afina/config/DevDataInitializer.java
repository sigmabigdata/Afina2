package ru.afina.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.DocumentTemplate;
import ru.afina.entity.Role;
import ru.afina.entity.TemplateKind;
import ru.afina.entity.User;
import ru.afina.repository.DocumentFolderRepository;
import ru.afina.repository.DocumentTemplateRepository;
import ru.afina.repository.UserRepository;
import ru.afina.security.PiiEncryptor;
import ru.afina.service.AuditService;
import ru.afina.service.DocumentService;
import ru.afina.service.FolderService;

import java.time.Instant;

/**
 * Демонстрационные данные для локальной разработки и витрины.
 *
 * <p>Включается только явно: {@code app.seed.enabled=true} (в production-профиле
 * выключено). Создаёт администратора, клиента, разделы, шаблоны и несколько
 * документов, чтобы интерфейс был наполнен с первого запуска.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class DevDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private static final String ADMIN_EMAIL = "admin@afina.local";
    private static final String CLIENT_EMAIL = "client@afina.local";

    @Bean
    public ApplicationRunner seedDemoData(UserRepository userRepository,
                                          DocumentFolderRepository folderRepository,
                                          DocumentTemplateRepository templateRepository,
                                          PiiEncryptor pii,
                                          FolderService folderService,
                                          DocumentService documentService,
                                          AuditService audit) {
        return args -> {
            if (userRepository.count() > 0) {
                log.info("Демо-данные уже есть — пропускаем первичное наполнение");
                return;
            }
            log.warn("=== СОЗДАНИЕ ДЕМО-ДАННЫХ (app.seed.enabled=true) ===");

            User admin = new User();
            admin.setEmail(ADMIN_EMAIL);
            admin.setEmailHash(pii.hash(ADMIN_EMAIL));
            admin.setFullName("Смирнова Анна Петровна");
            admin.setRole(Role.ROLE_ADMIN);
            admin.setEnabled(true);
            admin.setConsentSignedAt(Instant.now());
            admin = userRepository.save(admin);

            User client = new User();
            client.setEmail(CLIENT_EMAIL);
            client.setEmailHash(pii.hash(CLIENT_EMAIL));
            client.setFullName("Иванов Иван Иванович");
            client.setPhone("+79001234567");
            client.setPhoneHash(pii.hash("+79001234567"));
            client.setSnils("12345678901");
            client.setSnilsHash(pii.hash("12345678901"));
            client.setRole(Role.ROLE_USER);
            client.setEnabled(true);
            // Демо-клиент считается прошедшим онбординг, чтобы витрина была доступна
            client.setFullNameConfirmedAt(Instant.now());
            client.setConsentSignedAt(Instant.now());
            client = userRepository.save(client);

            DocumentFolder contracts = folderService.create(client, "Договоры");
            DocumentFolder acts = folderService.create(client, "Акты");
            DocumentFolder charters = folderService.create(client, "Учредительные");

            seedTemplates(templateRepository, admin.getEmail());

            seedDocument(documentService, client, contracts, "Договор поставки №148-2026.pdf",
                    "Настоящий Договор поставки заключён между сторонами и определяет "
                            + "порядок поставки товара, сроки и порядок расчётов.");
            seedDocument(documentService, client, acts, "Акт сверки за ноябрь.pdf",
                    "Акт сверки взаимных расчётов за ноябрь 2026 года.");
            seedDocument(documentService, client, charters, "Устав организации.pdf",
                    "Устав общества с ограниченной ответственностью (демонстрационный документ).");
            seedDocument(documentService, client, contracts, "Дополнительное соглашение №3.pdf",
                    "Дополнительное соглашение к договору поставки №148-2026.");

            audit.event("DEMO_SEED", "SUCCESS", "system", "USER", String.valueOf(client.getId()),
                    CLIENT_EMAIL, "Созданы демонстрационные данные");

            log.warn("Демо-данные готовы. Админ: {} · Клиент: {}", ADMIN_EMAIL, CLIENT_EMAIL);
            log.warn("Вход в демо: /api/v1/auth/dev-login?email={}", CLIENT_EMAIL);
        };
    }

    private void seedDocument(DocumentService documentService, User owner,
                              DocumentFolder folder, String name, String text) throws Exception {
        byte[] pdf = ru.afina.util.SimplePdfRenderer.render(name, text);
        Document doc = documentService.createDocument(pdf, name, "application/pdf", owner, folder);
        log.info("Демо-документ: {} ({} байт)", doc.getOriginalName(), doc.getSize());
    }

    private void seedTemplates(DocumentTemplateRepository repository, String author) {
        DocumentTemplate contract = new DocumentTemplate();
        contract.setKind(TemplateKind.DOCUMENT);
        contract.setName("Договор оказания услуг");
        contract.setDescription("Универсальный договор: исполнитель, заказчик, сумма и срок.");
        contract.setHtmlContent("""
                <h1>Договор оказания услуг</h1>
                <p><b>Исполнитель:</b> {{executor}}</p>
                <p><b>Заказчик:</b> {{customer}}</p>
                <p><b>Предмет:</b> {{subject}}</p>
                <p><b>Сумма:</b> {{amount}} руб.</p>
                <p><b>Дата:</b> {{date}}</p>
                """);
        contract.setFieldsJson("""
                [{"key":"executor","label":"Исполнитель","type":"text","required":true},
                 {"key":"customer","label":"Заказчик","type":"text","required":true},
                 {"key":"subject","label":"Предмет договора","type":"textarea","required":true},
                 {"key":"amount","label":"Сумма, руб.","type":"text","required":true},
                 {"key":"date","label":"Дата","type":"date","required":true}]
                """);
        contract.setCreatedBy(author);
        contract.setActive(true);
        repository.save(contract);

        DocumentTemplate consent = new DocumentTemplate();
        consent.setKind(TemplateKind.CONSENT);
        consent.setName("Согласие на обработку персональных данных");
        consent.setDescription("Согласие клиента на обработку ПДн (обязательный документ).");
        consent.setHtmlContent("""
                <h1>Согласие на обработку персональных данных</h1>
                <p>Я, {{fullName}}, даю согласие на обработку моих персональных данных
                в целях исполнения договора.</p>
                <p>Дата: {{date}}</p>
                """);
        consent.setFieldsJson("""
                [{"key":"fullName","label":"ФИО","type":"text","required":true},
                 {"key":"date","label":"Дата","type":"date","required":true}]
                """);
        consent.setCreatedBy(author);
        consent.setActive(true);
        repository.save(consent);
    }
}
