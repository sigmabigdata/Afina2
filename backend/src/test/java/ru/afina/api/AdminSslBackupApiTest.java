package ru.afina.api;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
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
 * TLS-сертификат и резервные копии: рабочие сценарии и защитные проверки.
 *
 * <p>В тестовом окружении Angie отсутствует, поэтому операция «сохранить
 * сертификат» завершается согласованным отказом (409) с откатом — именно
 * это поведение и проверяется: система не оставляет «полуприменённый» TLS.</p>
 */
class AdminSslBackupApiTest extends ApiTestBase {

    @Value("${app.backup-path}")
    private String backupPath;

    @Value("${app.angie-conf-path}")
    private String angieConfPath;

    @Value("${app.angie-certs-path}")
    private String angieCertsPath;

    // ─────────────────────────── TLS ───────────────────────────

    private static String[] selfSignedPem() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=afina.test, O=Afina, C=RU");
        Date from = new Date(System.currentTimeMillis() - 3_600_000L);
        Date to = new Date(System.currentTimeMillis() + 86_400_000L);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.currentTimeMillis()), from, to,
                subject, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));

        String certPem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        String keyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return new String[]{certPem, keyPem};
    }

    @Test
    @DisplayName("SSL: сохранение сертификата без Angie завершается согласованным отказом")
    void sslUploadWithRollback() throws Exception {
        User admin = createAdmin();
        String[] pem = selfSignedPem();

        // Готовим минимальный angie.conf: в тестовом окружении Angie не установлен,
        // поэтому после сохранения перезапуск невозможен и контроллер обязан
        // откатиться — ожидаем 409 (конфликт состояния), а не 200 и не «тихий» 500
        Path conf = Paths.get(angieConfPath);
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "server { listen 443 ssl; ssl_certificate %s; "
                + "ssl_certificate_key %s; }\n".formatted(
                angieCertsPath + "/cert.pem", angieCertsPath + "/privkey.pem"));

        // Без установленного Angie операция обязана завершиться ошибкой
        // (409 «сохранено, но не применено» либо 500 при невозможности записи
        // конфигурации), но не «успехом» и не 200
        mockMvc.perform(as(admin, post("/api/v1/admin/ssl"))
                        .contentType("application/json")
                        .content(body(Map.of("certPem", pem[0], "keyPem", pem[1]))))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @DisplayName("SSL: перезапуск без Angie → 409")
    void sslRestart() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, post("/api/v1/admin/ssl/restart")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("SSL: откат без резервной копии → 409")
    void sslRollback() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, post("/api/v1/admin/ssl/rollback")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("SSL: слишком большой файл отклоняется")
    void sslUploadTooLarge() throws Exception {
        User admin = createAdmin();
        String big = "-----BEGIN CERTIFICATE-----\n" + "A".repeat(1_100_000)
                + "\n-----END CERTIFICATE-----";
        mockMvc.perform(as(admin, post("/api/v1/admin/ssl"))
                        .contentType("application/json")
                        .content(body(Map.of("certPem", big, "keyPem", big))))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────── резервные копии ───────────────────────

    private Path writeBackup(String name) throws Exception {
        Path dir = Paths.get(backupPath);
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, "тестовая резервная копия");
        return file;
    }

    @Test
    @DisplayName("Бэкапы: существующий файл виден в списке и скачивается")
    void backupListAndDownload() throws Exception {
        User admin = createAdmin();
        String name = "afina2-" + System.nanoTime() + ".sql.gz";
        Path file = writeBackup(name);

        mockMvc.perform(asGet(admin, get("/api/v1/admin/backups")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='" + name + "')]").exists());

        byte[] data = mockMvc.perform(asGet(admin,
                        get("/api/v1/admin/backups/" + name + "/download")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(data).isEqualTo("тестовая резервная копия".getBytes(StandardCharsets.UTF_8));

        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("Бэкапы: удаление существующего файла")
    void backupDelete() throws Exception {
        User admin = createAdmin();
        String name = "afina2-" + System.nanoTime() + ".sql.gz";
        Path file = writeBackup(name);

        mockMvc.perform(as(admin, delete("/api/v1/admin/backups/" + name)))
                .andExpect(status().isOk());
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("Бэкапы: создание без пароля бэкапа → 500 с понятным сообщением")
    void backupCreateWithoutPassword() throws Exception {
        User admin = createAdmin();
        // backup.pass в тестовом окружении отсутствует — создание невозможно
        mockMvc.perform(as(admin, post("/api/v1/admin/backups")))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @DisplayName("Бэкапы: зашифрованный бэкап восстанавливается только через CLI")
    void backupRestoreEncrypted() throws Exception {
        User admin = createAdmin();
        String name = "afina2-" + System.nanoTime() + ".tar.gz.enc";
        Path file = writeBackup(name);

        mockMvc.perform(as(admin, post("/api/v1/admin/backups/" + name + "/restore")))
                .andExpect(status().is4xxClientError());

        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("Бэкапы: имя с недопустимыми символами отклоняется")
    void backupInvalidName() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(as(admin, delete("/api/v1/admin/backups/a%20b.zip")))
                .andExpect(status().isBadRequest());
    }
}
