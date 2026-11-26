package ru.afina.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Доверие корню — только по точному отпечатку SHA-256.
 *
 * <p>Раньше корень принимался «по DN» с предупреждением (в проде так проходил
 * перевыпущенный корень Минцифры). Теперь совпадение DN с другим ключом
 * отклоняется; послабление — только явным флагом signature.allow_root_dn_match.
 */
@DisplayName("SignatureVerifier: доверие корневому сертификату")
class SignatureVerifierTruststoreTest {

    @TempDir Path tempDir;

    private static X509Certificate root;
    private static X509Certificate sameDnOtherKey;

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeAll
    static void certs() throws Exception {
        root = selfSigned("CN=Минцифры России, O=Test", "root-");
        sameDnOtherKey = selfSigned("CN=Минцифры России, O=Test", "evil-");
    }

    private static X509Certificate selfSigned(String dn, String marker) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        long now = System.currentTimeMillis();
        X500Name name = new X500Name(dn);
        var builder = new JcaX509v3CertificateBuilder(
                name, new BigInteger(1, (marker + now).getBytes()),
                new Date(now - 1000), new Date(now + 86400000L), name, kp.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private SignatureVerifier verifier(SettingsService settings) {
        return new SignatureVerifier(tempDir.resolve("certs").toString(),
                tempDir.resolve("crls").toString(), settings);
    }

    private static boolean trusted(SignatureVerifier v, X509Certificate chainRoot) {
        Boolean res = ReflectionTestUtils.invokeMethod(v, "isTrustedRoot",
                chainRoot, Set.of(root));
        return Boolean.TRUE.equals(res);
    }

    @Test
    @DisplayName("корень с точным отпечатком — доверенный")
    void fingerprintMatchTrusted() {
        assertThat(trusted(verifier(null), root)).isTrue();
    }

    @Test
    @DisplayName("тот же DN, но другой ключ — НЕ доверенный (без флага)")
    void sameDnDifferentKeyRejected() {
        assertThat(trusted(verifier(null), sameDnOtherKey)).isFalse();
    }

    @Test
    @DisplayName("тот же DN с флагом allow_root_dn_match=true — разрешён (аварийный режим)")
    void sameDnAllowedByFlag() {
        SettingsService s = Mockito.mock(SettingsService.class);
        when(s.getBoolOrDefault("signature.allow_root_dn_match", false)).thenReturn(true);

        assertThat(trusted(verifier(s), sameDnOtherKey)).isTrue();
    }

    @Test
    @DisplayName("чужой корень — не доверенный")
    void unknownRootRejected() throws Exception {
        X509Certificate other = selfSigned("CN=Some Other CA", "other-");
        assertThat(trusted(verifier(null), other)).isFalse();
    }
}
