package ru.afina.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
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
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Подлинность CRL: подпись издателя и срок действия учитываются,
 * непроверенный CRL отбрасывается при {@code signature.require_crl_signature=true}.
 */
@DisplayName("SignatureVerifier: подлинность CRL")
class SignatureVerifierCrlTrustTest {

    private static final byte[] CONTENT = "CRL trust test".getBytes();

    @TempDir Path tempDir;

    static KeyPair rootKp;
    static KeyPair leafKp;
    static X509Certificate root;
    static X509Certificate leaf;

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeAll
    static void certs() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        rootKp = kpg.generateKeyPair();
        leafKp = kpg.generateKeyPair();
        root = cert(rootKp, "CN=CRL Root", rootKp, "CN=CRL Root");
        leaf = cert(leafKp, "CN=CRL Leaf", rootKp, "CN=CRL Root");
    }

    private static X509Certificate cert(KeyPair kp, String subject, KeyPair issuerKp,
                                        String issuer) throws Exception {
        long now = System.currentTimeMillis();
        var builder = new JcaX509v3CertificateBuilder(
                new X500Name(issuer), BigInteger.valueOf(System.nanoTime()),
                new Date(now - 1000), new Date(now + 86_400_000L),
                new X500Name(subject), kp.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve("certs"));
        Files.createDirectories(tempDir.resolve("crls"));
        Files.write(tempDir.resolve("certs").resolve("root.cer"), root.getEncoded());
    }

    private SignatureVerifier verifier(boolean requireCrlSignature, String mode) {
        SettingsService settings = Mockito.mock(SettingsService.class);
        when(settings.getOrDefault(eq("revocation.mode"), anyString())).thenReturn(mode);
        when(settings.getBoolOrDefault(eq("signature.require_crl"), anyBoolean())).thenReturn(false);
        when(settings.getBoolOrDefault(eq("signature.require_crl_signature"), anyBoolean()))
                .thenReturn(requireCrlSignature);
        return new SignatureVerifier(tempDir.resolve("certs").toString(),
                tempDir.resolve("crls").toString(), settings);
    }

    @Test
    @DisplayName("Подписанный издателем CRL с отозванным листом → отказ")
    void trustedCrlRejectsRevoked() throws Exception {
        writeCrl(rootKp, leaf.getSerialNumber());
        SignatureVerifier v = verifier(true, "strict");

        assertThatThrownBy(() -> v.verifyDetached(CONTENT, signedByLeaf()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("отозван");
    }

    @Test
    @DisplayName("CRL, подписанный чужим ключом, отбрасывается при require_crl_signature")
    void untrustedCrlSkipped() throws Exception {
        KeyPair otherKp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        writeCrl(otherKp, leaf.getSerialNumber());
        SignatureVerifier v = verifier(true, "strict");

        assertThatThrownBy(() -> v.verifyDetached(CONTENT, signedByLeaf()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Нет CRL");
    }

    @Test
    @DisplayName("Свежий подписанный CRL без отзыва → подпись принимается")
    void trustedCrlWithoutRevocationPasses() throws Exception {
        writeCrl(rootKp, BigInteger.valueOf(123456789L));
        SignatureVerifier v = verifier(true, "strict");

        var result = v.verifyDetached(CONTENT, signedByLeaf());
        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
    }

    private void writeCrl(KeyPair signerKp, BigInteger serial) throws Exception {
        long now = System.currentTimeMillis();
        X509v2CRLBuilder builder = new X509v2CRLBuilder(
                new X500Name("CN=CRL Root"), new Date(now - 1000));
        builder.setNextUpdate(new Date(now + 86_400_000L));
        builder.addCRLEntry(serial, new Date(now - 500), 0);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(signerKp.getPrivate());
        X509CRL crl = new JcaX509CRLConverter().setProvider("BC")
                .getCRL(builder.build(signer));
        Files.write(tempDir.resolve("crls").resolve("root.crl"), crl.getEncoded());
    }

    private static String signedByLeaf() throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(leafKp.getPrivate());
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                .build(cs, leaf));
        gen.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(List.of(leaf, root)));
        return Base64.getEncoder().encodeToString(
                gen.generate(new CMSProcessableByteArray(CONTENT), false).getEncoded());
    }
}
