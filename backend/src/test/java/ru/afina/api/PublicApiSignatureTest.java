package ru.afina.api;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentSignature;
import ru.afina.entity.PowerOfAttorney;
import ru.afina.entity.User;
import ru.afina.repository.DocumentSignatureRepository;
import ru.afina.repository.PowerOfAttorneyRepository;
import ru.afina.service.PowerOfAttorneyService;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Публичная карточка документа с реальной CMS-подписью (публичный API).
 *
 * <p>Подпись формируется BouncyCastle и сохраняется через
 * {@link ru.afina.service.DocumentService#addSignature}, поэтому проверяется
 * вся цепочка: контроллер → криптопроверка → атрибуты подписи в JSON.</p>
 */
@DisplayName("PublicApi: подписи, МЧД и ошибки проверки")
class PublicApiSignatureTest extends ApiTestBase {

    private static final String SIGNER_SUBJECT = "CN=Иванов Иван Иванович, O=Афина, C=RU";

    private static KeyPair keyPair;
    private static X509Certificate certificate;

    @Autowired
    private DocumentSignatureRepository signatureRepository;
    @Autowired
    private PowerOfAttorneyService poaService;
    @Autowired
    private PowerOfAttorneyRepository poaRepository;

    private final List<Long> createdPoaIds = new ArrayList<>();

    @BeforeAll
    static void generateCertificate() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();

        X500Name subject = new X500Name(SIGNER_SUBJECT);
        Date notBefore = new Date(System.currentTimeMillis() - 3_600_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 86_400_000L);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.currentTimeMillis()),
                notBefore, notAfter, subject, keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        certificate = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
    }

    @AfterEach
    void cleanup() {
        for (Long id : createdPoaIds) {
            poaRepository.deleteById(id);
        }
        createdPoaIds.clear();
    }

    // ──────────────────────────── помощники ────────────────────────────

    /** Detached CMS-подпись с встроенным сертификатом (CAdES-BES-совместимая). */
    private static String detachedSignature(byte[] content) throws Exception {
        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                .build(signer, certificate));
        generator.addCertificates(new JcaCertStore(List.of(certificate)));
        CMSSignedData cms = generator.generate(new CMSProcessableByteArray(content), false);
        return Base64.getEncoder().encodeToString(cms.getEncoded());
    }

    /** XML МЧД для представителя «Иванов Иван Иванович». */
    private static String poaXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Доверенность xmlns="urn://x-artefacts/EMCHD_1">
                  <СвДов ВнНомДовер="777" НомДовер="%s"
                         ДатаВыдДовер="2025-01-15" СрокДейст="2030-12-31"/>
                  <СвУпПред>
                    <ФИО Фамилия="Иванов" Имя="Иван" Отчество="Иванович"/>
                    <СведФизЛ СНИЛС="12345678901" ИННФЛ="500100732259"/>
                  </СвУпПред>
                  <СвРосОрг НаимОрг="ООО Афина" ИННЮЛ="7701234567"/>
                </Доверенность>
                """.formatted(UUID.randomUUID());
    }

    private PowerOfAttorney uploadPoa() throws Exception {
        PowerOfAttorney poa = poaService.upload(
                poaXml().getBytes(StandardCharsets.UTF_8), "admin@test.local");
        createdPoaIds.add(poa.getId());
        return poa;
    }

    // ──────────────────────────── сценарии ────────────────────────────

    @Test
    @DisplayName("Документ с корректной подписью → cryptoOk=true, цепочка помечена как непроверенная")
    void signedDocumentInfo() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Подписанный договор.pdf");
        byte[] content = documentService.getBytes(doc);
        documentService.addSignature(doc.getId(), null, detachedSignature(content),
                SIGNER_SUBJECT, "1a2b");

        mockMvc.perform(get("/api/v1/public/info/" + doc.getPublicUuid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signatureCount").value(1))
                .andExpect(jsonPath("$.signed").value(true))
                .andExpect(jsonPath("$.signatures[0].cryptoOk").value(true))
                // null-поля в JSON не сериализуются
                .andExpect(jsonPath("$.signatures[0].cryptoMessage").doesNotExist())
                .andExpect(jsonPath("$.signatures[0].subject").value(SIGNER_SUBJECT))
                .andExpect(jsonPath("$.signatures[0].serial").value("1a2b"))
                .andExpect(jsonPath("$.signatures[0].certValidNow").value(true))
                .andExpect(jsonPath("$.signatures[0].applyPoa").value(false))
                // самоподписанный сертификат не в truststore → цепочка не подтверждена
                .andExpect(jsonPath("$.signatures[0].chainOk").value(false))
                .andExpect(jsonPath("$.signatures[0].chainMessage")
                        .value(containsString("Цепочка/отзыв не проверены")));
    }

    @Test
    @DisplayName("Подпись от другого файла → cryptoOk=false с понятным сообщением")
    void signatureFromOtherDocument() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Чужой договор.pdf");
        String foreignSignature = detachedSignature("Совсем другой текст".getBytes(StandardCharsets.UTF_8));
        documentService.addSignature(doc.getId(), null, foreignSignature, SIGNER_SUBJECT, "2b3c");

        mockMvc.perform(get("/api/v1/public/info/" + doc.getPublicUuid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signatures[0].cryptoOk").value(false))
                .andExpect(jsonPath("$.signatures[0].cryptoMessage")
                        .value("Подпись не соответствует документу"))
                .andExpect(jsonPath("$.signatures[0].chainOk").value(false))
                .andExpect(jsonPath("$.signatures[0].chainMessage")
                        .value("Криптоподпись недействительна"));
    }

    @Test
    @DisplayName("Битая подпись (не CMS) → cryptoOk=false с технической причиной")
    void brokenSignature() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Битый sig.pdf");
        String notCms = Base64.getEncoder()
                .encodeToString("это не CMS".getBytes(StandardCharsets.UTF_8));
        documentService.addSignature(doc.getId(), null, notCms, SIGNER_SUBJECT, "3c4d");

        mockMvc.perform(get("/api/v1/public/info/" + doc.getPublicUuid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signatures[0].cryptoOk").value(false))
                .andExpect(jsonPath("$.signatures[0].cryptoMessage")
                        .value(containsString("Не удалось проверить")));
    }

    @Test
    @DisplayName("applyPoa=true → в ответе реквизиты действующей МЧД")
    void signatureWithPoa() throws Exception {
        PowerOfAttorney poa = uploadPoa();

        User client = createClient();
        Document doc = createDocument(client, "Договор с МЧД.pdf");
        byte[] content = documentService.getBytes(doc);
        DocumentSignature saved = documentService.addSignature(doc.getId(), null,
                detachedSignature(content), SIGNER_SUBJECT, "4d5e");
        saved.setApplyPoa(true);
        signatureRepository.save(saved);

        assertThat(poa.getId()).isNotNull();
        mockMvc.perform(get("/api/v1/public/info/" + doc.getPublicUuid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signatures[0].applyPoa").value(true))
                .andExpect(jsonPath("$.signatures[0].poaFound").value(true))
                .andExpect(jsonPath("$.signatures[0].poaStampNumber").isNotEmpty())
                .andExpect(jsonPath("$.signatures[0].poaEffective").value(true))
                .andExpect(jsonPath("$.signatures[0].poaValidUntil").value("2030-12-31"));
    }

    @Test
    @DisplayName("applyPoa=true без подходящей МЧД → poaFound=false")
    void signatureWithPoaNotFound() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Без МЧД.pdf");
        byte[] content = documentService.getBytes(doc);
        DocumentSignature saved = documentService.addSignature(doc.getId(), null,
                detachedSignature(content), "CN=Петров Пётр Петрович, O=Афина", "5e6f");
        saved.setApplyPoa(true);
        signatureRepository.save(saved);

        mockMvc.perform(get("/api/v1/public/info/" + doc.getPublicUuid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signatures[0].applyPoa").value(true))
                .andExpect(jsonPath("$.signatures[0].poaFound").value(false))
                .andExpect(jsonPath("$.signatures[0].poaStampNumber").doesNotExist());
    }
}
