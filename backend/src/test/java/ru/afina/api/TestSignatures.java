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

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Тестовые УКЭП-подписи.
 *
 * <p>Формирует настоящий CMS/CAdES-BES контейнер (BouncyCastle, RSA) с
 * самоподписанным сертификатом. Формат тот же, что отдаёт плагин КриптоПро, —
 * этого достаточно для проверки всех веток приёма подписи.</p>
 */
final class TestSignatures {

    private static KeyPair keyPair;
    private static X509Certificate certificate;

    private TestSignatures() {
    }

    /** Ключ и сертификат для тестов (создаются один раз на JVM). */
    static synchronized void ensureCertificate() throws Exception {
        if (certificate != null) return;
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

    /** Отсоединённая подпись контента в base64. */
    static String detached(byte[] content) throws Exception {
        ensureCertificate();
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

    /** Подпись в бинарном виде (для multipart-загрузки .sig). */
    static byte[] detachedBytes(byte[] content) throws Exception {
        return Base64.getDecoder().decode(detached(content));
    }
}
