package ru.afina.api;

import org.bouncycastle.asn1.x500.X500Name;
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
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import ru.afina.entity.Document;
import ru.afina.entity.User;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сквозной сценарий подписания через REST API.
 *
 * <p>Подпись формируется BouncyCastle (CMS/CAdES-BES, RSA) — тот же формат
 * контейнера, что отдаёт плагин КриптоПро. Проверяется весь путь:
 * загрузка .sig → криптопроверка → сохранение → повторное подписание
 * запрещено → скачивание подписи и ZIP «документ + подпись».</p>
 */
class DocumentSigningApiTest extends ApiTestBase {

    private static final byte[] CONTENT =
            "Договор поставки №148-2026".getBytes(StandardCharsets.UTF_8);

    private static KeyPair keyPair;
    private static X509Certificate certificate;

    @BeforeAll
    static void generateCertificate() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=Иванов Иван Иванович, O=Afina, C=RU");
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

    /** Отсоединённая CMS-подпись (CAdES-BES-совместимый контейнер). */
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

    private Document createSignedCandidate(User owner) throws Exception {
        return documentService.createDocument(CONTENT, "Договор.txt",
                "text/plain", owner, folderService.ensureDefault(owner));
    }

    @Test
    @DisplayName("Загрузка .sig: подпись проверена и сохранена")
    void uploadSignature() throws Exception {
        User client = createClient();
        Document doc = createSignedCandidate(client);
        byte[] sig = Base64.getDecoder().decode(detachedSignature(CONTENT));
        MockMultipartFile file = new MockMultipartFile(
                "file", "Договор.txt.sig", "application/octet-stream", sig);

        mockMvc.perform(as(client, multipart("/api/v1/documents/" + doc.getId() + "/signature")
                        .file(file)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.signerSubject").isNotEmpty());

        Document fresh = documentRepository.findByIdWithSignatures(doc.getId()).orElseThrow();
        assertThat(fresh.isSigned()).isTrue();
        assertThat(fresh.getSignatures()).hasSize(1);
        assertThat(fresh.getSignatures().get(0).getSignerSubject()).contains("Иванов");
    }

    @Test
    @DisplayName("Скачивание сохранённой подписи и ZIP с документом")
    void downloadSignature() throws Exception {
        User client = createClient();
        Document doc = createSignedCandidate(client);
        byte[] sig = Base64.getDecoder().decode(detachedSignature(CONTENT));
        mockMvc.perform(as(client, multipart("/api/v1/documents/" + doc.getId() + "/signature")
                        .file(new MockMultipartFile("file", "sig.sig",
                                "application/octet-stream", sig))))
                .andExpect(status().isOk());

        byte[] downloaded = mockMvc.perform(asGet(client,
                        get("/api/v1/documents/" + doc.getId() + "/signature")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(sig);

        byte[] zip = mockMvc.perform(asGet(client,
                        get("/api/v1/documents/" + doc.getId() + "/download-signed")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(zip).isNotEmpty();
        // ZIP начинается с сигнатуры PK
        assertThat(zip[0]).isEqualTo((byte) 'P');
        assertThat(zip[1]).isEqualTo((byte) 'K');
    }

    @Test
    @DisplayName("Подпись не от этого документа отклоняется")
    void signatureFromAnotherDocumentRejected() throws Exception {
        User client = createClient();
        Document doc = createSignedCandidate(client);
        String foreign = detachedSignature("другой документ".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = new MockMultipartFile("file", "sig.sig",
                "application/octet-stream", Base64.getDecoder().decode(foreign));

        mockMvc.perform(as(client, multipart("/api/v1/documents/" + doc.getId() + "/signature")
                        .file(file)))
                .andExpect(status().isBadRequest());

        Document fresh = documentRepository.findByIdWithSignatures(doc.getId()).orElseThrow();
        assertThat(fresh.isSigned()).isFalse();
    }

    @Test
    @DisplayName("Повторное подписание тем же пользователем запрещено (409)")
    void doubleSigningRejected() throws Exception {
        User client = createClient();
        Document doc = createSignedCandidate(client);
        byte[] sig = Base64.getDecoder().decode(detachedSignature(CONTENT));
        MockMultipartFile file = new MockMultipartFile("file", "sig.sig",
                "application/octet-stream", sig);

        mockMvc.perform(as(client, multipart("/api/v1/documents/" + doc.getId() + "/signature")
                        .file(file)))
                .andExpect(status().isOk());

        mockMvc.perform(as(client, post("/api/v1/documents/" + doc.getId() + "/sign"))
                        .contentType("application/json")
                        .content(body(java.util.Map.of("documentId", doc.getId(),
                                "signatureBase64", detachedSignature(CONTENT)))))
                .andExpect(status().isConflict());
    }
}
