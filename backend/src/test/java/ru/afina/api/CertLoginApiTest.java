package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockHttpSession;
import ru.afina.entity.User;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Вход по сертификату УКЭП (сотрудники и администраторы).
 *
 * <p>Проверяется полный сценарий: одноразовый челлендж → подпись челленджа
 * ключом ГОСТ-совместимого формата (CMS/CAdES-BES) → проверка подписи,
 * сверка CN/СНИЛС с admins.env → создание сессии.</p>
 */
class CertLoginApiTest extends ApiTestBase {

    private static final String CN = "Смирнова Анна Петровна";
    private static final String SNILS = "12345678901";

    private static KeyPair keyPair;
    private static X509Certificate certificate;

    @Value("${app.admins-file}")
    private String adminsFile;

    @Value("${app.certificates-path}")
    private String certificatesPath;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=" + CN + ", O=Afina, C=RU");
        Date from = new Date(System.currentTimeMillis() - 3_600_000L);
        Date to = new Date(System.currentTimeMillis() + 86_400_000L);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.currentTimeMillis()), from, to,
                subject, keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        certificate = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
    }

    /** Публикует сертификат в доверенных и администратора в admins.env. */
    private void prepareEnvironment() throws Exception {
        Path certs = Paths.get(certificatesPath);
        Files.createDirectories(certs);
        Files.write(certs.resolve("test-admin.cer"), certificate.getEncoded());

        Path admins = Paths.get(adminsFile);
        Files.createDirectories(admins.toAbsolutePath().getParent());
        Files.writeString(admins, CN + "|" + SNILS + "|admin\n", StandardCharsets.UTF_8);
    }

    private String signChallenge(String challenge) throws Exception {
        org.bouncycastle.cms.CMSSignedDataGenerator generator =
                new org.bouncycastle.cms.CMSSignedDataGenerator();
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        generator.addSignerInfoGenerator(new org.bouncycastle.cms.jcajce
                .JcaSignerInfoGeneratorBuilder(
                new org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder()
                        .setProvider("BC").build()).build(signer, certificate));
        generator.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(
                java.util.List.of(certificate)));
        var cms = generator.generate(new org.bouncycastle.cms.CMSProcessableByteArray(
                challenge.getBytes(StandardCharsets.UTF_8)), false);
        return Base64.getEncoder().encodeToString(cms.getEncoded());
    }

    @Test
    @DisplayName("Вход по сертификату: челлендж, подпись, сессия")
    void certLoginSuccess() throws Exception {
        prepareEnvironment();

        JsonNode challengeBody = json(mockMvc.perform(get("/api/v1/auth/admin/challenge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").isNotEmpty())
                .andReturn());
        String challenge = challengeBody.get("challenge").asText();
        String signature = signChallenge(challenge);

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/v1/auth/admin/cert-login")
                        .session(session)
                        .contentType("application/json")
                        .content(body(Map.of(
                                "cn", CN,
                                "snils", SNILS,
                                "challenge", challenge,
                                "signatureBase64", signature))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.role").value("ROLE_ADMIN"));

        // Повторное использование того же челленджа невозможно
        mockMvc.perform(post("/api/v1/auth/admin/cert-login")
                        .contentType("application/json")
                        .content(body(Map.of(
                                "cn", CN,
                                "snils", SNILS,
                                "challenge", challenge,
                                "signatureBase64", signature))))
                .andExpect(status().isBadRequest());

        User admin = userRepository.findByEmailHash(pii.hash(
                ru.afina.service.UserService.buildAdminEmail(CN, SNILS))).orElseThrow();
        assertThat(admin.getRole()).isEqualTo(ru.afina.entity.Role.ROLE_ADMIN);
    }

    @Test
    @DisplayName("Вход по сертификату: неизвестный сотрудник отклоняется")
    void certLoginUnknownUser() throws Exception {
        JsonNode challengeBody = json(mockMvc.perform(get("/api/v1/auth/admin/challenge"))
                .andReturn());
        String challenge = challengeBody.get("challenge").asText();

        mockMvc.perform(post("/api/v1/auth/admin/cert-login")
                        .contentType("application/json")
                        .content(body(Map.of(
                                "cn", "Посторонний Пользователь",
                                "snils", "00000000000",
                                "challenge", challenge,
                                "signatureBase64", signChallenge(challenge)))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Вход по сертификату: подпись не от того CN отклоняется")
    void certLoginCnMismatch() throws Exception {
        prepareEnvironment();
        // Сотрудник из admins.env существует, но подпись — другим ключом/сертификатом
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair other = kpg.generateKeyPair();
        X500Name subject = new X500Name("CN=" + CN + ", O=Other, C=RU");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.currentTimeMillis()),
                new Date(System.currentTimeMillis() - 3_600_000L),
                new Date(System.currentTimeMillis() + 86_400_000L),
                subject, other.getPublic());
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(other.getPrivate());
        X509Certificate otherCert = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(cs));

        String challenge = json(mockMvc.perform(get("/api/v1/auth/admin/challenge"))
                .andReturn()).get("challenge").asText();

        var generator = new org.bouncycastle.cms.CMSSignedDataGenerator();
        generator.addSignerInfoGenerator(new org.bouncycastle.cms.jcajce
                .JcaSignerInfoGeneratorBuilder(
                new org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder()
                        .setProvider("BC").build()).build(cs, otherCert));
        generator.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(
                java.util.List.of(otherCert)));
        var cms = generator.generate(new org.bouncycastle.cms.CMSProcessableByteArray(
                challenge.getBytes(StandardCharsets.UTF_8)), false);
        String signature = Base64.getEncoder().encodeToString(cms.getEncoded());

        mockMvc.perform(post("/api/v1/auth/admin/cert-login")
                        .contentType("application/json")
                        .content(body(Map.of(
                                "cn", CN,
                                "snils", SNILS,
                                "challenge", challenge,
                                "signatureBase64", signature))))
                .andExpect(status().is4xxClientError());
    }
}
