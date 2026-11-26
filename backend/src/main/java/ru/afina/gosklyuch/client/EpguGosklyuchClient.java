package ru.afina.gosklyuch.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.afina.gosklyuch.GosklyuchProperties;
import ru.afina.gosklyuch.GosklyuchTokenProvider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP-клиент API ЕПГУ (разделы 2.2.1–2.2.4 спецификации Госключа v1.8).
 *
 * <p>Адрес API, заголовок авторизации и маркер доступа — из конфигурации.
 * Кодировка ответов — UTF-8, тела — JSON/form-data.</p>
 */
public class EpguGosklyuchClient implements GosklyuchClient {

    private static final Logger log = LoggerFactory.getLogger(EpguGosklyuchClient.class);

    static final String PATH_ORDER = "/api/gusmev/order";
    static final String PATH_PUSH = "/api/gusmev/push";
    static final String PATH_PUSH_CHUNKED = "/api/gusmev/push/chunked";
    static final String PATH_STORAGE = "/api/storage/v2/files/";

    private static final String HEADER_ACCEPT = "Accept";
    private static final String APPLICATION_JSON = "application/json";

    private final GosklyuchProperties properties;
    private final GosklyuchTokenProvider tokenProvider;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String baseUrl;

    public EpguGosklyuchClient(GosklyuchProperties properties,
                               GosklyuchTokenProvider tokenProvider,
                               ObjectMapper mapper) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.mapper = mapper;
        this.baseUrl = normalizeBaseUrl(properties.getBaseUrl());
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "Не задан адрес API ЕПГУ (app.gosklyuch.base-url)");
        }
        String value = raw.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    @Override
    public long createOrder(GosklyuchOrderMeta meta) {
        String body = metaJson(meta);
        GosklyuchMultipart form = new GosklyuchMultipart(newBoundary()).field("meta", body);
        HttpResponse<String> response = post(PATH_ORDER, form, 200);
        JsonNode json = readJson(response.body());
        long orderId = json.path("orderId").asLong(0);
        if (orderId <= 0) {
            throw new GosklyuchClientException(
                    "ЕПГУ не вернул номер заявки: " + abbreviate(response.body()));
        }
        log.info("Госключ: создана заявка {}", orderId);
        return orderId;
    }

    @Override
    public void pushPackage(long orderId, byte[] zip, GosklyuchOrderMeta meta) {
        if (zip == null || zip.length == 0) {
            throw new IllegalArgumentException("Архив заявки пуст");
        }
        long chunkSize = properties.getChunkSize();
        if (chunkSize > 0 && zip.length > chunkSize) {
            pushChunked(orderId, zip, meta, (int) chunkSize);
            return;
        }
        pushSingle(orderId, zip, meta);
    }

    private void pushSingle(long orderId, byte[] zip, GosklyuchOrderMeta meta) {
        GosklyuchMultipart form = new GosklyuchMultipart(newBoundary())
                .field("meta", metaJson(meta))
                .field("orderId", String.valueOf(orderId));
        try {
            form.file("file", "req_" + orderId + ".zip", zip, "application/zip");
        } catch (IOException e) {
            throw new GosklyuchClientException("Не удалось сформировать запрос загрузки", 0, e);
        }
        post(PATH_PUSH, form, 200);
        log.info("Госключ: архив заявки {} загружен ({} байт)", orderId, zip.length);
    }

    private void pushChunked(long orderId, byte[] zip, GosklyuchOrderMeta meta, int chunkSize) {
        int chunks = (zip.length + chunkSize - 1) / chunkSize;
        for (int i = 1; i <= chunks; i++) {
            int from = (i - 1) * chunkSize;
            int to = Math.min(from + chunkSize, zip.length);
            byte[] part = Arrays.copyOfRange(zip, from, to);
            GosklyuchMultipart form = new GosklyuchMultipart(newBoundary())
                    .field("chunk", String.valueOf(i))
                    .field("chunks", String.valueOf(chunks))
                    .field("orderId", String.valueOf(orderId))
                    .field("meta", metaJson(meta));
            try {
                form.file("file", "part_" + i + ".zip", part, "application/zip");
            } catch (IOException e) {
                throw new GosklyuchClientException("Не удалось сформировать запрос загрузки", 0, e);
            }
            int expected = i == chunks ? 200 : 206;
            post(PATH_PUSH_CHUNKED, form, expected, 206, 200);
        }
        log.info("Госключ: архив заявки {} загружен частями ({} частей)", orderId, chunks);
    }

    @Override
    public GosklyuchOrderInfo getOrder(long orderId) {
        HttpResponse<String> response = request(
                "POST", PATH_ORDER + "/" + orderId + "?embed=STATUS_HISTORY",
                null, null, 200);
        JsonNode root = readJson(response.body());
        String code = root.path("code").asText("");
        if (!"OK".equalsIgnoreCase(code)) {
            throw new GosklyuchClientException(
                    "ЕПГУ вернул ошибку по заявке " + orderId + ": "
                            + root.path("message").asText(abbreviate(response.body())));
        }
        JsonNode order = root.path("order");
        if (order.isTextual()) {
            order = readJson(order.asText());
        }
        int statusId = order.path("orderStatusId").asInt(-1);
        String statusName = order.path("orderStatusName").asText("");
        List<GosklyuchFile> files = new ArrayList<>();
        JsonNode fileNodes = order.path("orderResponseFiles");
        if (fileNodes.isArray()) {
            for (JsonNode f : fileNodes) {
                String fileName = f.path("fileName").asText("");
                int version = versionFromLink(f.path("link").asText(""));
                if (!fileName.isEmpty()) {
                    files.add(new GosklyuchFile(fileName, version,
                            f.path("mimeType").asText("")));
                }
            }
        }
        return new GosklyuchOrderInfo(orderId, statusId, statusName, files);
    }

    /** Версия файла — последний сегмент ссылки {@code terrabyte://…/file.pdf/3}. */
    static int versionFromLink(String link) {
        if (link == null || link.isBlank()) return 0;
        String cleaned = link;
        int q = cleaned.indexOf('?');
        if (q >= 0) cleaned = cleaned.substring(0, q);
        int slash = cleaned.lastIndexOf('/');
        if (slash < 0) return 0;
        try {
            return Integer.parseInt(cleaned.substring(slash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public byte[] downloadFile(long orderId, GosklyuchFile file) {
        String mnemonic = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8);
        String path = PATH_STORAGE + orderId + "/" + file.version()
                + "/download?mnemonic=" + mnemonic;
        HttpResponse<byte[]> response = requestBytes("GET", path, 200);
        return response.body();
    }

    // ── транспорт ───────────────────────────────────────────

    private HttpResponse<String> post(String path, GosklyuchMultipart form,
                                      int... expected) {
        byte[] body = form.build();
        return request("POST", path, body,
                "multipart/form-data; boundary=" + form.boundary(), expected);
    }

    private HttpResponse<String> request(String method, String path, byte[] body,
                                         String contentType, int... expected) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(properties.getReadTimeout())
                .header(HEADER_ACCEPT, APPLICATION_JSON);
        String token = tokenProvider.token();
        String prefix = properties.getAuthPrefix() == null ? "" : properties.getAuthPrefix();
        b.header(properties.getAuthHeader(), prefix + token);
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            if (contentType != null) b.header("Content-Type", contentType);
        }
        try {
            HttpResponse<String> response =
                    http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            checkStatus(path, response.statusCode(), response.body(), expected);
            return response;
        } catch (IOException e) {
            throw new GosklyuchClientException(
                    "Ошибка запроса к ЕПГУ " + path + ": " + e.getMessage(), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GosklyuchClientException("Запрос к ЕПГУ прерван", 0, e);
        }
    }

    private HttpResponse<byte[]> requestBytes(String method, String path, int... expected) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(properties.getReadTimeout())
                .header(HEADER_ACCEPT, APPLICATION_JSON);
        String token = tokenProvider.token();
        String prefix = properties.getAuthPrefix() == null ? "" : properties.getAuthPrefix();
        b.header(properties.getAuthHeader(), prefix + token);
        b.method(method, HttpRequest.BodyPublishers.noBody());
        try {
            HttpResponse<byte[]> response =
                    http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (!matches(response.statusCode(), expected)) {
                throw new GosklyuchClientException(
                        "ЕПГУ ответил " + response.statusCode() + " на " + path,
                        response.statusCode());
            }
            return response;
        } catch (IOException e) {
            throw new GosklyuchClientException(
                    "Ошибка скачивания файла из ЕПГУ " + path + ": " + e.getMessage(), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GosklyuchClientException("Запрос к ЕПГУ прерван", 0, e);
        }
    }

    private void checkStatus(String path, int status, String body, int... expected) {
        if (matches(status, expected)) return;
        throw new GosklyuchClientException(
                "ЕПГУ ответил " + status + " на " + path + ": " + abbreviate(body), status);
    }

    private static boolean matches(int status, int... expected) {
        if (expected == null || expected.length == 0) return status >= 200 && status < 300;
        for (int e : expected) {
            if (e == status) return true;
        }
        return false;
    }

    private String metaJson(GosklyuchOrderMeta meta) {
        ObjectNode node = mapper.createObjectNode();
        node.put("region", nullSafe(meta.region()));
        node.put("serviceCode", nullSafe(meta.serviceCode()));
        node.put("targetCode", nullSafe(meta.targetCode()));
        try {
            return mapper.writeValueAsString(node);
        } catch (IOException e) {
            throw new GosklyuchClientException("Не удалось сформировать meta", 0, e);
        }
    }

    private JsonNode readJson(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new GosklyuchClientException(
                    "Некорректный JSON от ЕПГУ: " + abbreviate(body), 0, e);
        }
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    private static String newBoundary() {
        return "----AfinaZ" + UUID.randomUUID().toString().replace("-", "");
    }

    static String abbreviate(String body) {
        if (body == null) return "";
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() > 300 ? compact.substring(0, 300) + "…" : compact;
    }

    /** Диагностическая информация о настройках (без секретов). */
    public Map<String, String> diagnostics() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("baseUrl", baseUrl);
        map.put("authHeader", properties.getAuthHeader());
        map.put("chunkSize", String.valueOf(properties.getChunkSize()));
        return map;
    }
}
