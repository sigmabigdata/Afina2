package ru.afina.gosklyuch;

import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Подписант пакета Госключа из PKCS#12-файла с ключом УКЭП организации.
 *
 * <p>Криптографическая операция выполняется сертифицированным провайдером:
 * для ГОСТ — JCSP, для RSA/EC — JCA по умолчанию. BouncyCastle используется
 * только для сборки CMS-контейнера (структура PKCS#7).</p>
 *
 * <p>Включается конфигурацией: {@code app.gosklyuch.signer=pkcs12},
 * {@code GOSKLYUCH_P12_PATH}, {@code GOSKLYUCH_P12_PASSWORD}
 * (опционально alias и провайдер).</p>
 */
public class Pkcs12GosklyuchSigner implements GosklyuchSigner {

    private final PrivateKey privateKey;
    private final List<X509Certificate> chain;
    private final String provider;
    private final List<String> algorithms;

    public Pkcs12GosklyuchSigner(GosklyuchProperties properties) {
        String path = properties.getP12Path();
        if (path == null || path.isBlank()) {
            throw new GosklyuchUnavailableException(
                    "Не задан путь к PKCS#12 для подписи пакета (GOSKLYUCH_P12_PATH)");
        }
        Path file = Path.of(path);
        if (!Files.isReadable(file)) {
            throw new GosklyuchUnavailableException(
                    "PKCS#12 недоступен для чтения: " + path);
        }
        char[] password = properties.getP12Password() == null
                ? new char[0] : properties.getP12Password().toCharArray();
        try {
            KeyStore ks = loadKeyStore(file, password);
            String alias = resolveAlias(ks, properties.getP12Alias());
            Key key = ks.getKey(alias, password);
            if (!(key instanceof PrivateKey pk)) {
                throw new GosklyuchUnavailableException(
                        "В PKCS#12 alias '" + alias + "' не содержит закрытого ключа");
            }
            Certificate[] certs = ks.getCertificateChain(alias);
            if (certs == null || certs.length == 0) {
                throw new GosklyuchUnavailableException(
                        "В PKCS#12 нет цепочки сертификатов для alias '" + alias + "'");
            }
            this.privateKey = pk;
            this.chain = Arrays.stream(certs)
                    .filter(X509Certificate.class::isInstance)
                    .map(X509Certificate.class::cast)
                    .toList();
            this.provider = resolveProvider(properties.getP12Provider(), pk.getAlgorithm());
            this.algorithms = algorithmsFor(pk.getAlgorithm());
        } catch (GosklyuchUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new GosklyuchUnavailableException(
                    "Не удалось загрузить PKCS#12: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] signDetached(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Пустой файл для подписи: " + fileName);
        }
        Exception last = null;
        for (String alg : algorithms) {
            try {
                CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
                ContentSigner signer = contentSigner(alg);
                var digestProvider = new JcaDigestCalculatorProviderBuilder();
                if (provider != null) digestProvider.setProvider(provider);
                generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                        digestProvider.build()).build(signer, chain.get(0)));
                generator.addCertificates(new JcaCertStore(chain));
                return generator.generate(new CMSProcessableByteArray(content), false).getEncoded();
            } catch (Exception e) {
                last = e;
            }
        }
        throw new GosklyuchUnavailableException(
                "Не удалось подписать пакет PKCS#12 (алгоритмы: " + algorithms
                        + ", провайдер: " + provider + "): "
                        + (last != null ? last.getMessage() : "нет попыток"), last);
    }

    private ContentSigner contentSigner(String alg) throws Exception {
        var builder = new JcaContentSignerBuilder(alg);
        if (provider != null) builder.setProvider(provider);
        return builder.build(privateKey);
    }

    private static KeyStore loadKeyStore(Path file, char[] password) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            if (isProviderAvailable("JCSP")) {
                try {
                    KeyStore ks = KeyStore.getInstance("PKCS12", "JCSP");
                    ks.load(in, password);
                    return ks;
                } catch (Exception ignored) {
                    // ГОСТ-контейнер не подошёл — пробуем стандартный JDK
                }
            }
        }
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            return ks;
        }
    }

    private static boolean isProviderAvailable(String name) {
        return java.security.Security.getProvider(name) != null;
    }

    private static String resolveAlias(KeyStore ks, String configured) throws Exception {
        if (configured != null && !configured.isBlank()) return configured;
        var aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (ks.isKeyEntry(alias)) return alias;
        }
        throw new GosklyuchUnavailableException("В PKCS#12 нет ключевых записей");
    }

    static String resolveProvider(String configured, String keyAlgorithm) {
        if (configured != null && !configured.isBlank()) return configured;
        return isGost(keyAlgorithm) ? "JCSP" : null;
    }

    static List<String> algorithmsFor(String keyAlgorithm) {
        String alg = keyAlgorithm == null ? "" : keyAlgorithm.toUpperCase();
        List<String> result = new ArrayList<>();
        if (isGost(alg)) {
            result.add("GOST3411_2012_256withGOST3410_2012_256");
            result.add("GOST3411_2012_512withGOST3410_2012_512");
            result.add("GOST3411withGOST3410EL");
        } else if (alg.contains("EC")) {
            result.add("SHA256withECDSA");
        } else {
            result.add("SHA256withRSA");
        }
        return result;
    }

    private static boolean isGost(String algorithm) {
        String a = algorithm == null ? "" : algorithm.toUpperCase();
        return a.contains("GOST") || a.contains("3410");
    }
}
