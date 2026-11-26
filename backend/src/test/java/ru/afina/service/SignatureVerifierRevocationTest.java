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
import ru.afina.service.SignatureVerifier.RevocationPolicy;

import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Настройки «Режим проверки отзыва» (revocation.mode) и «Требовать актуальный CRL»
 * (signature.require_crl) раньше ни на что не влияли — теперь управляют проверкой.
 */
@DisplayName("SignatureVerifier: политика проверки отзыва из настроек")
class SignatureVerifierRevocationTest {

    @TempDir Path tempDir;

    private static X509Certificate leaf;
    private static X509Certificate root;

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeAll
    static void certs() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair rootKp = kpg.generateKeyPair();
        root = selfSigned(rootKp, "CN=Test Root CA");
        leaf = selfSigned(kpg.generateKeyPair(), "CN=leaf");
    }

    private static X509Certificate selfSigned(KeyPair kp, String dn) throws Exception {
        long now = System.currentTimeMillis();
        X500Name name = new X500Name(dn);
        var builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(System.nanoTime()), new Date(now - 1000),
                new Date(now + 86400000L), name, kp.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private SignatureVerifier verifier(SettingsService settings) {
        return new SignatureVerifier(tempDir.resolve("certs").toString(),
                tempDir.resolve("crls").toString(), settings);
    }

    private static SettingsService settingsStub(String mode, boolean requireCrl) {
        SettingsService s = Mockito.mock(SettingsService.class);
        when(s.getOrDefault(Mockito.eq("revocation.mode"), anyString())).thenReturn(mode);
        when(s.getBoolOrDefault(Mockito.eq("signature.require_crl"), anyBoolean()))
                .thenReturn(requireCrl);
        return s;
    }

    @Test
    @DisplayName("strict: нет CRL → подпись блокируется")
    void strictBlocksWithoutCrl() {
        var v = verifier(settingsStub("strict", false));

        assertThatThrownBy(() -> v.checkNotRevoked(List.of(leaf, root), Set.of(),
                RevocationPolicy.STRICT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Нет CRL");
    }

    @Test
    @DisplayName("soft: нет CRL → пропускаем с предупреждением")
    void softAllowsWithoutCrl() {
        var v = verifier(settingsStub("soft", false));

        assertThatCode(() -> v.checkNotRevoked(List.of(leaf, root), Set.of(),
                RevocationPolicy.SOFT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("off: проверка отзыва вообще не выполняется")
    void offSkipsRevocation() {
        var v = verifier(settingsStub("off", false));

        assertThatCode(() -> v.checkNotRevoked(List.of(leaf, root), Set.of(),
                RevocationPolicy.OFF)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("revocation.mode=strict → STRICT")
    void policyStrict() {
        assertThat(verifier(settingsStub("strict", false)).revocationPolicy())
                .isEqualTo(RevocationPolicy.STRICT);
    }

    @Test
    @DisplayName("revocation.mode=off → OFF")
    void policyOff() {
        assertThat(verifier(settingsStub("off", false)).revocationPolicy())
                .isEqualTo(RevocationPolicy.OFF);
    }

    @Test
    @DisplayName("галочка «требовать CRL» поднимает режим до STRICT")
    void policyRequireCrl() {
        assertThat(verifier(settingsStub("soft", true)).revocationPolicy())
                .isEqualTo(RevocationPolicy.STRICT);
    }

    @Test
    @DisplayName("по умолчанию (нет настроек) → SOFT")
    void policyDefault() {
        assertThat(verifier(null).revocationPolicy()).isEqualTo(RevocationPolicy.SOFT);
    }
}
