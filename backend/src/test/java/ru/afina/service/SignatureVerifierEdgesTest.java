package ru.afina.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Непокрытые ветки {@link SignatureVerifier}: толерантный base64, извлечение CN,
 * сертификат подписанта, «быстрая» проверка без цепочки, построение цепочки
 * (включая fallback на truststore), битые файлы truststore/CRL и политика отзыва.
 *
 * <p>Сертификаты и CMS-подписи генерируются BouncyCastle — без внешних УЦ,
 * поэтому тесты детерминированы и не требуют сети.</p>
 */
@DisplayName("SignatureVerifier: разбор подписи, цепочка и CRL")
@SuppressWarnings("java:S5778") // несколько assertThatThrownBy подряд — читается лучше
class SignatureVerifierEdgesTest {

    private static final byte[] DATA = "Договор поставки №148-2026".getBytes(StandardCharsets.UTF_8);
    private static final String LEAF_CN = "Иванов Иван Иванович";

    @TempDir Path tempDir;

    private static KeyPair rootKp;
    private static KeyPair leafKp;
    private static X509Certificate root;
    private static X509Certificate leaf;

    private static KeyPair expiredKp;
    private static X509Certificate expiredLeaf;

    private static KeyPair expiredRootKp;
    private static X509Certificate expiredRoot;
    private static KeyPair subLeafKp;
    private static X509Certificate subLeaf;

    private static final AtomicInteger SERIAL = new AtomicInteger(1000);

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeAll
    static void generateCertificates() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);

        rootKp = kpg.generateKeyPair();
        leafKp = kpg.generateKeyPair();
        root = cert(rootKp, "CN=Test Root CA, O=Афина", rootKp,
                "CN=Test Root CA, O=Афина", daysAgo(1), daysAhead(365));
        leaf = cert(leafKp, "CN=" + LEAF_CN + ", O=Афина, C=RU", rootKp,
                "CN=Test Root CA, O=Афина", daysAgo(1), daysAhead(365));

        expiredKp = kpg.generateKeyPair();
        expiredLeaf = cert(expiredKp, "CN=Просроченный Тест", expiredKp,
                "CN=Просроченный Тест", daysAgo(400), daysAgo(1));

        expiredRootKp = kpg.generateKeyPair();
        subLeafKp = kpg.generateKeyPair();
        expiredRoot = cert(expiredRootKp, "CN=Просроченный УЦ", expiredRootKp,
                "CN=Просроченный УЦ", daysAgo(800), daysAgo(10));
        subLeaf = cert(subLeafKp, "CN=Подчинённый Лист", expiredRootKp,
                "CN=Просроченный УЦ", daysAgo(100), daysAhead(365));
    }

    // ──────────────────────────── генерация данных ────────────────────────────

    private static Date daysAgo(int days) {
        return new Date(System.currentTimeMillis() - days * 86_400_000L);
    }

    private static Date daysAhead(int days) {
        return new Date(System.currentTimeMillis() + days * 86_400_000L);
    }

    private static X509Certificate cert(KeyPair keyPair, String subject, KeyPair issuerKey,
                                        String issuer, Date notBefore, Date notAfter)
            throws Exception {
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name(issuer), BigInteger.valueOf(SERIAL.incrementAndGet()),
                notBefore, notAfter, new X500Name(subject), keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(issuerKey.getPrivate());
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
    }

    /** Detached CMS-подпись; embedded — сертификаты, которые кладутся в контейнер. */
    private static String sign(byte[] content, X509Certificate signerCert, PrivateKey key,
                               X509Certificate... embedded) throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(key);
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                .build(cs, signerCert));
        if (embedded.length > 0) {
            gen.addCertificates(new JcaCertStore(List.of(embedded)));
        }
        CMSSignedData cms = gen.generate(new CMSProcessableByteArray(content), false);
        return Base64.getEncoder().encodeToString(cms.getEncoded());
    }

    /** Подпись «лист + корень» — полная цепочка внутри CMS. */
    private static String signedByLeaf() throws Exception {
        return sign(DATA, leaf, leafKp.getPrivate(), leaf, root);
    }

    private static void writeCert(Path dir, String name, X509Certificate certificate)
            throws Exception {
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), certificate.getEncoded());
    }

    private static void writeCrl(Path crlsDir, String issuerDn, KeyPair signerKp,
                                 Date thisUpdate, Date nextUpdate, BigInteger... revoked)
            throws Exception {
        X509v2CRLBuilder builder = new X509v2CRLBuilder(new X500Name(issuerDn), thisUpdate);
        builder.setNextUpdate(nextUpdate);
        for (BigInteger serial : revoked) {
            builder.addCRLEntry(serial, thisUpdate, 0);
        }
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(signerKp.getPrivate());
        X509CRL crl = new JcaX509CRLConverter().setProvider("BC").getCRL(builder.build(cs));
        Files.createDirectories(crlsDir);
        Files.write(crlsDir.resolve("crl-" + SERIAL.incrementAndGet() + ".crl"), crl.getEncoded());
    }

    /** Верификатор с подготовленными каталогами certs/ (root.cer) и crls/. */
    private SignatureVerifier verifier(SettingsService settings) throws Exception {
        Path certs = tempDir.resolve("certs");
        Path crls = tempDir.resolve("crls");
        writeCert(certs, "root.cer", root);
        Files.createDirectories(crls);
        return new SignatureVerifier(certs.toString(), crls.toString(), settings);
    }

    private SignatureVerifier verifierAt(Path certs, Path crls, SettingsService settings) {
        return new SignatureVerifier(certs.toString(), crls.toString(), settings);
    }

    private static SettingsService settingsStub(String mode, boolean requireCrlSignature) {
        return settingsStub(mode, requireCrlSignature, false);
    }

    private static SettingsService settingsStub(String mode, boolean requireCrlSignature,
                                                boolean allowRootDnMatch) {
        SettingsService settings = Mockito.mock(SettingsService.class);
        when(settings.getOrDefault(eq("revocation.mode"), anyString())).thenReturn(mode);
        when(settings.getBoolOrDefault(eq("signature.require_crl"), anyBoolean()))
                .thenReturn(false);
        when(settings.getBoolOrDefault(eq("signature.require_crl_signature"), anyBoolean()))
                .thenReturn(requireCrlSignature);
        when(settings.getBoolOrDefault(eq("signature.allow_root_dn_match"), anyBoolean()))
                .thenReturn(allowRootDnMatch);
        return settings;
    }

    /** Detached CMS-подпись БЕЗ signed attributes (прямая подпись содержимого). */
    private static String signWithoutSignedAttributes() throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(leafKp.getPrivate());
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                .setDirectSignature(true)   // прямая подпись: signedAttrs отсутствуют
                .build(cs, leaf));
        gen.addCertificates(new JcaCertStore(List.of(leaf, root)));
        CMSSignedData cms = gen.generate(new CMSProcessableByteArray(DATA), false);
        return Base64.getEncoder().encodeToString(cms.getEncoded());
    }

    // ════════════════════════ decodeBase64Tolerant ════════════════════════

    @Test
    @DisplayName("base64: пробелы, переводы строк и PEM-обёртка игнорируются")
    void decodesBase64WithWhitespaceAndPem() {
        byte[] raw = {0x01, 0x02, 0x03, (byte) 0xFF};
        String b64 = Base64.getEncoder().encodeToString(raw);

        assertThat(SignatureVerifier.decodeBase64Tolerant(b64)).isEqualTo(raw);
        assertThat(SignatureVerifier.decodeBase64Tolerant(
                " " + b64.substring(0, 4) + "\n" + b64.substring(4) + "\r\n\t ")).isEqualTo(raw);
        assertThat(SignatureVerifier.decodeBase64Tolerant(
                "-----BEGIN CMS-----\n" + b64 + "\n-----END CMS-----")).isEqualTo(raw);
        assertThat(SignatureVerifier.decodeBase64Tolerant(
                "-----BEGIN SIGNATURE-----\r\n" + b64 + "\r\n-----END SIGNATURE-----\r\n"))
                .isEqualTo(raw);
    }

    @Test
    @DisplayName("base64: URL-safe алфавит и отсутствие padding поддерживаются")
    void decodesUrlSafeAndUnpaddedBase64() {
        // URL-safe «-» и «_» соответствуют стандартным «+» и «/»
        assertThat(SignatureVerifier.decodeBase64Tolerant("----"))
                .isEqualTo(Base64.getDecoder().decode("++++"));
        assertThat(SignatureVerifier.decodeBase64Tolerant("____"))
                .isEqualTo(Base64.getDecoder().decode("////"));

        // без padding: 3 байта вместо 4 символов с «=»
        byte[] raw = {(byte) 0xFB, (byte) 0xEF, (byte) 0xBE};
        String unpadded = Base64.getEncoder().withoutPadding().encodeToString(raw);
        assertThat(unpadded).doesNotEndWith("=");
        assertThat(SignatureVerifier.decodeBase64Tolerant(unpadded)).isEqualTo(raw);
    }

    @Test
    @DisplayName("base64: padding из середины отбрасывается, мусорные символы удаляются")
    void decodesTolerantlyForDirtyInput() {
        // всё после первого «=» — мусор: декодируется только «QQ»
        assertThat(SignatureVerifier.decodeBase64Tolerant("QQ==QUJD"))
                .isEqualTo(new byte[]{'A'});
        // посторонние символы удаляются перед декодированием
        assertThat(SignatureVerifier.decodeBase64Tolerant("Q!Q @Q#"))
                .isEqualTo(new byte[]{0x41, 0x04});
    }

    @Test
    @DisplayName("base64: пустое значение и некорректная длина → IllegalArgumentException")
    void rejectsEmptyAndBadLengthBase64() {
        assertThatThrownBy(() -> SignatureVerifier.decodeBase64Tolerant(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty base64");
        assertThatThrownBy(() -> SignatureVerifier.decodeBase64Tolerant("   \n\t "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty base64");
        assertThatThrownBy(() -> SignatureVerifier.decodeBase64Tolerant("A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Некорректная длина base64");
    }

    @Test
    @DisplayName("base64: Data URI префикс снимается")
    void dataUriPrefixShouldBeStripped() {
        byte[] raw = {0x01, 0x02, 0x03, (byte) 0xFF};
        String b64 = Base64.getEncoder().encodeToString(raw);

        assertThat(SignatureVerifier.decodeBase64Tolerant(
                "data:application/octet-stream;base64," + b64)).isEqualTo(raw);
    }

    // ════════════════════════════ extractCn ════════════════════════════

    @Test
    @DisplayName("extractCn: разные форматы subject, отсутствие CN и null")
    void extractsCommonName() throws Exception {
        SignatureVerifier v = verifier(null);

        assertThat(v.extractCn("CN=" + LEAF_CN + ", O=Афина, C=RU")).isEqualTo(LEAF_CN);
        assertThat(v.extractCn("cn=Test User")).isEqualTo("Test User");
        assertThat(v.extractCn("CN=\"Иванов Иван\", O=Афина")).isEqualTo("Иванов Иван");
        assertThat(v.extractCn("CN=   Ivan   ")).isEqualTo("Ivan");
        // без CN возвращается вся строка subject
        assertThat(v.extractCn("O=Афина, C=RU")).isEqualTo("O=Афина, C=RU");
        assertThat(v.extractCn(null)).isEmpty();
    }

    // ════════════════════════════ extractSignerCert ════════════════════════════

    @Test
    @DisplayName("extractSignerCert: валидная подпись → сертификат подписанта")
    void extractSignerCertReturnsCertificate() throws Exception {
        String sig = sign(DATA, leaf, leafKp.getPrivate(), leaf);

        X509Certificate extracted = verifier(null).extractSignerCert(sig);

        assertThat(extracted).isNotNull();
        assertThat(extracted.getSubjectX500Principal().getName()).contains(LEAF_CN);
        assertThat(extracted.getSerialNumber()).isEqualTo(leaf.getSerialNumber());
        // PEM-обёртка не мешает
        assertThat(verifier(null).extractSignerCert(
                "-----BEGIN SIGNATURE-----\n" + sig + "\n-----END SIGNATURE-----")).isNotNull();
    }

    @Test
    @DisplayName("extractSignerCert: нет сертификата в CMS и битые данные → null")
    void extractSignerCertReturnsNullForBadInput() throws Exception {
        SignatureVerifier v = verifier(null);
        String withoutCert = sign(DATA, leaf, leafKp.getPrivate());

        assertThat(v.extractSignerCert(withoutCert)).isNull();
        assertThat(v.extractSignerCert("%%%not-base64%%%")).isNull();
        assertThat(v.extractSignerCert("")).isNull();
        assertThat(v.extractSignerCert("    ")).isNull();
        assertThat(v.extractSignerCert(null)).isNull();
    }

    // ════════════════════════ verifyDetachedBasic ════════════════════════

    @Test
    @DisplayName("verifyDetachedBasic: подпись сходится → valid=true, chainVerified=false")
    void verifyDetachedBasicAcceptsValidSignature() throws Exception {
        String sig = signedByLeaf();

        Map<String, Object> result = verifier(null).verifyDetachedBasic(DATA, sig);

        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("chainVerified")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("signersCount")).isEqualTo(1);
        assertThat((String) result.get("signerSubject")).isEqualTo(LEAF_CN);
        assertThat((String) result.get("signerSerial")).isNotBlank();
        assertThat(result).doesNotContainKey("certExpired");
    }

    @Test
    @DisplayName("verifyDetachedBasic: подмена содержимого → SignatureMismatchException")
    void verifyDetachedBasicRejectsForeignContent() throws Exception {
        String sig = signedByLeaf();

        assertThatThrownBy(() -> verifier(null).verifyDetachedBasic(
                "Другой документ".getBytes(StandardCharsets.UTF_8), sig))
                .isInstanceOf(SignatureVerifier.SignatureMismatchException.class)
                .hasMessageContaining("не соответствует документу");
    }

    @Test
    @DisplayName("verifyDetachedBasic: подпись без встроенного сертификата → отказ")
    void verifyDetachedBasicRejectsSignatureWithoutCertificate() throws Exception {
        String sig = sign(DATA, leaf, leafKp.getPrivate());

        assertThatThrownBy(() -> verifier(null).verifyDetachedBasic(DATA, sig))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Сертификат подписанта не найден");
    }

    @Test
    @DisplayName("verifyDetachedBasic: digest сходится, но подпись испорчена → отказ без падения")
    void verifyDetachedBasicRejectsCorruptedSignature() throws Exception {
        byte[] cmsBytes = Base64.getDecoder().decode(signedByLeaf());
        CMSSignedData cms = new CMSSignedData(cmsBytes);
        byte[] signature = cms.getSignerInfos().getSigners().iterator().next().getSignature();

        // Портим последний байт самой подписи (OCTET STRING), не трогая signedAttrs:
        // digest содержимого сходится, а криптоподпись — уже нет.
        int start = lastIndexOf(cmsBytes, signature);
        assertThat(start).isPositive();
        cmsBytes[start + signature.length - 1] ^= 0x01;
        String corrupted = Base64.getEncoder().encodeToString(cmsBytes);

        assertThatThrownBy(() -> verifier(null).verifyDetachedBasic(DATA, corrupted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Подпись не может быть проверена");
    }

    /** Индекс последнего вхождения подпоследовательности (нужен для правки подписи в DER). */
    private static int lastIndexOf(byte[] source, byte[] target) {
        for (int i = source.length - target.length; i >= 0; i--) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    @Test
    @DisplayName("verifyDetachedBasic: пустая и битая подпись → IllegalArgumentException")
    void verifyDetachedBasicRejectsEmptySignature() throws Exception {
        SignatureVerifier v = verifier(null);

        assertThatThrownBy(() -> v.verifyDetachedBasic(DATA, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty base64");
        assertThatThrownBy(() -> v.verifyDetachedBasic(DATA, "%%%"))
                .isInstanceOf(org.bouncycastle.cms.CMSException.class);
    }

    // ════════════════════════ verifyDetached: цепочка ════════════════════════

    @Test
    @DisplayName("verifyDetached: подпись без сертификата → IllegalStateException")
    void verifyDetachedRejectsMissingCertificate() throws Exception {
        String sig = sign(DATA, leaf, leafKp.getPrivate());

        assertThatThrownBy(() -> verifier(null).verifyDetached(DATA, sig))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Сертификат подписанта не найден");
    }

    @Test
    @DisplayName("verifyDetached: цепочка строится полностью → valid=true")
    void verifyDetachedBuildsFullChainFromCms() throws Exception {
        Map<String, Object> result = verifier(null).verifyDetached(DATA, signedByLeaf());

        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("signersCount")).isEqualTo(1);
        assertThat((String) result.get("signerSubject")).isEqualTo(LEAF_CN);
    }

    @Test
    @DisplayName("verifyDetached: issuer только в truststore → цепочка собирается fallback'ом")
    void verifyDetachedBuildsChainFromTruststore() throws Exception {
        // В CMS только лист, корень — в truststore
        String sig = sign(DATA, leaf, leafKp.getPrivate(), leaf);

        Map<String, Object> result = verifier(null).verifyDetached(DATA, sig);

        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
        assertThat((String) result.get("signerSubject")).isEqualTo(LEAF_CN);
    }

    @Test
    @DisplayName("verifyDetached: корня нет ни в CMS, ни в truststore → нет issuer'а")
    void verifyDetachedFailsWhenIssuerNotFound() throws Exception {
        Path certs = tempDir.resolve("other-certs");
        Path crls = tempDir.resolve("other-crls");
        writeCert(certs, "unrelated.cer", expiredLeaf);
        Files.createDirectories(crls);

        String sig = sign(DATA, leaf, leafKp.getPrivate(), leaf);
        SignatureVerifier v = verifierAt(certs, crls, null);

        assertThatThrownBy(() -> v.verifyDetached(DATA, sig))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не найден issuer");
    }

    @Test
    @DisplayName("verifyDetached: просроченный сертификат подписанта → отказ")
    void verifyDetachedRejectsExpiredLeaf() throws Exception {
        Path certs = tempDir.resolve("certs-expired");
        Path crls = tempDir.resolve("crls-expired");
        writeCert(certs, "expired.cer", expiredLeaf);
        Files.createDirectories(crls);

        String sig = sign(DATA, expiredLeaf, expiredKp.getPrivate(), expiredLeaf);

        assertThatThrownBy(() -> verifierAt(certs, crls, null).verifyDetached(DATA, sig))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Срок действия сертификата истёк");
    }

    @Test
    @DisplayName("verifyDetached: просроченное звено цепочки → отказ")
    void verifyDetachedRejectsExpiredChainLink() throws Exception {
        Path certs = tempDir.resolve("certs-sub");
        Path crls = tempDir.resolve("crls-sub");
        writeCert(certs, "expired-root.cer", expiredRoot);
        Files.createDirectories(crls);

        String sig = sign(DATA, subLeaf, subLeafKp.getPrivate(), subLeaf, expiredRoot);

        assertThatThrownBy(() -> verifierAt(certs, crls, null).verifyDetached(DATA, sig))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Срок действия звена цепочки истёк");
    }

    // ════════════════════════ truststore / CRL из файлов ════════════════════════

    @Test
    @DisplayName("truststore: каталог отсутствует → корень не доверенный, без падения")
    void missingTruststoreDirectoryIsHandled() throws Exception {
        Path missingCerts = tempDir.resolve("no-certs");
        Path crls = tempDir.resolve("no-crls");
        Files.createDirectories(crls);

        SignatureVerifier v = verifierAt(missingCerts, crls, null);

        assertThatThrownBy(() -> v.verifyDetached(DATA, signedByLeaf()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("truststore");
    }

    @Test
    @DisplayName("truststore: пустой и битый .cer и посторонний файл не ломают проверку")
    void brokenTruststoreFilesAreSkipped() throws Exception {
        Path certs = tempDir.resolve("broken-certs");
        Path crls = tempDir.resolve("broken-crls");
        Files.createDirectories(certs);
        Files.createDirectories(crls);
        Files.write(certs.resolve("empty.cer"), new byte[0]);
        Files.write(certs.resolve("garbage.cer"), "не сертификат".getBytes(StandardCharsets.UTF_8));
        Files.write(certs.resolve("readme.txt"), new byte[]{1, 2, 3});

        SignatureVerifier v = verifierAt(certs, crls, null);

        assertThatThrownBy(() -> v.verifyDetached(DATA, signedByLeaf()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("truststore");
    }

    @Test
    @DisplayName("CRL: битый файл пропускается, подпись при этом проверяется")
    void corruptCrlFileIsSkipped() throws Exception {
        Path certs = tempDir.resolve("certs-crl-broken");
        Path crls = tempDir.resolve("crls-broken");
        writeCert(certs, "root.cer", root);
        Files.createDirectories(crls);
        Files.write(crls.resolve("bad.crl"), "мусор".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> result = verifierAt(certs, crls, null)
                .verifyDetached(DATA, signedByLeaf());

        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("CRL: издатель не найден в truststore/CMS → CRL не доверенный (soft)")
    void crlWithoutKnownIssuerIsKeptOnlyInSoftMode() throws Exception {
        Path certs = tempDir.resolve("certs-crl-unknown");
        Path crls = tempDir.resolve("crls-unknown");
        writeCert(certs, "root.cer", root);
        writeCrl(crls, "CN=Чужой УЦ", expiredRootKp, daysAgo(1), daysAhead(7));

        Map<String, Object> result = verifierAt(certs, crls, null)
                .verifyDetached(DATA, signedByLeaf());

        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("CRL: подпись не сходится с издателем → CRL отбрасывается при require_crl_signature")
    void crlWithBadSignatureIsRejectedWhenRequired() throws Exception {
        Path certs = tempDir.resolve("certs-crl-badsig");
        Path crls = tempDir.resolve("crls-badsig");
        writeCert(certs, "root.cer", root);
        KeyPair foreignKp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        writeCrl(crls, "CN=Test Root CA, O=Афина", foreignKp, daysAgo(1), daysAhead(7));

        SignatureVerifier v = verifierAt(certs, crls,
                settingsStub("strict", true));

        assertThatThrownBy(() -> v.verifyDetached(DATA, signedByLeaf()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Нет CRL");
    }

    @Test
    @DisplayName("CRL: истёк nextUpdate → в soft-режиме используется, в strict — отбрасывается")
    void expiredCrlNextUpdateSoftKeepsIt() throws Exception {
        Path certs = tempDir.resolve("certs-crl-expired");
        Path crls = tempDir.resolve("crls-expired-next");
        writeCert(certs, "root.cer", root);
        writeCrl(crls, "CN=Test Root CA, O=Афина", rootKp, daysAgo(30), daysAgo(1));

        Map<String, Object> result = verifierAt(certs, crls, settingsStub("soft", false))
                .verifyDetached(DATA, signedByLeaf());

        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("Политика отзыва: неизвестный режим → SOFT")
    void unknownRevocationModeFallsBackToSoft() throws Exception {
        assertThat(verifier(settingsStub("weird", false)).revocationPolicy())
                .isEqualTo(SignatureVerifier.RevocationPolicy.SOFT);
    }

    @Test
    @DisplayName("Строгий режим без CRL: подпись блокируется при полной проверке")
    void strictModeWithoutCrlBlocksSignature() throws Exception {
        SignatureVerifier v = verifier(settingsStub("strict", false));

        assertThatThrownBy(() -> v.verifyDetached(DATA, signedByLeaf()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Нет CRL");
    }

    // ════════════════════════ Data URI и signedAttrs ════════════════════════

    @Test
    @DisplayName("verifyDetached: подпись с Data URI префиксом принимается")
    void verifyDetachedAcceptsDataUri() throws Exception {
        String sig = "data:application/pkcs7-signature;base64," + signedByLeaf();

        Map<String, Object> result = verifier(null).verifyDetached(DATA, sig);

        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
        assertThat((String) result.get("signerSubject")).isEqualTo(LEAF_CN);
    }

    @Test
    @DisplayName("verifyDetached: CMS без signedAttrs → отказ по целостности")
    void verifyDetachedRejectsSignatureWithoutSignedAttributes() throws Exception {
        String sig = signWithoutSignedAttributes();

        assertThatThrownBy(() -> verifier(null).verifyDetached(DATA, sig))
                .isInstanceOf(SignatureVerifier.SignatureMismatchException.class)
                .hasMessageContaining("не соответствует документу");
    }

    @Test
    @DisplayName("verifyDetachedBasic: CMS без signedAttrs → отказ по целостности")
    void verifyDetachedBasicRejectsSignatureWithoutSignedAttributes() throws Exception {
        String sig = signWithoutSignedAttributes();

        assertThatThrownBy(() -> verifier(null).verifyDetachedBasic(DATA, sig))
                .isInstanceOf(SignatureVerifier.SignatureMismatchException.class)
                .hasMessageContaining("не соответствует документу");
    }

    // ════════════════════════ совпадение DN корня ════════════════════════

    /** Подмена корня: тот же subject/issuer DN, но другой ключ. */
    private Path writeImpostorRoot(Path certs) throws Exception {
        KeyPair impostorKp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        X509Certificate impostor = cert(impostorKp,
                "CN=Test Root CA, O=Афина", impostorKp,
                "CN=Test Root CA, O=Афина", daysAgo(1), daysAhead(365));
        writeCert(certs, "impostor.cer", impostor);
        return certs;
    }

    @Test
    @DisplayName("truststore: корень с тем же DN, но другим ключом — НЕ доверенный")
    void sameDnDifferentKeyIsNotTrusted() throws Exception {
        Path certs = writeImpostorRoot(tempDir.resolve("certs-impostor"));
        Path crls = tempDir.resolve("crls-impostor");
        Files.createDirectories(crls);

        assertThatThrownBy(() -> verifierAt(certs, crls, null).verifyDetached(DATA, signedByLeaf()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("truststore");
    }

    @Test
    @DisplayName("truststore: совпадение по DN разрешается флагом signature.allow_root_dn_match")
    void sameDnDifferentKeyIsTrustedWithFlag() throws Exception {
        Path certs = writeImpostorRoot(tempDir.resolve("certs-impostor-flag"));
        Path crls = tempDir.resolve("crls-impostor-flag");
        Files.createDirectories(crls);

        Map<String, Object> result = verifierAt(certs, crls,
                settingsStub("soft", false, true)).verifyDetached(DATA, signedByLeaf());

        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("truststore: пустой каталог → корень не доверенный, без падения")
    void emptyTruststoreDirectoryIsNotTrusted() throws Exception {
        Path certs = tempDir.resolve("certs-empty-dir");
        Path crls = tempDir.resolve("crls-empty-dir");
        Files.createDirectories(certs);
        Files.createDirectories(crls);

        assertThatThrownBy(() -> verifierAt(certs, crls, null).verifyDetached(DATA, signedByLeaf()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("truststore");
    }

    // ════════════════════════ CRL без известного издателя ════════════════════════

    @Test
    @DisplayName("CRL: издателя нет при require_crl_signature=true → CRL отбрасывается")
    void crlWithoutKnownIssuerRejectedWhenSignatureRequired() throws Exception {
        Path certs = tempDir.resolve("certs-crl-required");
        Path crls = tempDir.resolve("crls-required");
        writeCert(certs, "root.cer", root);
        writeCrl(crls, "CN=Чужой УЦ", expiredRootKp, daysAgo(1), daysAhead(7));

        // CRL отброшен как непроверенный, но режим soft — подпись проходит
        Map<String, Object> result = verifierAt(certs, crls,
                settingsStub("soft", true)).verifyDetached(DATA, signedByLeaf());

        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
    }
}
