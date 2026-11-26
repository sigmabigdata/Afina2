package ru.afina.demo;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.DocumentTemplate;
import ru.afina.entity.PowerOfAttorney;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.repository.DocumentTemplateRepository;
import ru.afina.repository.PowerOfAttorneyRepository;
import ru.afina.security.PiiEncryptor;
import ru.afina.service.DocumentService;
import ru.afina.service.FolderService;
import ru.afina.service.PowerOfAttorneyXmlParser;
import ru.afina.service.SettingsService;
import ru.afina.service.UserService;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Наполнение демо-базы (H2, только профиль demo): клиенты, разделы, документы,
 * настоящая CAdES-подпись с демо-сертификатом, МЧД и настройки.
 *
 * <p>Данные одноразовые: H2 in-memory, при рестарте контейнера создаются заново.
 */
@Component
@Profile("demo")
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private static final String DEMO_ADMIN_EMAIL = "demo-admin@afina.local";
    private static final String DEMO_CLIENT_EMAIL = "demo-client@afina.local";
    private static final String DEMO_SIGNER_CN = "Демо Подписант";
    private static final String DEMO_ORG = "ПАО «Афина» (демо)";
    /** Валидный тестовый СНИЛС (проходит проверку контрольного числа) для Госключа. */
    private static final String DEMO_CLIENT_SNILS = "11223344595";
    private static final String FLAG_FALSE = "false";

    private final UserService userService;
    private final FolderService folderService;
    private final DocumentService documentService;
    private final SettingsService settings;
    private final PiiEncryptor pii;
    private final PowerOfAttorneyRepository poaRepository;
    private final DocumentTemplateRepository templateRepository;

    @Value("${app.certificates-path:/tmp/afina-demo/certs}")
    private String certsPath;

    @Value("${app.file-encryption-key-path:/tmp/afina-demo/keys/file.key}")
    private String fileKeyPath;

    @Value("${app.pii-key-path:/tmp/afina-demo/keys/pii.key}")
    private String piiKeyPath;

    @Value("${app.admins-file:/tmp/afina-demo/admins.env}")
    private String adminsFile;

    public DemoDataInitializer(UserService userService,
                               FolderService folderService,
                               DocumentService documentService,
                               SettingsService settings,
                               PiiEncryptor pii,
                               PowerOfAttorneyRepository poaRepository,
                               DocumentTemplateRepository templateRepository) {
        this.userService = userService;
        this.folderService = folderService;
        this.documentService = documentService;
        this.settings = settings;
        this.pii = pii;
        this.poaRepository = poaRepository;
        this.templateRepository = templateRepository;
    }

    /**
     * Наполнение БД текущего тенанта (шаблонная БД остаётся пустой — она нужна
     * только как источник DDL для персональных баз демо-сессий).
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            seedInfraFiles();
        } catch (Exception e) {
            log.error("DEMO: не удалось создать служебные файлы", e);
        }
    }

    /** Демо-данные для текущего тенанта (вызывается при первом входе в сессию). */
    @SuppressWarnings("java:S112") // внутренний хелпер: спектр исключений широк
    public void seedDemoData() throws Exception {
        User admin = ensureUser(DEMO_ADMIN_EMAIL, "Демо Администратор", Role.ROLE_ADMIN);
        User client = ensureUser(DEMO_CLIENT_EMAIL, "Демо Клиент", Role.ROLE_USER,
                DEMO_CLIENT_SNILS);

        // Настройки безопасного демо
        settings.set("admin.ip_whitelist", "*", "demo");
        settings.set("monitor.enabled", FLAG_FALSE, "demo");
        settings.set("alert.enabled", FLAG_FALSE, "demo");
        settings.set("revocation.mode", "soft", "demo");
        settings.set("signature.require_crl", FLAG_FALSE, "demo");
        settings.set("monitor.mail_to", "demo-alerts@afina.local", "demo");
        settings.set("auth.rate_limit.login", "50", "demo");
        settings.set("auth.rate_limit.window_min", "15", "demo");
        settings.set("email.login.subject", "Афина · Ссылка для входа (демо)", "demo");

        DocumentFolder folder = folderService.create(client, "Договоры (демо)");
        DocumentFolder acts = folderService.create(client, "Акты (демо)");

        // ── Документ 1: подписанный (с демо-сертификатом и МЧД) ──
        byte[] pdf1 = demoPdf("Договор поставки № DEMO-1",
                "Настоящий документ создан в демо-версии сервиса Афина.",
                "Подпись: демонстрационная, ключи одноразовые.");
        Document signed = documentService.createDocument(pdf1, "Договор_DEMO-1.pdf",
                "application/pdf", client, folder);

        DemoSigner signer = createDemoSigner();
        String sigBase64 = sign(pdf1, signer);
        documentService.addSignature(signed.getId(), client, sigBase64,
                signer.cert.getSubjectX500Principal().getName(),
                signer.cert.getSerialNumber().toString(16));

        seedPoa(signer.cert);

        // ── Документ 2: без подписи ──
        byte[] pdf2 = demoPdf("Акт сверки № DEMO-2",
                "Документ без подписи — попробуйте подписать или приложить .sig.",
                "Демо-версия сервиса Афина.");
        documentService.createDocument(pdf2, "Акт_DEMO-2.pdf",
                "application/pdf", client, acts);

        // ── Шаблон согласия на обработку ПД (клиентам не виден) ──
        if (templateRepository.findFirstByActiveTrueAndKindOrderBySortOrderAscNameAsc(
                ru.afina.entity.TemplateKind.CONSENT).isEmpty()) {
            DocumentTemplate consent = new DocumentTemplate();
            consent.setKind(ru.afina.entity.TemplateKind.CONSENT);
            consent.setName("Согласие на обработку персональных данных (демо)");
            consent.setDescription("Формируется автоматически при первом входе клиента");
            consent.setHtmlContent("""
                    <h1>Согласие на обработку персональных данных</h1>
                    <p>Я, {{fio}}, даю согласие на обработку моих персональных данных.</p>
                    <p>Дата: {{date}}</p>
                    <p>Паспорт: {{passport}}, выдан {{passport_issued_by}} {{passport_issue_date}}</p>
                    <p>Адрес регистрации: {{address}}</p>
                    <p>СНИЛС: {{snils}} · ИНН: {{inn}} · Телефон: {{phone}} · Email: {{email}}</p>
                    """);
            consent.setCreatedBy(DEMO_ADMIN_EMAIL);
            consent.setActive(true);
            templateRepository.save(consent);
        }

        // ── Шаблон документа ──
        if (templateRepository.findAll().isEmpty()) {
            DocumentTemplate t = new DocumentTemplate();
            t.setName("Договор (демо-шаблон)");
            t.setDescription("Простой шаблон для проверки генерации документов");
            t.setHtmlContent("<h1>Договор № ${number}</h1>"
                    + "<p>Стороны: ${org}</p><p>Дата: ${date}</p>");
            t.setCreatedBy(DEMO_ADMIN_EMAIL);
            t.setActive(true);
            templateRepository.save(t);
        }

        log.info("DEMO: admin={}, client={}, документы и подпись готовы",
                admin.getEmail(), DEMO_CLIENT_EMAIL);
    }

    /**
     * Демо-файлы, которые проверяет мониторинг: legacy-ключи и admins.env.
     * Это НЕ секреты: H2 и хранилище всё равно одноразовые.
     */
    @SuppressWarnings("java:S112") // внутренний хелпер: спектр исключений широк
    private void seedInfraFiles() throws Exception {
        Path keysDir = Paths.get(piiKeyPath).getParent();
        if (keysDir != null) {
            Files.createDirectories(keysDir);
            copyIfMissing(keysDir.resolve("pii.key.v1"), Paths.get(piiKeyPath));
            copyIfMissing(keysDir.resolve("file.key.v1"), Paths.get(fileKeyPath));
        }
        Path admins = Paths.get(adminsFile);
        if (admins.getParent() != null) {
            Files.createDirectories(admins.getParent());
        }
        if (!Files.exists(admins)) {
            Files.writeString(admins, "# демо-администратор (одноразовый)\n"
                    + "Демо Администратор|00000000000\n", StandardCharsets.UTF_8);
        }
        seedPublicRoots();
    }

    /**
     * Копирует публичные корневые сертификаты из образа в truststore демо.
     * Без них реальная УКЭП (ГОСТ) не проходит проверку цепочки:
     * в демо иначе только самоподписанный демо-корень.
     */
    @SuppressWarnings("java:S112") // внутренний хелпер: спектр исключений широк
    private void seedPublicRoots() {
        Path source = Paths.get("/app/certs");
        if (!Files.isDirectory(source)) return;
        Path target = Paths.get(certsPath);
        try {
            Files.createDirectories(target);
            try (var ds = Files.newDirectoryStream(source)) {
                for (Path p : ds) {
                    String name = p.getFileName().toString().toLowerCase();
                    if (!(name.endsWith(".cer") || name.endsWith(".crt")
                            || name.endsWith(".pem"))) {
                        continue;
                    }
                    copyIfMissing(p, target.resolve(p.getFileName()));
                }
            }
            log.info("DEMO: публичные корневые сертификаты скопированы в {}", target);
        } catch (Exception e) {
            log.warn("DEMO: не удалось скопировать корневые сертификаты: {}", e.getMessage());
        }
    }

    @SuppressWarnings("java:S112") // спектр исключений широк намеренно
    private static void copyIfMissing(Path from, Path to) throws Exception {
        if (!Files.isReadable(from) || Files.exists(to)) return;
        Files.copy(from, to);
    }

    private User ensureUser(String email, String fullName, Role role) {
        return ensureUser(email, fullName, role, null);
    }

    /** Демо-пользователь; при необходимости дозаполняет СНИЛС (для Госключа). */
    private User ensureUser(String email, String fullName, Role role, String snils) {
        var existing = userService.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (snils != null && user.getSnils() == null) {
                return userService.adminUpdate(user.getId(), user.getEmail(),
                        user.getFullName(), null, snils, user.getRole(), user.isEnabled());
            }
            return user;
        }
        return userService.adminCreate(email, fullName, null, snils, role, true);
    }

    // ─── демо-сертификат и подпись ───────────────────────────────

    private record DemoSigner(PrivateKey key, X509Certificate cert) {
    }

    @SuppressWarnings("java:S112") // внутренний хелпер: спектр исключений широк

    private DemoSigner createDemoSigner() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        X500Name subject = new X500Name("CN=" + DEMO_SIGNER_CN + ", O=" + DEMO_ORG + ", C=RU");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(now),
                new Date(now - 86_400_000L), new Date(now + 365L * 86_400_000L),
                subject, kp.getPublic());
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(cs));

        // складываем в truststore демо, чтобы цепочка проверялась «по-настоящему»
        Path dir = Paths.get(certsPath);
        Files.createDirectories(dir);
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        Files.writeString(dir.resolve("demo-signer-root.pem"), pem, StandardCharsets.UTF_8);

        return new DemoSigner(kp.getPrivate(), cert);
    }

    @SuppressWarnings("java:S112") // внутренний хелпер: спектр исключений широк

    private String sign(byte[] content, DemoSigner signer) throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(signer.key());
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                .build(cs, signer.cert()));
        gen.addCertificates(new JcaCertStore(List.of(signer.cert())));
        CMSSignedData cms = gen.generate(new CMSProcessableByteArray(content), false);
        return Base64.getEncoder().encodeToString(cms.getEncoded());
    }

    private void seedPoa(X509Certificate cert) {
        String fio = DEMO_SIGNER_CN;
        String normalized = PowerOfAttorneyXmlParser.normalizeFio(fio);
        if (poaRepository
                .findFirstByFioHashAndActiveTrueAndValidUntilGreaterThanEqualOrderByValidUntilDesc(
                        pii.hash(normalized), LocalDate.now())
                .isPresent()) {
            return;
        }
        PowerOfAttorney poa = new PowerOfAttorney();
        poa.setFio(fio);
        poa.setFioHash(pii.hash(normalized));
        poa.setSnils("00000000000");
        poa.setInn("000000000000");
        poa.setOrgName(DEMO_ORG);
        poa.setOrgInn("0000000000");
        poa.setDocNumber("DEMO-МЧД-1");
        poa.setDocGuid(java.util.UUID.randomUUID().toString().toUpperCase());
        poa.setStampNumber("DEMO-МЧД-1");
        poa.setDocDate(LocalDate.now().minusMonths(1));
        poa.setValidFrom(LocalDate.now().minusMonths(1));
        poa.setValidUntil(LocalDate.now().plusYears(3));
        poa.setNotaryName("Демо-нотариус");
        poa.setActive(true);
        poaRepository.save(poa);
        if (log.isInfoEnabled()) {
            log.info("DEMO: МЧД для «{}» добавлена (serial={})", fio,
                    cert.getSerialNumber().toString(16));
        }
    }

    // ─── простой PDF ─────────────────────────────────────────────

    @SuppressWarnings("java:S112") // спектр исключений широк намеренно
    private static byte[] demoPdf(String title, String... lines) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 18);
                cs.newLineAtOffset(60, 770);
                cs.showText(ascii(title));
                cs.endText();

                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(60, 730);
                for (String line : lines) {
                    cs.showText(ascii(line));
                    cs.newLineAtOffset(0, -18);
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Helvetica не умеет кириллицу — заменяем для демо-текста. */
    private static String ascii(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(c < 128 ? c : '?');
        }
        return sb.toString();
    }
}
