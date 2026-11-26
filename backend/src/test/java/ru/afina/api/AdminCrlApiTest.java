package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import ru.afina.entity.User;
import ru.afina.repository.DocumentSignatureRepository;
import ru.afina.service.CrlService;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Админский API списков отзыва сертификатов (CRL): список, сводка, отчёт,
 * ручная загрузка, удаление (включая защиту от path traversal) и права доступа.
 *
 * <p>Для загрузки генерируется настоящий CRL через BouncyCastle — файл
 * проходит реальную проверку {@link CrlService#saveManual}. Созданные файлы
 * удаляются в {@code @AfterEach}, чтобы не влиять на другие тесты.</p>
 */
class AdminCrlApiTest extends ApiTestBase {

    private static final String BASE = "/api/v1/admin/crl";

    @Autowired
    private CrlService crlService;
    @Autowired
    private DocumentSignatureRepository signatureRepository;

    private User admin;
    private final List<String> createdFiles = new ArrayList<>();

    private static KeyPair caKeyPair;

    @BeforeAll
    static void generateCa() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        caKeyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void initAdmin() {
        admin = createAdmin();
    }

    @AfterEach
    void removeCreatedFiles() throws Exception {
        for (String fileName : createdFiles) {
            Files.deleteIfExists(crlService.getCrlsDir().resolve(fileName));
        }
        createdFiles.clear();
    }

    // ──────────────────────────── вспомогательное ────────────────────────────

    /** Настоящий подписанный CRL с указанным сроком действия. */
    private byte[] validCrlBytes(int daysValid) throws Exception {
        X500Name issuer = new X500Name("CN=Test CA " + UUID.randomUUID() + ", O=Afina Test, C=RU");
        Date now = new Date();
        // Для уже истёкшего CRL thisUpdate должен быть раньше nextUpdate,
        // иначе получится CRL «из будущего».
        Date thisUpdate = daysValid < 0
                ? new Date(now.getTime() - TimeUnit.DAYS.toMillis(30)) : now;
        X509v2CRLBuilder builder = new X509v2CRLBuilder(issuer, thisUpdate);
        builder.setNextUpdate(new Date(now.getTime() + TimeUnit.DAYS.toMillis(daysValid)));
        builder.addCRLEntry(BigInteger.ONE, thisUpdate, 1);

        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(caKeyPair.getPrivate());
        var crl = new JcaX509CRLConverter().setProvider("BC").getCRL(builder.build(signer));
        return crl.getEncoded();
    }

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID() + ".crl";
    }

    private MockMultipartFile crlFile(String fileName, byte[] data) {
        return new MockMultipartFile("file", fileName, "application/pkix-crl", data);
    }

    /** Загружает валидный CRL и запоминает имя файла для уборки. */
    private void uploadValidCrl(String fileName) throws Exception {
        uploadValidCrl(fileName, 60);
    }

    /** Загружает валидный CRL с заданным сроком действия (может быть отрицательным). */
    private void uploadValidCrl(String fileName, int daysValid) throws Exception {
        createdFiles.add(fileName);
        mockMvc.perform(as(admin, multipart(BASE).file(crlFile(fileName, validCrlBytes(daysValid)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploaded").value(true))
                .andExpect(jsonPath("$.sizeBytes").isNumber());
    }

    private static JsonNode findByName(JsonNode array, String fileName) {
        for (JsonNode item : array) {
            if (fileName.equals(item.get("fileName").asText())) return item;
        }
        return null;
    }

    // ════════════════════════════ список, сводка, отчёт ════════════════════════════

    @Test
    @DisplayName("Список CRL: загруженный файл виден со статусом и размером")
    void listContainsUploadedCrl() throws Exception {
        String fileName = uniqueName("manual");
        uploadValidCrl(fileName);

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        JsonNode found = findByName(json(result), fileName);
        assertThat(found).isNotNull();
        assertThat(found.get("manual").asBoolean()).isTrue();
        assertThat(found.get("status").asText()).isIn("ok", "expiring", "expired");
        assertThat(found.get("sizeBytes").asLong()).isPositive();
        assertThat(found.get("sizePretty").asText()).isNotEmpty();
        assertThat(found.get("nextUpdate").isNull()).isFalse();
    }

    @Test
    @DisplayName("Сводка по каталогу CRL: количество, размер, истекающие и истёкшие")
    void summary() throws Exception {
        uploadValidCrl(uniqueName("manual"));

        mockMvc.perform(asGet(admin, get(BASE + "/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").isNumber())
                .andExpect(jsonPath("$.totalSize").isNumber())
                .andExpect(jsonPath("$.totalSizePretty").isNotEmpty())
                .andExpect(jsonPath("$.expiring").isNumber())
                .andExpect(jsonPath("$.expired").isNumber());

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE + "/summary")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(result).get("count").asLong()).isPositive();
    }

    @Test
    @DisplayName("Отчёт о состоянии CRL: элементы и сводка")
    void statusReport() throws Exception {
        uploadValidCrl(uniqueName("manual"));

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE + "/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.summary.total").isNumber())
                .andExpect(jsonPath("$.summary.ok").isNumber())
                .andExpect(jsonPath("$.summary.expiring").isNumber())
                .andExpect(jsonPath("$.summary.expired").isNumber())
                .andExpect(jsonPath("$.summary.untrusted").isNumber())
                .andReturn();

        assertThat(json(result).get("items")).isNotEmpty();
    }

    // ════════════════════════════ загрузка ════════════════════════════

    @Test
    @DisplayName("Загрузка битого CRL → 400")
    void uploadInvalidCrl() throws Exception {
        mockMvc.perform(as(admin, multipart(BASE)
                        .file(crlFile("bad.crl", "это не CRL".getBytes(StandardCharsets.UTF_8)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    @DisplayName("Загрузка пустого файла и запрос без файла → 400")
    void uploadEmptyOrMissingFile() throws Exception {
        mockMvc.perform(as(admin, multipart(BASE).file(crlFile("empty.crl", new byte[0]))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(as(admin, multipart(BASE)))
                .andExpect(status().isBadRequest());
    }

    // ════════════════════════════ удаление ════════════════════════════

    @Test
    @DisplayName("Удаление CRL по имени: файл исчезает из каталога и списка")
    void deleteByName() throws Exception {
        String fileName = uniqueName("manual");
        uploadValidCrl(fileName);

        mockMvc.perform(as(admin, delete(BASE + "/" + fileName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.fileName").value(fileName));

        assertThat(Files.exists(crlService.getCrlsDir().resolve(fileName))).isFalse();

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(findByName(json(result), fileName)).isNull();
    }

    @Test
    @DisplayName("Защита от path traversal в имени файла → 400")
    void deleteRejectsPathTraversal() throws Exception {
        for (String malicious : List.of("..secret.crl", "a..b.crl", ".hidden.crl")) {
            mockMvc.perform(as(admin, delete(BASE + "/" + malicious)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"));
        }

        // литеральный ".." отсекается ещё до контроллера (нормализация пути) —
        // тело пустое, но клиент получает 400, а не доступ к каталогу
        mockMvc.perform(as(admin, delete(BASE + "/..")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Удаление всех автоматически скачанных CRL")
    void deleteAllAuto() throws Exception {
        String autoName = uniqueName("auto");
        uploadValidCrl(autoName);

        mockMvc.perform(as(admin, delete(BASE + "/auto")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").isNumber());

        assertThat(Files.exists(crlService.getCrlsDir().resolve(autoName))).isFalse();

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(findByName(json(result), autoName)).isNull();
    }

    @Test
    @DisplayName("Список CRL: истёкший и истекающий файлы получают статусы expired/expiring")
    void listShowsBoundaryStatuses() throws Exception {
        String expiredName = uniqueName("manual");
        String expiringName = uniqueName("manual");
        uploadValidCrl(expiredName, -2);
        uploadValidCrl(expiringName, 10);

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode expired = findByName(json(result), expiredName);
        JsonNode expiring = findByName(json(result), expiringName);
        assertThat(expired).isNotNull();
        assertThat(expiring).isNotNull();
        assertThat(expired.get("status").asText()).isEqualTo("expired");
        assertThat(expiring.get("status").asText()).isEqualTo("expiring");

        mockMvc.perform(asGet(admin, get(BASE + "/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expired").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.expiring").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    // ════════════════════════════ принудительное обновление ════════════════════════════

    @Test
    @DisplayName("POST /refresh: без подписей скачивать нечего → 200 и нулевые счётчики")
    void refreshWithoutSignatures() throws Exception {
        // В тестовом окружении сети нет; при пустой таблице подписей
        // CrlDownloader не делает ни одного запроса — ответ детерминирован.
        signatureRepository.deleteAll();

        mockMvc.perform(as(admin, post(BASE + "/refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSignatures").value(0))
                .andExpect(jsonPath("$.parsedSignatures").value(0))
                .andExpect(jsonPath("$.uniqueCerts").value(0))
                .andExpect(jsonPath("$.downloaded").value(0))
                .andExpect(jsonPath("$.crlDownloaded").value(0))
                .andExpect(jsonPath("$.crlFailed").value(0))
                .andExpect(jsonPath("$.failures").isArray());
    }

    @Test
    @DisplayName("Удаление несуществующего CRL → 404")
    void deleteMissingCrl() throws Exception {
        mockMvc.perform(as(admin, delete(BASE + "/manual-missing-"
                        + UUID.randomUUID() + ".crl")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    // ════════════════════════════ права доступа ════════════════════════════

    @Test
    @DisplayName("Не-администратор получает 403")
    void nonAdminForbidden() throws Exception {
        User client = createClient();

        mockMvc.perform(asGet(client, get(BASE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/summary")))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/status")))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, multipart(BASE)
                        .file(crlFile("client.crl", "x".getBytes(StandardCharsets.UTF_8)))))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, delete(BASE + "/auto")))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, post(BASE + "/refresh")))
                .andExpect(status().isForbidden());
    }
}
