package ru.afina.gosklyuch;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Госключ: подписант пакета из PKCS#12")
class Pkcs12GosklyuchSignerTest {

    @TempDir Path tempDir;

    @BeforeAll
    static void provider() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    @DisplayName("Подпись PKCS#12 собирается и проверяется")
    void signsAndVerifies() throws Exception {
        byte[] content = "PKCS12 test content".getBytes();
        Path p12 = createP12("secret");
        Path certs = tempDir.resolve("certs");
        Files.createDirectories(certs);
        Files.write(certs.resolve("root.cer"), readCert(p12).getEncoded());

        GosklyuchProperties props = new GosklyuchProperties();
        props.setP12Path(p12.toString());
        props.setP12Password("secret");

        Pkcs12GosklyuchSigner signer = new Pkcs12GosklyuchSigner(props);
        byte[] sig = signer.signDetached("req.xml", content);

        assertThat(sig).isNotEmpty();
        var verifier = new ru.afina.service.SignatureVerifier(
                certs.toString(), tempDir.resolve("crls").toString(), null);
        var result = verifier.verifyDetached(content,
                java.util.Base64.getEncoder().encodeToString(sig));
        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("Алгоритмы и провайдер выбираются по типу ключа")
    void algorithmSelection() {
        assertThat(Pkcs12GosklyuchSigner.algorithmsFor("ECGOST3410_2012_256"))
                .contains("GOST3411_2012_256withGOST3410_2012_256");
        assertThat(Pkcs12GosklyuchSigner.algorithmsFor("RSA"))
                .containsExactly("SHA256withRSA");
        assertThat(Pkcs12GosklyuchSigner.algorithmsFor("EC"))
                .containsExactly("SHA256withECDSA");
        assertThat(Pkcs12GosklyuchSigner.resolveProvider("", "ECGOST3410_2012_256"))
                .isEqualTo("JCSP");
        assertThat(Pkcs12GosklyuchSigner.resolveProvider("", "RSA")).isNull();
        assertThat(Pkcs12GosklyuchSigner.resolveProvider("BC", "RSA")).isEqualTo("BC");
    }

    @Test
    @DisplayName("Нет файла или неверный пароль — понятная ошибка")
    void rejectsBadConfig() {
        GosklyuchProperties props = new GosklyuchProperties();
        props.setP12Path(tempDir.resolve("missing.p12").toString());
        assertThatThrownBy(() -> new Pkcs12GosklyuchSigner(props))
                .isInstanceOf(GosklyuchUnavailableException.class);

        assertThatThrownBy(() -> {
            GosklyuchProperties p = new GosklyuchProperties();
            p.setP12Path(tempDir.resolve("missing.p12").toString());
            p.setP12Password("x");
            new Pkcs12GosklyuchSigner(p);
        }).isInstanceOf(GosklyuchUnavailableException.class);
    }

    private Path createP12(String password) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        long now = System.currentTimeMillis();
        X500Name dn = new X500Name("CN=Org Signer, O=Test, C=RU");
        var cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(
                new JcaX509v3CertificateBuilder(dn, BigInteger.valueOf(now),
                        new Date(now - 1000), new Date(now + 86_400_000L), dn, kp.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA")
                                .setProvider("BC").build(kp.getPrivate())));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("signer", kp.getPrivate(), password.toCharArray(),
                new java.security.cert.Certificate[]{cert});
        Path file = tempDir.resolve("signer.p12");
        try (var out = Files.newOutputStream(file)) {
            ks.store(out, password.toCharArray());
        }
        return file;
    }

    private X509Certificate readCert(Path p12) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(p12)) {
            ks.load(in, "secret".toCharArray());
        }
        return (X509Certificate) ks.getCertificate("signer");
    }
}
