package ru.afina.service;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.util.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Верификатор CAdES-BES подписей через BouncyCastle с ручным
 * построением цепочки сертификатов и проверкой отзыва по CRL.
 *
 * НЕ использует JCSP для chain building — это позволяет обойти
 * проблему "Root certificate is untrusted", когда JCSP ищет корни
 * в своём системном хранилище, игнорируя переданный truststore.
 *
 * <p><b>Криптографические операции ГОСТ выполняет только JCSP</b>
 * (сертифицированное СКЗИ КриптоПро). BouncyCastle используется лишь для
 * разбора ASN.1/CMS/X.509/CRL; для ГОСТ-подписей, ГОСТ-дайджестов и подписей
 * звеньев ГОСТ-цепочки BC не применяется. Для не-ГОСТ алгоритмов (RSA/EC,
 * тесты и демо) BC остаётся допустимым fallback.</p>
 */
@Service
public class SignatureVerifier {

    /** Подпись не сходится с содержимым — файл .sig от другого документа. */
    public static class SignatureMismatchException extends IllegalArgumentException {
        public SignatureMismatchException(String msg) { super(msg); }
    }

    /** Ни BC, ни JCSP не смогли проверить подпись (алгоритм не поддерживается). */
    public static class CryptoNotSupportedException extends IllegalStateException {
        public CryptoNotSupportedException(String msg, Throwable cause) { super(msg, cause); }
    }

    private static final String LOG_SKIP_CERT = "Skipping certificate: {}";

    /** OID ГОСТ: 2012 (1.2.643.7.*) и 2001 (1.2.643.2.2.*). */
    static boolean isGostOid(String oid) {
        return oid != null && (oid.startsWith("1.2.643.7.") || oid.startsWith("1.2.643.2.2."));
    }

    /** Имя/идентификатор ГОСТ-алгоритма (включая OID 1.2.643.*). */
    static boolean isGostAlgName(String alg) {
        return alg != null && (alg.startsWith("GOST") || alg.startsWith("ECGOST")
                || alg.startsWith("1.2.643"));
    }

    /** Подпись CMS выполнена по ГОСТ (по алгоритму подписи или дайджеста). */
    static boolean isGostSigner(SignerInformation signer) {
        try {
            return isGostOid(signer.getEncryptionAlgOID())
                    || isGostOid(signer.getDigestAlgOID());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Провайдеры для проверки подписи: ГОСТ — только сертифицированный JCSP,
     * остальные алгоритмы — BC (тесты/демо) и JCSP.
     */
    static String[] signatureProviders(boolean gost) {
        return gost ? new String[]{"JCSP"} : new String[]{"BC", "JCSP"};
    }


    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);

    /**
     * Толерантное декодирование base64 (принимает PEM, URL-safe, без padding).
     * Нужно, чтобы не падать на реальных .sig от разных плагинов.
     */
    public static byte[] decodeBase64Tolerant(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("empty base64");
        }
        // 1. Убрать PEM-обёртку и whitespace
        String cleaned = s
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s+", "");
        // 2. URL-safe → стандартный base64
        cleaned = cleaned.replace('-', '+').replace('_', '/');
        // 3. Оставить только base64-символы
        cleaned = cleaned.replaceAll("[^A-Za-z0-9+/=]", "");
        // 4. Убрать padding из середины (только на конце должен быть)
        int firstEq = cleaned.indexOf('=');
        if (firstEq >= 0) {
            cleaned = cleaned.substring(0, firstEq);
        }
        // 5. Добавить padding до кратности 4
        int rem = cleaned.length() % 4;
        if (rem == 2) cleaned += "==";
        else if (rem == 3) cleaned += "=";
        else if (rem == 1) {
            throw new IllegalArgumentException("Некорректная длина base64: " + cleaned.length());
        }
        // 6. Decode
        try {
            return java.util.Base64.getDecoder().decode(cleaned);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный base64: " + e.getMessage(), e);
        }
    }

    private final Path certsDir;
    private final Path crlsDir;
    /** Настройки проверки отзыва (revocation.mode / signature.require_crl). Может быть null в тестах. */
    private final ru.afina.service.SettingsService settings;

    /** Дублирует настройку БД: удобно включать через .env.prod без входа в админку. */
    @org.springframework.beans.factory.annotation.Value(
            "${app.signature.require-crl-signature:false}")
    private boolean requireCrlSignatureProperty;

    public SignatureVerifier(
            @Value("${app.certificates-path:/app/certs}") String certsPath,
            @Value("${app.crl-path:/app/crls}") String crlsPath,
            ru.afina.service.SettingsService settings) {
        this.certsDir = Paths.get(certsPath);
        this.crlsDir = Paths.get(crlsPath);
        this.settings = settings;
        log.info("SignatureVerifier: certs={}, crls={}", certsDir, crlsDir);
    }

    /** Политика проверки отзыва из настроек админки (пакетная видимость — для тестов). */
    enum RevocationPolicy { OFF, SOFT, STRICT }

    RevocationPolicy revocationPolicy() {
        if (settings == null) return RevocationPolicy.SOFT;
        String mode = settings.getOrDefault("revocation.mode", "soft");
        if ("off".equalsIgnoreCase(mode)) return RevocationPolicy.OFF;
        if ("strict".equalsIgnoreCase(mode)
                || settings.getBoolOrDefault("signature.require_crl", false)) {
            return RevocationPolicy.STRICT;
        }
        return RevocationPolicy.SOFT;
    }

    public Map<String, Object> verifyDetached(Path contentPath, String signatureBase64)
            throws java.io.IOException, org.bouncycastle.cms.CMSException, java.security.cert.CertificateException {
        byte[] data = Files.readAllBytes(contentPath);
        return verifyDetached(data, signatureBase64);
    }

    public Map<String, Object> verifyDetached(byte[] data, String signatureBase64)
            throws org.bouncycastle.cms.CMSException, java.security.cert.CertificateException {
        byte[] signatureBytes = decodeBase64Tolerant(signatureBase64);

        CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(data), signatureBytes);
        SignerInformationStore signers = cms.getSignerInfos();

        Set<X509Certificate> truststore = loadTrustedCerts();

        // CRL обновляются фоново в CrlRefreshService и по CDP перед подписанием.
        // Издатель CRL ищется и в truststore, и среди сертификатов самой подписи:
        // сертификат УЦ в truststore может быть старее, чем в CMS.
        java.util.Set<X509Certificate> knownCerts = new java.util.HashSet<>(truststore);
        knownCerts.addAll(collectCandidates(cms));
        Set<X509CRL> crls = loadAllCrls(knownCerts);

        Map<String, Object> result = new HashMap<>();
        List<String> infos = new ArrayList<>();
        String firstCn = "";
        String firstSerial = "";

        for (SignerInformation signer : signers.getSigners()) {
            X509Certificate leaf = getSignerCert(signer, cms);
            SignerMeta meta = verifyOneSigner(signer, leaf, cms, truststore, crls, data);
            infos.add(meta.info());
            if (firstCn.isEmpty()) {
                firstCn = meta.cn();
                firstSerial = meta.serial();
            }
        }

        result.put("valid", true);
        result.put("signersCount", infos.size());
        result.put("signersInfo", String.join("\n", infos));
        result.put("signerSubject", firstCn);
        result.put("signerSerial", firstSerial);
        return result;
    }

    /**
     * Верификация подписи <b>без построения цепочки</b>.
     * <p>Проверяет только:
     * <ol>
     *   <li>Криптографическую подпись документа signer-сертификатом</li>
     *   <li>Срок действия signer-сертификата</li>
     * </ol>
     * <p>НЕ проверяет: issuer chain, доверие корню, отзыв по CRL.
     *
     * <p>Используется при загрузке .sig через админку, когда .sig сделан
     * с опцией END_ENTITY_ONLY (без промежуточного CA в CMS).</p>
     */
    @SuppressWarnings("java:S112") // внутренний хелпер: спектр исключений широк
    public Map<String, Object> verifyDetachedBasic(byte[] data, String signatureBase64) throws Exception {
        byte[] signatureBytes = decodeBase64Tolerant(signatureBase64);

        CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(data), signatureBytes);
        SignerInformationStore signers = cms.getSignerInfos();

        Map<String, Object> result = new HashMap<>();
        List<String> infos = new ArrayList<>();
        String firstCn = "";
        String firstSerial = "";

        for (SignerInformation signer : signers.getSigners()) {
            X509Certificate leaf = getSignerCert(signer, cms);

            // 1) Быстрая проверка: digest из CMS == digest переданных данных?
            boolean digestMatches = checkSignedDigest(signer, data);
            if (!digestMatches) {
                throw new SignatureMismatchException(
                        "Файл подписи не соответствует документу. " +
                        "Возможно, выбран .sig от другого файла.");
            }

            // 2) Криптографическая проверка (с fallback для ГОСТ 2012)
            try {
                if (!verifySignerCrypto(signer, leaf, data)) {
                    throw new SignatureMismatchException(
                            "Файл подписи не соответствует документу " +
                            "(подпись не сходится с содержимым).");
                }
            } catch (CryptoNotSupportedException e) {
                // ГОСТ-подпись без сертифицированного JCSP не принимается:
                // совпадение digest не является доказательством подписи.
                throw new IllegalStateException(
                        "Подпись не может быть проверена: " + e.getMessage(), e);
            }

            // 3) Срок действия НЕ является отказом: при прикреплении .sig важно
            //    только соответствие подписи документу. Просроченный/ещё не вступивший
            //    сертификат лишь фиксируем в результате (для отображения/аудита).
            boolean expired = false;
            try {
                leaf.checkValidity();
            } catch (java.security.cert.CertificateException e) {
                expired = true;
                log.info("verifyDetachedBasic: подпись принята, но сертификат вне срока "
                        + "действия ({}) serial={}", e.getMessage(), leaf.getSerialNumber().toString(16));
            }
            if (expired) {
                result.put("certExpired", true);
                result.put("certNotBefore", leaf.getNotBefore().toInstant().toString());
                result.put("certNotAfter", leaf.getNotAfter().toInstant().toString());
            }

            String subject = leaf.getSubjectX500Principal().getName();
            String cn = extractCn(subject);
            String serial = leaf.getSerialNumber().toString(16);
            infos.add(subject + "; serial=" + serial);
            if (firstCn.isEmpty()) {
                firstCn = cn;
                firstSerial = serial;
            }
        }

        result.put("valid", true);
        result.put("signersCount", infos.size());
        result.put("signersInfo", String.join("\n", infos));
        result.put("signerSubject", firstCn);
        result.put("signerSerial", firstSerial);
        result.put("chainVerified", false);
        return result;
    }

    // ==================== Внутренние методы ====================

    /** Метаданные одного подписанта (для результата верификации). */
    private record SignerMeta(String cn, String serial, String info) {}

    /**
     * Полная проверка одного подписанта: крипто, цепочка, корень в truststore,
     * срок действия, отзыв по CRL.
     */
    private SignerMeta verifyOneSigner(SignerInformation signer, X509Certificate leaf,
                                       CMSSignedData cms,
                                       Set<X509Certificate> truststore,
                                       Set<X509CRL> crls, byte[] data) {
        // 0) Digest из signedAttrs должен совпадать с содержимым
        if (!checkSignedDigest(signer, data)) {
            throw new SignatureMismatchException(
                    "Файл подписи не соответствует документу. "
                            + "Возможно, выбран .sig от другого файла.");
        }
        // 1) Криптопроверка подписи: ГОСТ — только JCSP, не-ГОСТ — BC/JCSP
        if (!verifySignerCrypto(signer, leaf, data)) {
            throw new IllegalStateException("Подпись недействительна");
        }
        // 2) Срок действия листа
        try {
            leaf.checkValidity();
        } catch (java.security.cert.CertificateException e) {
            throw new IllegalStateException("Срок действия сертификата истёк", e);
        }

        // 3) Построение цепочки (вручную)
        List<X509Certificate> chain = buildChain(leaf, cms, truststore);
        if (log.isInfoEnabled()) {
            log.info("Chain built: {} certificates (leaf={})", chain.size(),
                    leaf.getSubjectX500Principal());
        }

        // 4) Корень в truststore
        X509Certificate root = chain.get(chain.size() - 1);
        if (!isTrustedRoot(root, truststore)) {
            throw new IllegalStateException(
                    "Корневой сертификат не в truststore: " + root.getSubjectX500Principal());
        }
        // 5) Подписи звеньев
        verifyChainSignatures(chain);
        // 6) Срок действия всех звеньев
        for (X509Certificate c : chain) {
            try {
                c.checkValidity();
            } catch (java.security.cert.CertificateException e) {
                throw new IllegalStateException("Срок действия звена цепочки истёк", e);
            }
        }
        // 7) Отзыв по CRL — по политике revocation.mode / signature.require_crl
        checkNotRevoked(chain, crls, revocationPolicy());

        String subject = leaf.getSubjectX500Principal().getName();
        return new SignerMeta(
                extractCn(subject),
                leaf.getSerialNumber().toString(16),
                subject + "; serial=" + leaf.getSerialNumber().toString(16));
    }

    /**
     * Проверка подписи CMS.
     *
     * <p>ГОСТ 34.10-2012/2001 — только JCSP (сертифицированное СКЗИ).
     * Для остальных алгоритмов (RSA/EC, тесты и демо) допускается BC.</p>
     */
    private boolean verifySignerCrypto(SignerInformation signer, X509Certificate leaf, byte[] data) {
        boolean gost = isGostSigner(signer);
        String[] providers = signatureProviders(gost);
        Exception last = null;

        for (String provider : providers) {
            try {
                boolean ok = signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                        .setProvider(provider).build(leaf));
                if (ok) {
                    log.info("verifySignerCrypto: подпись проверена {} ({})",
                            provider, gost ? "ГОСТ" : "не-ГОСТ");
                    return true;
                }
            } catch (Exception e) {
                last = e;
                log.debug("verifySignerCrypto: {} verify failed: {}", provider, e.getMessage());
            }
        }

        if (tryManualVerify(signer, leaf, gost, data)) {
            log.info("verifySignerCrypto: подпись проверена ручным fallback ({})",
                    gost ? "только JCSP" : "BC/JCSP");
            return true;
        }

        String msg = gost
                ? "ГОСТ-подпись не проверена: сертифицированный провайдер JCSP недоступен "
                  + "или не поддерживает алгоритм"
                : "Криптопровайдер не поддерживает алгоритм подписи этого сертификата";
        log.warn("verifySignerCrypto: {}", msg);
        throw new CryptoNotSupportedException(msg, last);
    }

    private boolean tryManualVerify(SignerInformation signer, X509Certificate leaf,
                                    boolean gost, byte[] data) {
        try {
            // Ручная проверка подписывает только signedAttrs — без сверки digest
            // с содержимым она приняла бы .sig от другого документа.
            if (!checkSignedDigest(signer, data)) {
                log.debug("manual verify: digest не совпадает с содержимым — отказ");
                return false;
            }
            byte[] signedData = signedAttrsOrContent(signer);
            byte[] sigBytes = signer.getSignature();
            if (signedData == null || sigBytes == null) return false;

            String[] algos = {
                signer.getEncryptionAlgOID(),
                "1.2.643.7.1.1.3.2",   // GOST 2012-256
                "1.2.643.7.1.1.3.3",   // GOST 2012-512
                "1.2.643.2.2.3",       // GOST 2001
                // имена JCSP (КриптоПро)
                "GOST3411_2012_256withGOST3410_2012_256",
                "GOST3411_2012_512withGOST3410_2012_512",
                "GOST3411withGOST3410EL",
                "CryptoProSignature_2012_256",
                // имена BC (не-ГОСТ fallback/совместимость)
                "GOST3411-2012-256withECGOST3410-2012-256",
                "GOST3411-2012-512withECGOST3410-2012-512",
                "GOST3411-2012-256WITHECGOST3410-2012-256",
                "GOST3411withECGOST3410",
            };
            for (String alg : algos) {
                if (alg == null || alg.isBlank()) continue;
                boolean algGost = isGostAlgName(alg);
                if (algGost != gost) continue;
                for (String provider : signatureProviders(gost)) {
                    try {
                        var s = java.security.Signature.getInstance(alg, provider);
                        s.initVerify(leaf.getPublicKey());
                        s.update(signedData);
                        boolean ok = s.verify(sigBytes);
                        log.debug("manual verify: alg={}, provider={}, ok={}", alg, provider, ok);
                        return ok;
                    } catch (Exception ignored) { /* try next */ }
                }
            }
        } catch (Exception e) {
            log.debug("manual verify failed: {}", e.getMessage());
        }
        return false;
    }

    /** Signed attributes (DER) — то, что подписано. */
    private static byte[] signedAttrsOrContent(SignerInformation signer) {
        try {
            var attrs = signer.getSignedAttributes();
            if (attrs != null) {
                return attrs.toASN1Structure()
                        .getEncoded(org.bouncycastle.asn1.ASN1Encoding.DER);
            }
            // Без signed attributes content недоступен из SignerInformation
            // (он живёт в CMSProcessable и передаётся при конструировании CMSSignedData).
            // Для CAdES-BES signed attrs есть всегда; сюда не попадём.
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Быстрая, независимая от провайдера проверка: сравнивает digest
     * переданных данных с messageDigest-атрибутом CMS.
     *
     * @return TRUE — совпадает, FALSE — не совпадает, NULL — невозможно проверить.
     */
    private boolean checkSignedDigest(SignerInformation signer, byte[] data) {
        try {
            var attrs = signer.getSignedAttributes();
            if (attrs == null) return false;
            var mdAttr = attrs.get(org.bouncycastle.asn1.cms.CMSAttributes.messageDigest);
            if (mdAttr == null) return false;
            byte[] expected = ((org.bouncycastle.asn1.ASN1OctetString)
                    mdAttr.getAttrValues().getObjectAt(0)).getOctets();

            String[] algorithms = {
                // имена JCSP (КриптоПро)
                "GOST3411_2012_256", "GOST3411_2012_512", "GOST3411",
                // имена BC (только для не-ГОСТ fallback/совместимости)
                "GOST3411-2012-256", "GOST3411-2012-512",
                "SHA-256", "SHA-384", "SHA-512", "SHA-1"
            };
            boolean gostDigest = isGostOid(signer.getDigestAlgOID());
            for (String alg : algorithms) {
                boolean algGost = isGostAlgName(alg);
                if (algGost != gostDigest) continue;
                for (String provider : algGost ? new String[]{"JCSP"}
                                               : new String[]{"BC", "JCSP"}) {
                    try {
                        byte[] computed = java.security.MessageDigest
                                .getInstance(alg, provider).digest(data);
                        if (java.util.Arrays.equals(computed, expected)) return true;
                    } catch (Exception ignored) {
                        // провайдер не поддержал алгоритм — пробуем следующий
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("checkSignedDigest failed: {}", e.getMessage());
            return false;
        }
    }

    /** Извлекает все встроенные сертификаты из CMS (лист + промежуточные). */
    private X509Certificate getSignerCert(SignerInformation signer, CMSSignedData cms) throws java.security.cert.CertificateException {
        Store<X509CertificateHolder> certStore = cms.getCertificates();
        Collection<X509CertificateHolder> matches = certStore.getMatches(signer.getSID());
        if (matches.isEmpty()) {
            throw new IllegalStateException("Сертификат подписанта не найден в подписи");
        }
        return new JcaX509CertificateConverter()
                .getCertificate(matches.iterator().next());
    }

    private Set<X509Certificate> loadTrustedCerts() {
        Set<X509Certificate> result = new HashSet<>();
        if (!Files.isDirectory(certsDir)) {
            log.warn("truststore dir not found: {}", certsDir);
            return result;
        }
        try (Stream<Path> stream = Files.list(certsDir)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            stream.filter(Files::isRegularFile).forEach(f -> {
                String name = f.getFileName().toString().toLowerCase();
                if (!name.endsWith(".cer") && !name.endsWith(".crt") && !name.endsWith(".pem")) {
                    return;
                }
                try (InputStream is = Files.newInputStream(f)) {
                    // generateCertificates читает ВСЕ сертификаты из файла (PEM-бандлы, PKCS7)
                    java.util.Collection<? extends java.security.cert.Certificate> certs =
                            cf.generateCertificates(is);
                    int loaded = 0;
                    for (var c : certs) {
                        X509Certificate cert = (X509Certificate) c;
                        result.add(cert);
                        loaded++;
                        log.info("Truststore: loaded {} — Subject: {}",
                                f.getFileName(), cert.getSubjectX500Principal());
                    }
                    if (loaded == 0) {
                        log.warn("Truststore: в файле {} не найдено сертификатов", f.getFileName());
                    }
                } catch (Exception ex) {
                    log.warn("Truststore: FAILED to load {}: {}", f.getFileName(), ex.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("loadTrustedCerts error", e);
        }
        log.info("Truststore: {} certificates loaded from {}", result.size(), certsDir);
        return result;
    }

    /**
     * Загружает все *.crl из директории, дедуплицирует по issuer —
     * оставляет только самый свежий (по thisUpdate) CRL для каждого CA.
     */
    private Set<X509CRL> loadAllCrls(Set<X509Certificate> knownCerts) {
        if (!Files.isDirectory(crlsDir)) {
            log.warn("crls dir not found: {}", crlsDir);
            return new HashSet<>();
        }

        RevocationPolicy policy = revocationPolicy();
        boolean requireSignature = requireCrlSignature();
        java.util.Map<String, java.util.List<X509Certificate>> bySubject =
                new java.util.HashMap<>();
        for (X509Certificate cert : knownCerts) {
            bySubject.computeIfAbsent(cert.getSubjectX500Principal().getName(),
                    k -> new java.util.ArrayList<>()).add(cert);
        }

        // issuer → самый свежий CRL
        java.util.Map<String, X509CRL> byIssuer = new java.util.HashMap<>();
        int total = 0;
        int failed = 0;
        int skipped = 0;

        try (Stream<Path> stream = Files.list(crlsDir)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            java.util.List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".crl"))
                    .sorted()
                    .toList();
            for (Path f : files) {
                total++;
                LoadedCrl loaded = parseCrl(f, cf);
                if (loaded == null) {
                    failed++;
                    continue;
                }
                if (!checkCrlTrust(loaded, bySubject, requireSignature, policy)) {
                    skipped++;
                    continue;
                }
                putNewest(byIssuer, loaded.crl());
            }
        } catch (Exception e) {
            log.error("loadAllCrls error", e);
        }

        log.info("CRLs: {} файлов → {} уникальных issuer (failed={}, skipped={})",
                total, byIssuer.size(), failed, skipped);
        return new HashSet<>(byIssuer.values());
    }

    /** Разобранный CRL. */
    private record LoadedCrl(Path file, X509CRL crl) {
    }

    private LoadedCrl parseCrl(Path f, CertificateFactory cf) {
        try (InputStream is = Files.newInputStream(f)) {
            return new LoadedCrl(f, (X509CRL) cf.generateCRL(is));
        } catch (Exception e) {
            log.debug("CRL parse failed: {} — {}", f.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * Проверка подлинности CRL: подпись издателя и срок действия.
     * В soft-режиме непроверенный/устаревший CRL используется с предупреждением;
     * при {@code signature.require_crl_signature=true} он отбрасывается.
     */
    private boolean checkCrlTrust(LoadedCrl loaded,
                                  java.util.Map<String, java.util.List<X509Certificate>> bySubject,
                                  boolean requireSignature, RevocationPolicy policy) {
        X509CRL crl = loaded.crl();
        java.util.List<X509Certificate> issuers = bySubject.getOrDefault(
                crl.getIssuerX500Principal().getName(), java.util.List.of());
        if (issuers.isEmpty()) {
            log.warn("CRL {}: нет сертификата издателя (truststore/подпись) — подлинность не проверена",
                    loaded.file().getFileName());
            return !requireSignature;
        }
        if (!verifyCrlSignature(crl, issuers)) {
            log.warn("CRL {}: подпись не сходится с сертификатом издателя "
                            + "(нужен актуальный сертификат УЦ в truststore) — CRL не доверенный",
                    loaded.file().getFileName());
            return !requireSignature;
        }
        java.util.Date nextUpdate = crl.getNextUpdate();
        if (nextUpdate != null && nextUpdate.before(new java.util.Date())) {
            log.warn("CRL {}: истёк nextUpdate={} — CRL устарел",
                    loaded.file().getFileName(), nextUpdate);
            return policy != RevocationPolicy.STRICT;
        }
        return true;
    }

    private boolean verifyCrlSignature(X509CRL crl, java.util.List<X509Certificate> issuers) {
        boolean gost = isGostOid(crl.getSigAlgOID());
        for (X509Certificate issuer : issuers) {
            for (String provider : signatureProviders(gost)) {
                try {
                    crl.verify(issuer.getPublicKey(), provider);
                    log.info("CRL: подпись проверена {} ({}, serial={})",
                            provider, gost ? "ГОСТ" : "не-ГОСТ",
                            issuer.getSerialNumber().toString(16));
                    return true;
                } catch (Exception e) {
                    log.debug("CRL verify {} failed: {}", provider, e.getMessage());
                }
            }
        }
        return false;
    }

    private void putNewest(java.util.Map<String, X509CRL> byIssuer, X509CRL crl) {
        String issuer = crl.getIssuerX500Principal().getName();
        X509CRL existing = byIssuer.get(issuer);
        if (existing == null || crl.getThisUpdate().after(existing.getThisUpdate())) {
            byIssuer.put(issuer, crl);
        }
    }

    private boolean requireCrlSignature() {
        return requireCrlSignatureProperty
                || (settings != null
                && settings.getBoolOrDefault("signature.require_crl_signature", false));
    }

    /** Парсит один CRL-файл и кладёт в map (самый свежий по issuer). Возвращает true при успехе. */
    private boolean loadOneCrl(Path f, CertificateFactory cf,
                               java.util.Map<String, X509CRL> byIssuer) {
        try (InputStream is = Files.newInputStream(f)) {
            X509CRL crl = (X509CRL) cf.generateCRL(is);
            String issuer = crl.getIssuerX500Principal().getName();
            X509CRL existing = byIssuer.get(issuer);
            if (existing == null || crl.getThisUpdate().after(existing.getThisUpdate())) {
                byIssuer.put(issuer, crl);
            }
            return true;
        } catch (Exception e) {
            log.debug("CRL parse failed: {} — {}", f.getFileName(), e.getMessage());
            return false;
        }
    }

    private List<X509Certificate> buildChain(X509Certificate leaf, CMSSignedData cms,
                                              Set<X509Certificate> truststore) {
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(leaf);

        Set<X509Certificate> candidates = collectCandidates(cms);

        X509Certificate current = leaf;
        int maxDepth = 10;
        while (!isSelfSigned(current) && maxDepth-- > 0) {
            X509Certificate issuer = findIssuer(current, candidates, truststore);
            if (issuer == null) {
                throw new IllegalStateException(
                        "Не найден issuer для " + current.getSubjectX500Principal());
            }
            chain.add(issuer);
            current = issuer;
        }
        if (maxDepth <= 0) {
            throw new IllegalStateException("Цепочка слишком длинная (>10)");
        }
        return chain;
    }

    /** Все сертификаты из CMS в виде Set. Невалидные — тихо пропускаем. */
    private Set<X509Certificate> collectCandidates(CMSSignedData cms) {
        Set<X509Certificate> candidates = new HashSet<>();
        Store<X509CertificateHolder> certStore = cms.getCertificates();
        JcaX509CertificateConverter conv = new JcaX509CertificateConverter();
        for (X509CertificateHolder holder : certStore.getMatches(null)) {
            try {
                candidates.add(conv.getCertificate(holder));
            } catch (Exception ex) {
                log.debug(LOG_SKIP_CERT, ex.getMessage());
            }
        }
        return candidates;
    }

    /** Ищет issuer для cert: сначала в candidates (CMS), затем в truststore. */
    private X509Certificate findIssuer(X509Certificate cert,
                                        Set<X509Certificate> candidates,
                                        Set<X509Certificate> truststore) {
        String wantedIssuer = cert.getIssuerX500Principal().getName();
        String leafCn = extractCn(cert.getSubjectX500Principal().getName());
        log.info("findIssuer[{}]: wanted issuer DN = {}", leafCn, wantedIssuer);
        log.info("findIssuer[{}]: candidates CMS ({}):", leafCn, candidates.size());
        for (X509Certificate cand : candidates) {
            log.info("  CMS subject={}", cand.getSubjectX500Principal().getName());
        }
        if (truststore != null) {
            log.info("findIssuer[{}]: truststore ({}):", leafCn, truststore.size());
            for (X509Certificate cand : truststore) {
                log.info("  TRUST subject={}", cand.getSubjectX500Principal().getName());
            }
        }

        // 1) CMS
        for (X509Certificate candidate : candidates) {
            if (!cert.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                continue;
            }
            boolean ok = verifySignature(cert, candidate);
            log.info("findIssuer[{}]: DN match in CMS, verify={}", leafCn, ok);
            if (ok) return candidate;
        }
        // 2) fallback: truststore
        if (truststore != null) {
            for (X509Certificate candidate : truststore) {
                if (!cert.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                    continue;
                }
                boolean ok = verifySignature(cert, candidate);
                log.info("findIssuer[{}]: DN match in TRUSTSTORE, verify={}", leafCn, ok);
                if (ok) return candidate;
            }
        }
        log.warn("findIssuer[{}]: issuer NOT FOUND ни в CMS, ни в truststore", leafCn);
        return null;
    }

    private void verifyChainSignatures(List<X509Certificate> chain) {
        for (int i = 0; i < chain.size() - 1; i++) {
            X509Certificate cert = chain.get(i);
            X509Certificate issuer = chain.get(i + 1);
            if (!verifySignature(cert, issuer)) {
                throw new IllegalStateException(
                        "Подпись звена невалидна: " + cert.getSubjectX500Principal()
                        + " ← " + issuer.getSubjectX500Principal());
            }
        }
    }

    /**
     * Проверяет подпись leaf-сертификата его issuer'ом.
     * Пробует несколько имён алгоритмов × провайдеров — из-за разницы
     * регистрации ГОСТ-2012 в BC 1.78 и JCSP 5.0.
     */
    private boolean verifySignature(X509Certificate cert, X509Certificate issuer) {
        byte[] tbs = null;
        byte[] sig = null;
        try {
            tbs = cert.getTBSCertificate();
            sig = cert.getSignature();
        } catch (Exception e) {
            log.debug("verifySignature: getTBSCertificate failed: {}", e.getMessage());
        }

        String[] algos = {
                cert.getSigAlgName(),                       // как в cert
                // имена JCSP (КриптоПро)
                "GOST3411_2012_256withGOST3410_2012_256",
                "GOST3411_2012_512withGOST3410_2012_512",
                "GOST3411withGOST3410EL",
                "CryptoProSignature_2012_256",
                // имена BC (не-ГОСТ fallback/совместимость)
                "GOST3411-2012-256withECGOST3410-2012-256",
                "GOST3411-2012-512withECGOST3410-2012-512",
                "GOST3411-2012-256WITHECGOST3410-2012-256",  // uppercase WITH
                "GOST3411withECGOST3410",                   // 2001
                "ECGOST3410-2012",                          // fallback
        };
        boolean gostCert = isGostOid(cert.getSigAlgOID());
        String[] providers = signatureProviders(gostCert);

        // Попытка 1: cert.verify (стандартный API — берёт алгоритм из cert)
        for (String provider : providers) {
            try {
                cert.verify(issuer.getPublicKey(), provider);
                if (log.isInfoEnabled()) {
                    log.info("verifySignature: OK ({}): '{}' <- '{}'",
                            provider,
                            extractCn(cert.getSubjectX500Principal().getName()),
                            extractCn(issuer.getSubjectX500Principal().getName()));
                }
                return true;
            } catch (Exception e) {
                log.debug("verifySignature: cert.verify({}) failed: {}",
                        provider, e.getMessage());
            }
        }

        // Попытка 2: явный перебор имён алгоритмов (низкоуровневая)
        // Sonar fix (S2589): убрана избыточная проверка на null, так как ошибки ловит catch
        for (String alg : algos) {
            if (alg == null || alg.isBlank()) continue;
            boolean algGost = isGostAlgName(alg);
            if (algGost != gostCert) continue;
            for (String provider : providers) {
                try {
                    var s = java.security.Signature.getInstance(alg, provider);
                    s.initVerify(issuer.getPublicKey());
                    s.update(tbs);
                    if (s.verify(sig)) {
                        if (log.isInfoEnabled()) {
                            if (log.isInfoEnabled()) {
                                log.info("verifySignature: OK explicit (alg={}, provider={}): '{}' <- '{}'",
                                        alg, provider,
                                        extractCn(cert.getSubjectX500Principal().getName()),
                                        extractCn(issuer.getSubjectX500Principal().getName()));
                            }
                        }
                        return true;
                    }
                } catch (Exception ignored) {
                    // пробуем дальше
                }
            }
        }

        log.warn("verifySignature: ALL attempts failed for '{}' <- '{}'",
                extractCn(cert.getSubjectX500Principal().getName()),
                extractCn(issuer.getSubjectX500Principal().getName()));
        return false;
    }

    private boolean isSelfSigned(X509Certificate cert) {
        return cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
    }

    /** Аварийный флаг: разрешить доверие корню по DN (по умолчанию выключено). */
    private boolean allowRootDnMatch() {
        return settings != null
                && settings.getBoolOrDefault("signature.allow_root_dn_match", false);
    }

    private boolean isTrustedRoot(X509Certificate root, Set<X509Certificate> truststore) {
        byte[] rootFp = fingerprint(root);
        if (rootFp.length == 0) return false;

        String rootCn = extractCn(root.getSubjectX500Principal().getName());
        String rootSerial = root.getSerialNumber().toString(16);
        if (log.isInfoEnabled()) {
            log.info("isTrustedRoot: chain root CN={}, serial={}, fp={}",
                    rootCn, rootSerial, hex16(rootFp));
        }

        for (X509Certificate trusted : truststore) {
            byte[] trustedFp = fingerprint(trusted);
            String trustedCn = extractCn(trusted.getSubjectX500Principal().getName());
            String trustedSerial = trusted.getSerialNumber().toString(16);

            // 1) точное совпадение по SHA-256 — единственный доверенный вариант
            if (trustedFp.length > 0 && Arrays.equals(rootFp, trustedFp)) {
                log.info("isTrustedRoot: MATCH on fingerprint — {}", trustedCn);
                return true;
            }
            // 2) Тот же DN, но другой ключ: НЕ доверяем (иначе любой самоподписанный
            //    сертификат с тем же CN проходил бы проверку). Совместимость —
            //    только явным флагом signature.allow_root_dn_match=true.
            if (root.getSubjectX500Principal().equals(trusted.getSubjectX500Principal())
                    && root.getIssuerX500Principal().equals(trusted.getIssuerX500Principal())
                    && isSelfSigned(trusted)) {
                if (allowRootDnMatch()) {
                    log.warn("isTrustedRoot: MATCH by DN (fingerprint differs) — разрешено "
                                    + "signature.allow_root_dn_match=true. chain root serial={}, "
                                    + "trusted serial={}, CN={}",
                            rootSerial, trustedSerial, trustedCn);
                    return true;
                }
                log.warn("isTrustedRoot: DN совпадает, но отпечаток другой — проверяю дальше "
                                + "(chain root serial={}, trusted serial={}, CN={}). "
                                + "Добавьте актуальный корневой сертификат в truststore.",
                        rootSerial, trustedSerial, trustedCn);
            }
        }
        log.warn("isTrustedRoot: NOT TRUSTED — chain root CN={}, serial={} не найден в truststore ({} сертификатов)",
                rootCn, rootSerial, truststore.size());
        return false;
    }

    private static String hex16(byte[] fp) {
        if (fp == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(fp.length, 8); i++) {
            sb.append(String.format("%02x", fp[i]));
        }
        return sb.toString();
    }

    /**
     * SHA-256 отпечаток всего сертификата (getEncoded).
     * Надёжнее, чем сравнивать getPublicKey().getEncoded() для ГОСТ — у BC
     * бывает разная DER-кодировка SubjectPublicKeyInfo для одного ключа.
     */
    private static byte[] fingerprint(X509Certificate cert) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(cert.getEncoded());
        } catch (Exception e) {
            log.warn("Failed to compute fingerprint: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
     * Онлайн-проверка отзыва через CDP из сертификатов (как на Госуслугах).
     * Возвращает отчёт по каждому сертификату цепочки.
     */
    void checkNotRevoked(List<X509Certificate> chain, Set<X509CRL> crls,
                         RevocationPolicy policy) {
        if (policy == RevocationPolicy.OFF) {
            log.debug("revocation.mode=off — проверка отзыва пропущена");
            return;
        }
        for (int i = 0; i < chain.size() - 1; i++) {
            X509Certificate cert = chain.get(i);
            X509CRL matchingCrl = null;
            for (X509CRL crl : crls) {
                if (crl.getIssuerX500Principal().equals(cert.getIssuerX500Principal())) {
                    matchingCrl = crl;
                    break;
                }
            }
            if (matchingCrl == null) {
                if (policy == RevocationPolicy.STRICT) {
                    throw new IllegalStateException(
                            "Нет CRL для " + cert.getIssuerX500Principal()
                            + " — отзыв обязателен (revocation.mode=strict / signature.require_crl)");
                }
                log.warn("Нет CRL для {} — проверка отзыва пропущена (soft)",
                        cert.getIssuerX500Principal());
                continue;
            }
            X509CRLEntry entry = matchingCrl.getRevokedCertificate(cert.getSerialNumber());
            if (entry != null) {
                throw new IllegalStateException(
                        "Сертификат отозван: " + cert.getSubjectX500Principal()
                        + ", serial=" + cert.getSerialNumber().toString(16)
                        + ", reason=" + entry.getRevocationReason());
            }
        }
    }

    public String extractCn(String x500) {
        if (x500 == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("CN=([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(x500);
        return m.find() ? m.group(1).replace("\"", "").trim() : x500;
    }

    // ═══════════════════════════════════════════════════════
    //  Строгая проверка с пошаговым отчётом (как Госуслуги)
    // ═══════════════════════════════════════════════════════

    /**
     * Полная проверка подписи с возвратом списка проверок.
     * Проверяет: целостность, криптоподпись, срок leaf, цепочку,
     * корень в truststore, подписи звеньев, срок всех звеньев,
     * CRL, EKU.
     */
    /** Проверяет EKU: digitalSignature (0) или nonRepudiation (1). */
    /** SHA-1 отпечаток сертификата (hex uppercase, без разделителей). */
    /** Человекочитаемое keyUsage. */

    /**
     * Извлекает leaf-сертификат подписанта из CAdES-BES подписи.
     * Возвращает null, если подпись битая/не CMS.
     */
    public X509Certificate extractSignerCert(String signatureBase64) {
        if (signatureBase64 == null || signatureBase64.isBlank()) return null;
        try {
            byte[] bytes = decodeBase64Tolerant(signatureBase64);
            CMSSignedData cms = new CMSSignedData(bytes);
            SignerInformationStore store = cms.getSignerInfos();
            if (store.size() == 0) return null;
            SignerInformation signer = store.getSigners().iterator().next();
            Collection<X509CertificateHolder> matches = cms.getCertificates().getMatches(signer.getSID());
            if (matches.isEmpty()) return null;
            return new JcaX509CertificateConverter()
                    .getCertificate(matches.iterator().next());
        } catch (Exception e) {
            log.debug("extractSignerCert failed: {}", e.getMessage());
            return null;
        }
    }
}
