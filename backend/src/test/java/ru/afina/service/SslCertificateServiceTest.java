package ru.afina.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Загрузка SSL-сертификата: коммерческие сертификаты приходят с ключом PKCS#1
 * ({@code BEGIN RSA PRIVATE KEY}) и цепочкой «лист + промежуточные».
 */
@DisplayName("SSL: валидация и применение сертификата")
class SslCertificateServiceTest {

    @TempDir Path tempDir;

    private SslCertificateService service;
    private Path certsDir;
    private Path confPath;

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static X509Certificate genCert(KeyPair kp, String cn) throws Exception {
        long now = System.currentTimeMillis();
        X500Name name = new X500Name("CN=" + cn);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), new Date(now - 1000),
                new Date(now + 86400000L * 365), name, kp.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    /** PKCS#8 (BEGIN PRIVATE KEY). */
    private static String pkcs8(PrivateKey key) {
        String b64 = Base64.getEncoder().encodeToString(key.getEncoded());
        StringBuilder sb = new StringBuilder("-----BEGIN PRIVATE KEY-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append("\n");
        }
        return sb.append("-----END PRIVATE KEY-----\n").toString();
    }

    /** PKCS#1 (BEGIN RSA PRIVATE KEY) — как отдают коммерческие УЦ / openssl genrsa. */
    private static String pkcs1(PrivateKey key) throws Exception {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(key);
        }
        return sw.toString();
    }

    private static String pem(X509Certificate c) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(c.getEncoded());
        StringBuilder sb = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append("\n");
        }
        return sb.append("-----END CERTIFICATE-----\n").toString();
    }

    @BeforeEach
    void setUp() throws Exception {
        certsDir = tempDir.resolve("angie-certs");
        Files.createDirectories(certsDir);
        confPath = tempDir.resolve("angie.conf");
        Files.writeString(confPath, """
                acme_client letsencrypt https://acme-v02.api.letsencrypt.org/directory;
                server {
                    listen 443 ssl;
                    include /etc/angie/certs/tls.conf;
                }
                """);
        service = new SslCertificateService();
        ReflectionTestUtils.setField(service, "angieCertsPath", certsDir.toString());
        ReflectionTestUtils.setField(service, "angieConfPath", confPath.toString());
    }

    @Test
    @DisplayName("PKCS#8 ключ принимается")
    void validate_validPair_ok() throws Exception {
        KeyPair kp = genKeyPair();
        var r = service.validate(pem(genCert(kp, "afinasystems.ru")), pkcs8(kp.getPrivate()));

        assertThat(r.valid()).as(r.error()).isTrue();
        assertThat(r.chainCount()).isEqualTo(1);
        assertThat(r.subject()).contains("afinasystems.ru");
    }

    @Test
    @DisplayName("PKCS#1 (BEGIN RSA PRIVATE KEY) принимается — как у коммерческих УЦ")
    void validate_pkcs1_ok() throws Exception {
        KeyPair kp = genKeyPair();
        var r = service.validate(pem(genCert(kp, "shop.example.ru")), pkcs1(kp.getPrivate()));

        assertThat(r.valid()).as(r.error()).isTrue();
    }

    @Test
    @DisplayName("цепочка «лист + промежуточный» → chainCount=2")
    void validate_chainCount() throws Exception {
        KeyPair kp = genKeyPair();
        KeyPair intermediateKp = genKeyPair();
        String chain = pem(genCert(kp, "afinasystems.ru"))
                + pem(genCert(intermediateKp, "Test Intermediate CA"));

        var r = service.validate(chain, pkcs8(kp.getPrivate()));

        assertThat(r.valid()).as(r.error()).isTrue();
        assertThat(r.chainCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("ключ от другого сертификата → отказ")
    void validate_wrongKey_fail() throws Exception {
        KeyPair kp1 = genKeyPair();
        KeyPair kp2 = genKeyPair();

        var r = service.validate(pem(genCert(kp1, "afinasystems.ru")), pkcs8(kp2.getPrivate()));

        assertThat(r.valid()).isFalse();
        assertThat(r.error()).contains("не соответствует");
    }

    @Test
    @DisplayName("мусор вместо сертификата → отказ без исключения")
    void validate_garbageCert_fail() {
        var r = service.validate("NOT A PEM", "NOT A PEM");

        assertThat(r.valid()).isFalse();
    }

    @Test
    @DisplayName("зашифрованный ключ → понятная ошибка")
    void validate_encryptedKey() throws Exception {
        KeyPair kp = genKeyPair();
        String enc = "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n";

        var r = service.validate(pem(genCert(kp, "afinasystems.ru")), enc);

        assertThat(r.valid()).isFalse();
        assertThat(r.error()).contains("паролем");
    }

    @Test
    @DisplayName("save(): файлы записаны, angie.conf переключён, старый серт в .bak")
    void saveAndDelete_filesCreatedAndRemoved() throws Exception {
        Files.writeString(certsDir.resolve("fullchain.pem"), "OLD-SELF-SIGNED");
        Files.writeString(certsDir.resolve("privkey.pem"), "OLD-KEY");
        KeyPair kp = genKeyPair();

        service.save(pem(genCert(kp, "afinasystems.ru")), pkcs8(kp.getPrivate()));

        assertThat(Files.isRegularFile(certsDir.resolve("fullchain.pem"))).isTrue();
        assertThat(Files.readString(certsDir.resolve("privkey.pem"))).contains("PRIVATE KEY");
        assertThat(Files.readString(certsDir.resolve("fullchain.pem.bak"))).isEqualTo("OLD-SELF-SIGNED");
        assertThat(service.hasCertificate()).isTrue();
        assertThat(service.isAcmeActive()).isFalse();
        assertThat(service.isAcmeAvailable()).isTrue();

        // angie.conf не трогаем — управляем через включаемый tls.conf
        String conf = Files.readString(confPath);
        assertThat(conf).contains("include /etc/angie/certs/tls.conf;")
                .doesNotContain("$acme_cert_letsencrypt");
        String tls = Files.readString(certsDir.resolve("tls.conf"));
        assertThat(tls).contains("/etc/angie/certs/fullchain.pem")
                .contains("/etc/angie/certs/privkey.pem")
                .doesNotContain("acme letsencrypt;");

        service.delete();
        assertThat(Files.exists(certsDir.resolve("fullchain.pem"))).isFalse();
        assertThat(Files.exists(certsDir.resolve("privkey.pem"))).isFalse();
        assertThat(Files.readString(certsDir.resolve("tls.conf")))
                .contains("acme letsencrypt;")
                .contains("$acme_cert_letsencrypt");
        assertThat(service.isAcmeActive()).isTrue();
    }

    @Test
    @DisplayName("save() ставит лист первым, даже если цепочка была в обратном порядке")
    void saveNormalizesChainOrder() throws Exception {
        KeyPair leafKp = genKeyPair();
        KeyPair otherKp = genKeyPair();
        X509Certificate leaf = genCert(leafKp, "afinasystems.ru");
        X509Certificate other = genCert(otherKp, "Other Cert");

        service.save(pem(other) + pem(leaf), pkcs8(leafKp.getPrivate()));

        String saved = Files.readString(certsDir.resolve("fullchain.pem"));
        int leafPos = saved.indexOf(Base64.getEncoder().encodeToString(leaf.getEncoded()).substring(0, 40));
        int otherPos = saved.indexOf(Base64.getEncoder().encodeToString(other.getEncoded()).substring(0, 40));
        assertThat(leafPos).isNotNegative().isLessThan(otherPos);
    }

    @Test
    @DisplayName("delete(): без ACME и без .bak не удаляет файлы (иначе Angie без TLS)")
    void deleteWithoutAcmeAndBackupRefuses() throws Exception {
        Files.writeString(confPath, "server { include /etc/angie/certs/tls.conf; }\n");
        Files.writeString(certsDir.resolve("fullchain.pem"), "CUSTOM");
        Files.writeString(certsDir.resolve("privkey.pem"), "CUSTOM-KEY");

        assertThatThrownBy(() -> service.delete())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACME");
        assertThat(Files.exists(certsDir.resolve("fullchain.pem"))).isTrue();
    }

    @Test
    @DisplayName("delete(): без ACME, но с .bak — восстанавливает self-signed")
    void deleteWithoutAcmeRestoresBackup() throws Exception {
        Files.writeString(confPath, "server { include /etc/angie/certs/tls.conf; }\n");
        Files.writeString(certsDir.resolve("fullchain.pem"), "CUSTOM");
        Files.writeString(certsDir.resolve("privkey.pem"), "CUSTOM-KEY");
        Files.writeString(certsDir.resolve("fullchain.pem.bak"), "SELF-SIGNED");
        Files.writeString(certsDir.resolve("privkey.pem.bak"), "SELF-KEY");

        service.delete();

        assertThat(Files.readString(certsDir.resolve("fullchain.pem"))).isEqualTo("SELF-SIGNED");
        assertThat(Files.readString(certsDir.resolve("privkey.pem"))).isEqualTo("SELF-KEY");
    }

    @Test
    @DisplayName("rollback(): возвращает .bak-сертификат, если новый не поднялся")
    void rollbackRestoresBackup() throws Exception {
        Files.writeString(certsDir.resolve("fullchain.pem"), "BROKEN");
        Files.writeString(certsDir.resolve("privkey.pem"), "BROKEN");
        Files.writeString(certsDir.resolve("fullchain.pem.bak"), "SELF-SIGNED");
        Files.writeString(certsDir.resolve("privkey.pem.bak"), "SELF-KEY");

        assertThat(service.rollback()).isTrue();
        assertThat(Files.readString(certsDir.resolve("fullchain.pem"))).isEqualTo("SELF-SIGNED");
        assertThat(Files.readString(certsDir.resolve("privkey.pem"))).isEqualTo("SELF-KEY");
    }

    @Test
    @DisplayName("rollback(): без .bak, но с ACME — возвращает ACME")
    void rollbackToAcme() throws Exception {
        Files.writeString(certsDir.resolve("fullchain.pem"), "BROKEN");
        Files.writeString(certsDir.resolve("privkey.pem"), "BROKEN");

        assertThat(service.rollback()).isTrue();
        assertThat(Files.readString(certsDir.resolve("tls.conf"))).contains("acme letsencrypt;");
        assertThat(Files.exists(certsDir.resolve("fullchain.pem"))).isFalse();
    }
}
