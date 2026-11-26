package ru.afina.service;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Управление TLS-сертификатом для Angie.
 *
 * <p>Поддерживаются:
 * <ul>
 *   <li>коммерческие сертификаты RSA/ECDSA в PEM (fullchain.pem: лист + промежуточные);</li>
 *   <li>приватные ключи PKCS#8 ({@code BEGIN PRIVATE KEY}) и PKCS#1
 *       ({@code BEGIN RSA PRIVATE KEY}) / SEC1 ({@code BEGIN EC PRIVATE KEY}) — как их
 *       обычно отдают удостоверяющие центры и openssl;</li>
 *   <li>сертификаты ГОСТ (если в JVM доступен провайдер КриптоПро/BC ГОСТ).</li>
 * </ul>
 * Не поддерживаются ключи с паролем ({@code BEGIN ENCRYPTED PRIVATE KEY}) — их нужно
 * расшифровать перед загрузкой.
 */
@Service
public class SslCertificateService {

    private static final Logger log = LoggerFactory.getLogger(SslCertificateService.class);

    private static final String CERT_FILE = "fullchain.pem";
    private static final String KEY_FILE = "privkey.pem";
    private static final String CERT_BAK = "fullchain.pem.bak";
    private static final String ACME_LINE = "acme letsencrypt;";
    private static final String ACME_LINE_COMMENTED = "# acme letsencrypt;";
    private static final String ACME_CERT_DIRECTIVE = "ssl_certificate     $acme_cert_letsencrypt;";
    private static final String ACME_KEY_DIRECTIVE = "ssl_certificate_key $acme_cert_key_letsencrypt;";
    private static final String CUSTOM_CERT_PREFIX = "ssl_certificate     /etc/angie/certs/";
    private static final String CUSTOM_KEY_PREFIX = "ssl_certificate_key /etc/angie/certs/";
    private static final String KEY_BAK = "privkey.pem.bak";

    static {    }

    @Value("${app.angie-certs-path:/opt/afinaz/angie-certs}")
    private String angieCertsPath;

    @Value("${app.angie-conf-path:/opt/afinaz/angie.conf}")
    private String angieConfPath;

    /** Безопасный PATH для ProcessBuilder (sonar S4036). */
    @Value("${app.safe-path:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}")
    private String safePath;

    private ProcessBuilder safePb(String... cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().put("PATH", safePath);
        return pb;
    }

    public record ValidationResult(boolean valid, String error, String subject, String issuer,
                                   String notAfter, int chainCount) {
        public static ValidationResult ok(String subject, String issuer, String notAfter, int chainCount) {
            return new ValidationResult(true, null, subject, issuer, notAfter, chainCount);
        }
        public static ValidationResult fail(String error) {
            return new ValidationResult(false, error, null, null, null, 0);
        }
    }

    /**
     * Проверка загружаемых файлов:
     * <ul>
     *   <li>ключ разбирается (PKCS#8 / PKCS#1 / EC);</li>
     *   <li>ключ соответствует листовому сертификату;</li>
     *   <li>срок действия листа актуален.</li>
     * </ul>
     * Возвращает число сертификатов в цепочке (1 = только лист, без промежуточных).
     */
    public ValidationResult validate(String certPem, String keyPem) {
        try {
            List<X509Certificate> chain = parseCertificates(certPem);
            if (chain.isEmpty()) {
                return ValidationResult.fail("В файле нет сертификатов (BEGIN CERTIFICATE)");
            }
            X509Certificate leaf = chain.get(0);
            PrivateKey privateKey = parsePrivateKey(keyPem);
            if (!keysMatch(leaf, privateKey)) {
                return ValidationResult.fail("Приватный ключ не соответствует сертификату");
            }
            leaf.checkValidity();
            if (chain.size() == 1) {
                log.warn("SSL: загружен только листовой сертификат без промежуточных — "
                        + "некоторые браузеры могут не построить цепочку");
            }
            return ValidationResult.ok(
                    leaf.getSubjectX500Principal().getName(),
                    leaf.getIssuerX500Principal().getName(),
                    leaf.getNotAfter().toInstant().toString(),
                    chain.size());
        } catch (java.security.cert.CertificateExpiredException e) {
            return ValidationResult.fail("Срок действия сертификата истёк");
        } catch (java.security.cert.CertificateNotYetValidException e) {
            return ValidationResult.fail("Сертификат ещё не вступил в силу");
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail(e.getMessage());
        } catch (Exception e) {
            return ValidationResult.fail("Ошибка разбора: " + e.getMessage());
        }
    }

    /** Все сертификаты из PEM (лист и промежуточные), в порядке файла. */
    @SuppressWarnings("java:S112") // внутренний хелпер: спектр исключений широк
    private List<X509Certificate> parseCertificates(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends java.security.cert.Certificate> parsed =
                cf.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        List<X509Certificate> out = new ArrayList<>();
        for (var c : parsed) {
            out.add((X509Certificate) c);
        }
        return out;
    }

    /**
     * Разбор приватного ключа. Понимает PKCS#8 ({@code BEGIN PRIVATE KEY}),
     * PKCS#1 RSA ({@code BEGIN RSA PRIVATE KEY}) и SEC1 EC ({@code BEGIN EC PRIVATE KEY}).
     */
    @SuppressWarnings("java:S112") // внутренний хелпер: спектр исключений широк
    private PrivateKey parsePrivateKey(String pem) throws Exception {
        String trimmed = pem.trim();
        if (trimmed.contains("ENCRYPTED PRIVATE KEY")) {
            throw new IllegalArgumentException(
                    "Приватный ключ защищён паролем. Расшифруйте его и загрузите без пароля: "
                    + "openssl pkey -in key.pem -out key-plain.pem");
        }

        // 1) BouncyCastle PEMParser: понимает PKCS#1/PKCS#8/EC
        try (PEMParser parser = new PEMParser(new StringReader(trimmed))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter conv = new JcaPEMKeyConverter();
            if (obj instanceof PEMKeyPair kp) {
                return conv.getPrivateKey(kp.getPrivateKeyInfo());
            }
            if (obj instanceof PrivateKeyInfo pki) {
                return conv.getPrivateKey(pki);
            }
        } catch (Exception e) {
            log.debug("PEMParser не разобрал ключ: {}", e.getMessage());
        }

        // 2) Фолбэк: DER PKCS#8 без PEM-заголовков
        String base64 = pem
                .replaceAll("-----BEGIN [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("-----END [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Некорректный PEM-ключ: не удалось декодировать base64");
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String alg : new String[]{"RSA", "EC", "DSA", "GOST3410_2012_256", "GOST3410"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (Exception ignored) {
                // пробуем следующий алгоритм
            }
        }
        throw new IllegalArgumentException(
                "Не удалось определить алгоритм приватного ключа (ожидается RSA/EC). "
                + "Если ключ в формате PKCS#1, сконвертируйте: "
                + "openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem");
    }

    private boolean keysMatch(X509Certificate cert, PrivateKey privateKey) {
        String alg = cert.getPublicKey().getAlgorithm();
        List<String> candidates = new ArrayList<>();
        switch (alg) {
            case "RSA" -> candidates.add("SHA256withRSA");
            case "EC", "ECDSA" -> candidates.add("SHA256withECDSA");
            case "DSA" -> candidates.add("SHA256withDSA");
            default -> {
                candidates.add("SHA256with" + alg);
                // ГОСТ 2012: имена алгоритмов у BC и КриптоПро различаются
                candidates.add("GOST3411withGOST3410_2012_256");
                candidates.add("GOST3411withGOST3410_2012_512");
                candidates.add("GOST3411withGOST3410");
            }
        }
        byte[] testData = "afina-key-match-test".getBytes(StandardCharsets.UTF_8);
        for (String sigAlg : candidates) {
            try {
                Signature sig = Signature.getInstance(sigAlg);
                sig.initSign(privateKey);
                sig.update(testData);
                byte[] signature = sig.sign();
                sig.initVerify(cert.getPublicKey());
                sig.update(testData);
                if (sig.verify(signature)) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("keysMatch: алгоритм {} не сработал: {}", sigAlg, e.getMessage());
            }
        }
        log.warn("keysMatch: не удалось подтвердить соответствие ключа сертификату (alg={})", alg);
        return false;
    }

    /**
     * Сохраняет сертификат и ключ, переключает angie.conf на них и делает резервную
     * копию предыдущего сертификата (чтобы delete мог вернуть self-signed).
     */
    public void save(String certPem, String keyPem) throws IOException {
        Path dir = Paths.get(angieCertsPath);
        Files.createDirectories(dir);
        Path certPath = dir.resolve(CERT_FILE);
        Path keyPath = dir.resolve(KEY_FILE);
        Path certBak = dir.resolve(CERT_BAK);
        Path keyBak = dir.resolve(KEY_BAK);

        // Резервная копия исходного (self-signed) сертификата — только один раз
        if (Files.exists(certPath) && !Files.exists(certBak)) {
            Files.copy(certPath, certBak, StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(keyPath)) {
                Files.copy(keyPath, keyBak, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("SSL: сохранена резервная копия предыдущего сертификата");
        }

        String normalizedChain = normalizeChain(certPem, keyPem);
        Files.writeString(certPath, normalizedChain, StandardCharsets.UTF_8);
        Files.writeString(keyPath, keyPem, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(keyPath, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // не POSIX-ФС
        }

        switchConfToCustomCert();

        log.info("SSL certificate saved to {}", certPath);
    }

    /**
     * Приводит цепочку к порядку «лист → промежуточные», как требует Angie:
     * если пользователь загрузил цепочку в обратном порядке, сертификат,
     * соответствующий ключу, ставится первым.
     */
    private String normalizeChain(String certPem, String keyPem) {
        try {
            List<X509Certificate> chain = parseCertificates(certPem);
            if (chain.size() <= 1) {
                return certPem;
            }
            PrivateKey key = parsePrivateKey(keyPem);
            int leafIdx = -1;
            for (int i = 0; i < chain.size(); i++) {
                if (keysMatch(chain.get(i), key)) {
                    leafIdx = i;
                    break;
                }
            }
            if (leafIdx <= 0) {
                return certPem;
            }
            List<X509Certificate> reordered = new ArrayList<>();
            reordered.add(chain.get(leafIdx));
            for (int i = 0; i < chain.size(); i++) {
                if (i != leafIdx) reordered.add(chain.get(i));
            }
            StringBuilder sb = new StringBuilder();
            for (X509Certificate c : reordered) {
                sb.append("-----BEGIN CERTIFICATE-----\n")
                        .append(Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                                .encodeToString(c.getEncoded()))
                        .append("\n-----END CERTIFICATE-----\n");
            }
            log.info("SSL: порядок цепочки нормализован (лист первым)");
            return sb.toString();
        } catch (Exception e) {
            log.warn("SSL: не удалось нормализовать цепочку: {}", e.getMessage());
            return certPem;
        }
    }

    /** Файл, который include-ит angie.conf — им управляет приложение (владелец uid 999). */
    private Path tlsConfPath() {
        return Paths.get(angieCertsPath).resolve("tls.conf");
    }

    private String tlsConfContent(boolean acme) {
        if (acme) {
            return ACME_LINE + "\n"
                    + ACME_CERT_DIRECTIVE + "\n"
                    + ACME_KEY_DIRECTIVE + "\n";
        }
        return CUSTOM_CERT_PREFIX + CERT_FILE + ";\n"
                + CUSTOM_KEY_PREFIX + KEY_FILE + ";\n";
    }

    private void writeTlsConf(boolean acme) throws IOException {
        Path tlsConf = tlsConfPath();
        Files.createDirectories(tlsConf.getParent());
        Files.writeString(tlsConf, tlsConfContent(acme), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tlsConf, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException ignored) {
            // не POSIX-ФС
        }
        log.info("SSL: tls.conf переключён на {}", acme ? "ACME" : "файлы angie-certs");
    }

    private String angieConfContent() {
        try {
            Path conf = Paths.get(angieConfPath);
            return Files.exists(conf) ? Files.readString(conf, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** angie.conf подключает tls.conf (новый способ управления сертификатом). */
    private boolean usesTlsInclude() {
        return angieConfContent().contains("include /etc/angie/certs/tls.conf;");
    }

    /** В angie.conf настроен ACME-клиент (Let's Encrypt доступен). */
    private boolean hasAcmeClient() {
        return angieConfContent().contains("acme_client");
    }

    /**
     * Переключает Angie на файлы в angie-certs.
     *
     * <p>Основной путь — записать tls.conf (файл принадлежит uid приложения).
     * Legacy-путь (angie.conf без include) требует прав на angie.conf — если их нет,
     * бросаем ошибку, чтобы UI не показал ложный успех.
     */
    private void switchConfToCustomCert() throws IOException {
        if (usesTlsInclude()) {
            writeTlsConf(false);
            return;
        }
        String conf = angieConfContent();
        if (conf.contains("/etc/angie/certs/" + CERT_FILE) && !conf.contains("$acme_cert_letsencrypt")) {
            log.debug("angie.conf уже указывает на файлы angie-certs");
            return;
        }
        String updated = conf
                .replace(ACME_LINE, ACME_LINE_COMMENTED)
                .replace(ACME_CERT_DIRECTIVE,
                        CUSTOM_CERT_PREFIX + CERT_FILE + ";")
                .replace(ACME_KEY_DIRECTIVE,
                        CUSTOM_KEY_PREFIX + KEY_FILE + ";");
        if (updated.equals(conf)) {
            throw new IOException("Не удалось переключить Angie на сертификат: в angie.conf нет "
                    + "include /etc/angie/certs/tls.conf; и нет директив ACME для замены.");
        }
        try {
            Files.writeString(Paths.get(angieConfPath), updated, StandardCharsets.UTF_8);
            log.info("angie.conf переключён на кастомный сертификат (legacy-путь)");
        } catch (IOException e) {
            throw new IOException("Нет прав на запись " + angieConfPath
                    + ". Добавьте в angie.conf строку: include /etc/angie/certs/tls.conf;", e);
        }
    }

    /**
     * Удаление кастомного сертификата.
     *
     * <p>Если настроен ACME — переключаем tls.conf обратно на ACME и удаляем файлы.
     * Если ACME нет, восстанавливаем резервную копию (self-signed).
     * Если ни того, ни другого — <b>не удаляем</b>: иначе Angie останется без TLS.
     */
    public void delete() throws IOException {
        Path dir = Paths.get(angieCertsPath);
        Path certPath = dir.resolve(CERT_FILE);
        Path keyPath = dir.resolve(KEY_FILE);

        if (hasAcmeClient()) {
            if (usesTlsInclude()) {
                writeTlsConf(true);
            } else {
                Files.writeString(Paths.get(angieConfPath),
                        angieConfContent()
                                .replace(ACME_LINE_COMMENTED, ACME_LINE)
                                .replace(CUSTOM_CERT_PREFIX + CERT_FILE + ";",
                                        ACME_CERT_DIRECTIVE)
                                .replace(CUSTOM_KEY_PREFIX + KEY_FILE + ";",
                                        ACME_KEY_DIRECTIVE),
                        StandardCharsets.UTF_8);
            }
            Files.deleteIfExists(certPath);
            Files.deleteIfExists(keyPath);
            log.info("SSL certificate deleted, Angie возвращён на ACME");
            return;
        }

        Path certBak = dir.resolve(CERT_BAK);
        Path keyBak = dir.resolve(KEY_BAK);
        if (Files.exists(certBak) && Files.exists(keyBak)) {
            Files.copy(certBak, certPath, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(keyBak, keyPath, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setPosixFilePermissions(keyPath, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // не POSIX-ФС
            }
            if (usesTlsInclude()) {
                writeTlsConf(false);
            }
            log.info("SSL: восстановлен предыдущий (self-signed) сертификат");
            return;
        }

        throw new IllegalStateException(
                "ACME в этом окружении не настроен, резервного сертификата нет — "
                + "удаление оставит Angie без TLS. Загрузите новый сертификат вместо удаления.");
    }

    /**
     * Откат на предыдущий сертификат (если новый не дал Angie подняться).
     * Возвращает true, если откат выполнен.
     */
    public boolean rollback() {
        try {
            Path dir = Paths.get(angieCertsPath);
            Path certBak = dir.resolve(CERT_BAK);
            Path keyBak = dir.resolve(KEY_BAK);
            if (Files.exists(certBak) && Files.exists(keyBak)) {
                Files.copy(certBak, dir.resolve(CERT_FILE), StandardCopyOption.REPLACE_EXISTING);
                Files.copy(keyBak, dir.resolve(KEY_FILE), StandardCopyOption.REPLACE_EXISTING);
                writeTlsConf(false);
                log.warn("SSL: выполнен откат на предыдущий сертификат");
                return true;
            }
            if (hasAcmeClient()) {
                writeTlsConf(true);
                Files.deleteIfExists(dir.resolve(CERT_FILE));
                Files.deleteIfExists(dir.resolve(KEY_FILE));
                log.warn("SSL: выполнен откат на ACME");
                return true;
            }
        } catch (Exception e) {
            log.error("SSL: откат не удался: {}", e.getMessage(), e);
        }
        return false;
    }

    /**
     * Перезапуск Angie через Docker API по unix-сокету.
     *
     * <p>В образе приложения нет docker CLI, поэтому используем curl (он есть) —
     * как и CleanupService. Если curl недоступен, пробуем docker CLI.
     */
    public void restartAngie() throws IOException, InterruptedException {
        String container = "afina-angie";
        boolean viaApi = false;
        try {
            String out = curlDockerApi("-X", "POST",
                    "/containers/" + container + "/restart?t=15");
            viaApi = true;
            log.info("Angie restart via docker API: {}", out.isBlank() ? "ok" : out);
        } catch (Exception e) {
            log.warn("Docker API недоступен ({}), пробую docker CLI", e.getMessage());
        }

        if (!viaApi) {
            ProcessBuilder pb = safePb("docker", "restart", container);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IOException("docker restart вернул код " + exit);
            }
        }

        for (int i = 0; i < 20; i++) {
            Thread.sleep(500);
            if (isContainerRunning(container)) {
                log.info("Angie restarted and is running");
                return;
            }
        }
        throw new IOException("Angie не запустился после перезапуска — проверь логи");
    }

    private boolean isContainerRunning(String container) {
        try {
            String json = curlDockerApi("/containers/" + container + "/json");
            return json.contains("\"Running\":true") || json.contains("\"Running\": true");
        } catch (Exception e) {
            log.debug("docker inspect через API не удался: {}", e.getMessage());
            return false;
        }
    }

    /** Вызов Docker API через unix-сокет (curl), как в CleanupService. */
    @SuppressWarnings("java:S112") // спектр исключений широк намеренно
    private String curlDockerApi(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("curl", "-s", "-S", "--max-time", "20",
                "--unix-socket", "/var/run/docker.sock"));
        cmd.addAll(List.of(args));
        cmd.add("http://localhost");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().put("PATH", safePath);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IOException("curl docker.sock rc=" + rc + ": " + out.trim());
        }
        return out;
    }

    public boolean hasCertificate() {
        return Files.isRegularFile(Paths.get(angieCertsPath, CERT_FILE))
                && Files.isRegularFile(Paths.get(angieCertsPath, KEY_FILE));
    }

    /** ACME (Let's Encrypt) настроен в angie.conf — можно вернуться на него при удалении. */
    public boolean isAcmeAvailable() {
        return hasAcmeClient();
    }

    /** ACME используется прямо сейчас (tls.conf или legacy-конфиг указывают на ACME). */
    public boolean isAcmeActive() {
        try {
            if (usesTlsInclude()) {
                Path tlsConf = tlsConfPath();
                return Files.exists(tlsConf)
                        && Files.readString(tlsConf, StandardCharsets.UTF_8)
                        .contains(ACME_LINE);
            }
            String conf = angieConfContent();
            return conf.contains(ACME_LINE)
                    && !conf.contains(ACME_LINE_COMMENTED);
        } catch (Exception e) {
            return false;
        }
    }
}
