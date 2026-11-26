package ru.afina.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@DisplayName("CRL: состояние, подпись, срок действия и алерты")
class CrlStatusServiceTest {

    @TempDir Path tempDir;

    static KeyPair keyPair;
    static X509Certificate root;

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeAll
    static void cert() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();
        long now = System.currentTimeMillis();
        var builder = new JcaX509v3CertificateBuilder(
                new X500Name("CN=CRL Status Root"), BigInteger.valueOf(System.nanoTime()),
                new Date(now - 1000), new Date(now + 86_400_000L),
                new X500Name("CN=CRL Status Root"), keyPair.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        root = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve("certs"));
        Files.createDirectories(tempDir.resolve("crls"));
        Files.write(tempDir.resolve("certs").resolve("root.cer"), root.getEncoded());
    }

    private CrlStatusService service(SettingsService settings, EmailService email) {
        return new CrlStatusService(tempDir.resolve("crls").toString(),
                tempDir.resolve("certs").toString(), settings, email);
    }

    private SettingsService settings() {
        SettingsService s = Mockito.mock(SettingsService.class);
        lenient().when(s.getBoolOrDefault(anyString(), anyBoolean())).thenReturn(true);
        lenient().when(s.getOrDefault(anyString(), anyString())).thenReturn("");
        lenient().when(s.getOrDefault(eq("monitor.mail_to"), anyString()))
                .thenReturn("sec@example.test");
        return s;
    }

    @Test
    @DisplayName("Действующий CRL: OK и подпись проверена")
    void okCrl() throws Exception {
        writeCrl("ok.crl", keyPair, 30);
        var report = service(settings(), Mockito.mock(EmailService.class)).report();

        assertThat(report.summary().total()).isEqualTo(1);
        assertThat(report.summary().ok()).isEqualTo(1);
        assertThat(report.items().get(0).signatureVerified()).isTrue();
        assertThat(report.items().get(0).status()).isEqualTo(CrlStatusService.Status.OK);
    }

    @Test
    @DisplayName("Истекающий и истёкший CRL различаются")
    void expiringAndExpired() throws Exception {
        writeCrl("soon.crl", keyPair, 3);
        writeCrl("old.crl", keyPair, -2);
        var report = service(settings(), Mockito.mock(EmailService.class)).report();

        assertThat(report.summary().expiring()).isEqualTo(1);
        assertThat(report.summary().expired()).isEqualTo(1);
    }

    @Test
    @DisplayName("CRL с чужой подписью помечается как не проверенный")
    void untrustedCrl() throws Exception {
        KeyPair other = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        writeCrl("bad.crl", other, 30);
        var report = service(settings(), Mockito.mock(EmailService.class)).report();

        assertThat(report.summary().untrusted()).isEqualTo(1);
        assertThat(report.items().get(0).signatureVerified()).isFalse();
    }

    @Test
    @DisplayName("Алерт отправляется при проблемах и не отправляется при OK")
    void sendsAlerts() throws Exception {
        SettingsService settings = settings();
        EmailService email = Mockito.mock(EmailService.class);
        CrlStatusService service = service(settings, email);

        service.scheduledCheck();
        org.mockito.Mockito.verify(email, org.mockito.Mockito.never())
                .sendAlert(anyString(), anyString(), anyString());

        writeCrl("old.crl", keyPair, -2);
        service.scheduledCheck();
        verify(email).sendAlert(eq("sec@example.test"), anyString(), anyString());
    }

    private void writeCrl(String name, KeyPair signerKey, int daysToNextUpdate) throws Exception {
        long now = System.currentTimeMillis();
        X509v2CRLBuilder builder = new X509v2CRLBuilder(
                new X500Name("CN=CRL Status Root"), new Date(now - 1000));
        builder.setNextUpdate(new Date(now + daysToNextUpdate * 86_400_000L));
        builder.addCRLEntry(BigInteger.valueOf(42), new Date(now - 500), 0);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(signerKey.getPrivate());
        X509CRL crl = new JcaX509CRLConverter().setProvider("BC")
                .getCRL(builder.build(signer));
        Files.write(tempDir.resolve("crls").resolve(name), crl.getEncoded());
    }
}
