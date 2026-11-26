package ru.afina.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Прикрепление .sig: главное — соответствие подписи документу.
 * Просроченный сертификат не должен блокировать загрузку.
 */
@DisplayName("verifyDetachedBasic: срок действия сертификата не важен")
class SignatureVerifierExpiredCertTest {

    private static KeyPair keyPair;
    private static X509Certificate expiredCert;

    @TempDir Path tempDir;

    @BeforeAll
    static void generateExpiredCert() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=Expired Signer, O=AfinaZ, C=RU");
        Date notBefore = new Date(System.currentTimeMillis() - 40L * 86_400_000L);
        Date notAfter = new Date(System.currentTimeMillis() - 10L * 86_400_000L);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(42L), notBefore, notAfter, subject, keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        expiredCert = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
    }

    private static String signDetached(byte[] content) throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                .build(cs, expiredCert));
        gen.addCertificates(new JcaCertStore(List.of(expiredCert)));
        CMSSignedData cms = gen.generate(new CMSProcessableByteArray(content), false);
        return Base64.getEncoder().encodeToString(cms.getEncoded());
    }

    private SignatureVerifier verifier() throws Exception {
        Path certsDir = tempDir.resolve("certs");
        Path crlsDir = tempDir.resolve("crls");
        Files.createDirectories(certsDir);
        Files.createDirectories(crlsDir);
        return new SignatureVerifier(certsDir.toString(), crlsDir.toString(), null);
    }

    @Test
    @DisplayName("просроченный сертификат + подпись сходится → принято, certExpired=true")
    void acceptsExpiredCert() throws Exception {
        byte[] data = "Договор №1".getBytes();
        String sig = signDetached(data);

        Map<String, Object> result = verifier().verifyDetachedBasic(data, sig);

        assertThat(result)
                .containsEntry("valid", Boolean.TRUE)
                .containsEntry("certExpired", Boolean.TRUE);
        assertThat((String) result.get("signerSubject")).contains("Expired Signer");
    }

    @Test
    @DisplayName(".sig от другого файла → SignatureMismatchException")
    void rejectsMismatchedDocument() throws Exception {
        String sig = signDetached("Договор №1".getBytes());

        assertThatThrownBy(() -> verifier().verifyDetachedBasic("Другой документ".getBytes(), sig))
                .isInstanceOf(SignatureVerifier.SignatureMismatchException.class);
    }
}
