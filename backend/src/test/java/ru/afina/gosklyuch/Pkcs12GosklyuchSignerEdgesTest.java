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

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Граничные случаи {@link Pkcs12GosklyuchSigner}: пустой/нечитаемый путь,
 * неверный пароль, отсутствующий алиас, алиас без закрытого ключа,
 * автоматический выбор алиаса, пустой пароль контейнера, неизвестный
 * криптопровайдер и пустое содержимое для подписи.
 *
 * <p>Все PKCS#12-файлы генерируются на лету в {@link TempDir} через
 * стандартный JDK KeyStore — сеть и внешние носители не нужны.</p>
 */
@DisplayName("Госключ: граничные случаи PKCS#12-подписанта")
class Pkcs12GosklyuchSignerEdgesTest {

    private static final String ALIAS = "signer";
    private static final String PASSWORD = "secret";

    @TempDir
    Path tempDir;

    private static KeyPair keyPair;
    private static X509Certificate certificate;

    @BeforeAll
    static void generateKeyAndCertificate() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        X500Name dn = new X500Name("CN=ООО Тест, O=Afina Test, C=RU");
        certificate = new JcaX509CertificateConverter().setProvider("BC").getCertificate(
                new JcaX509v3CertificateBuilder(dn, BigInteger.valueOf(now),
                        new Date(now - 1000), new Date(now + 86_400_000L), dn,
                        keyPair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA")
                                .setProvider("BC").build(keyPair.getPrivate())));
    }

    // ──────────────────────────── вспомогательное ────────────────────────────

    /** PKCS#12 с ключевой записью и сертификатом. */
    private Path writeKeyStore(String alias, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias, keyPair.getPrivate(), password.toCharArray(),
                new Certificate[]{certificate});
        return store(ks, password);
    }

    /** PKCS#12 только с сертификатом (без закрытого ключа). */
    private Path writeCertificateOnlyStore(String alias, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry(alias, certificate);
        return store(ks, password);
    }

    private Path store(KeyStore ks, String password) throws Exception {
        Path file = tempDir.resolve("ks-" + UUID.randomUUID() + ".p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            ks.store(out, password.toCharArray());
        }
        return file;
    }

    private static GosklyuchProperties properties(Path file, String password) {
        GosklyuchProperties props = new GosklyuchProperties();
        props.setP12Path(file.toString());
        props.setP12Password(password);
        return props;
    }

    // ════════════════════════════ путь и файл ════════════════════════════

    @Test
    @DisplayName("Пустой и null путь к контейнеру → понятная ошибка")
    void rejectsBlankPath() {
        // по умолчанию путь пустой
        assertThatThrownBy(() -> new Pkcs12GosklyuchSigner(new GosklyuchProperties()))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("Не задан путь к PKCS#12");

        GosklyuchProperties nullPath = new GosklyuchProperties();
        nullPath.setP12Path(null);
        assertThatThrownBy(() -> new Pkcs12GosklyuchSigner(nullPath))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("Не задан путь к PKCS#12");

        GosklyuchProperties blankPath = new GosklyuchProperties();
        blankPath.setP12Path("   ");
        assertThatThrownBy(() -> new Pkcs12GosklyuchSigner(blankPath))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("Не задан путь к PKCS#12");
    }

    @Test
    @DisplayName("Нечитаемый файл контейнера → понятная ошибка")
    void rejectsUnreadableFile() {
        GosklyuchProperties props = new GosklyuchProperties();
        props.setP12Path(tempDir.resolve("no-such-file.p12").toString());

        assertThatThrownBy(() -> new Pkcs12GosklyuchSigner(props))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("недоступен для чтения");
    }

    // ════════════════════════════ пароль и алиас ════════════════════════════

    @Test
    @DisplayName("Неверный пароль контейнера → понятная ошибка загрузки")
    void rejectsWrongPassword() throws Exception {
        Path p12 = writeKeyStore(ALIAS, PASSWORD);

        assertThatThrownBy(() -> new Pkcs12GosklyuchSigner(properties(p12, "wrong-password")))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("Не удалось загрузить PKCS#12");
    }

    @Test
    @DisplayName("Отсутствующий алиас → понятная ошибка, без NPE")
    void rejectsUnknownAlias() throws Exception {
        Path p12 = writeKeyStore(ALIAS, PASSWORD);
        GosklyuchProperties props = properties(p12, PASSWORD);
        props.setP12Alias("no-such-alias");

        assertThatThrownBy(() -> new Pkcs12GosklyuchSigner(props))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("PKCS#12");
    }

    @Test
    @DisplayName("Алиас указывает на сертификат без закрытого ключа → отказ")
    void rejectsAliasWithoutPrivateKey() throws Exception {
        Path p12 = writeCertificateOnlyStore("certonly", PASSWORD);
        GosklyuchProperties props = properties(p12, PASSWORD);
        props.setP12Alias("certonly");

        assertThatThrownBy(() -> new Pkcs12GosklyuchSigner(props))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("не содержит закрытого ключа");
    }

    @Test
    @DisplayName("Контейнер без ключевых записей и без алиаса → отказ")
    void rejectsStoreWithoutKeyEntries() throws Exception {
        Path p12 = writeCertificateOnlyStore("certonly", PASSWORD);

        assertThatThrownBy(() -> new Pkcs12GosklyuchSigner(properties(p12, PASSWORD)))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("нет ключевых записей");
    }

    @Test
    @DisplayName("Алиас не задан → выбирается первая ключевая запись")
    void resolvesAliasAutomatically() throws Exception {
        Path p12 = writeKeyStore(ALIAS, PASSWORD);
        GosklyuchProperties props = properties(p12, PASSWORD);
        props.setP12Alias("");

        Pkcs12GosklyuchSigner signer = new Pkcs12GosklyuchSigner(props);

        assertThat(signer.signDetached("req.xml", "данные".getBytes())).isNotEmpty();
    }

    @Test
    @DisplayName("Пустой пароль контейнера (null в настройках) поддерживается")
    void supportsEmptyPassword() throws Exception {
        Path p12 = writeKeyStore(ALIAS, "");
        GosklyuchProperties props = properties(p12, null);

        Pkcs12GosklyuchSigner signer = new Pkcs12GosklyuchSigner(props);

        assertThat(signer.signDetached("req.xml", "данные".getBytes())).isNotEmpty();
    }

    // ════════════════════════════ подпись ════════════════════════════

    @Test
    @DisplayName("Пустое и null содержимое для подписи → IllegalArgumentException")
    void rejectsEmptyContent() throws Exception {
        Pkcs12GosklyuchSigner signer = new Pkcs12GosklyuchSigner(
                properties(writeKeyStore(ALIAS, PASSWORD), PASSWORD));

        assertThatThrownBy(() -> signer.signDetached("req.xml", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Пустой файл для подписи");
        assertThatThrownBy(() -> signer.signDetached("req.xml", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Пустой файл для подписи");
    }

    @Test
    @DisplayName("Указан явный провайдер BC → подпись собирается")
    void signsWithExplicitProvider() throws Exception {
        GosklyuchProperties props = properties(writeKeyStore(ALIAS, PASSWORD), PASSWORD);
        props.setP12Provider("BC");

        Pkcs12GosklyuchSigner signer = new Pkcs12GosklyuchSigner(props);
        byte[] signature = signer.signDetached("req.xml", "содержимое".getBytes());

        assertThat(signature).isNotEmpty();
    }

    @Test
    @DisplayName("Неизвестный криптопровайдер → все алгоритмы падают с понятной ошибкой")
    void rejectsUnknownProvider() throws Exception {
        GosklyuchProperties props = properties(writeKeyStore(ALIAS, PASSWORD), PASSWORD);
        props.setP12Provider("НетТакогоПровайдера");

        Pkcs12GosklyuchSigner signer = new Pkcs12GosklyuchSigner(props);

        assertThatThrownBy(() -> signer.signDetached("req.xml", "содержимое".getBytes()))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("Не удалось подписать пакет PKCS#12")
                .hasMessageContaining("НетТакогоПровайдера");
    }

    // ════════════════════════════ выбор алгоритмов ════════════════════════════

    @Test
    @DisplayName("Алгоритмы и провайдер для неизвестного/пустого типа ключа → RSA по умолчанию")
    void defaultsToRsaForUnknownKeyType() {
        assertThat(Pkcs12GosklyuchSigner.algorithmsFor(null))
                .containsExactly("SHA256withRSA");
        assertThat(Pkcs12GosklyuchSigner.algorithmsFor(""))
                .containsExactly("SHA256withRSA");
        assertThat(Pkcs12GosklyuchSigner.resolveProvider(null, null)).isNull();
        assertThat(Pkcs12GosklyuchSigner.resolveProvider("  ", "EC")).isNull();
    }
}
