package ru.afina.gosklyuch.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.afina.gosklyuch.GosklyuchProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Госключ: клиент API ЕПГУ (методы и разбор ответов)")
class EpguGosklyuchClientTest {

    private HttpServer server;
    private final BlockingQueue<Request> requests = new ArrayBlockingQueue<>(16);
    private GosklyuchProperties properties;
    private EpguGosklyuchClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        properties = new GosklyuchProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        properties.setAuthHeader("X-Auth");
        properties.setAuthPrefix("Bearer ");
        properties.setToken("T-123");
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofSeconds(5));
        client = new EpguGosklyuchClient(properties,
                () -> properties.getToken(), new ObjectMapper());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("Создание заявки: meta в form-data, разбор orderId")
    void createsOrder() throws Exception {
        long orderId = client.createOrder(meta());

        Request req = take();
        assertThat(req.path()).isEqualTo("/api/gusmev/order");
        assertThat(req.authHeader()).isEqualTo("Bearer T-123");
        assertThat(req.body()).contains("name=\"meta\"")
                .contains("\"serviceCode\":\"10000000374\"");
        assertThat(orderId).isEqualTo(2058854583L);
    }

    @Test
    @DisplayName("Загрузка архива: /api/gusmev/push с файлом")
    void pushesPackage() throws Exception {
        long orderId = client.createOrder(meta());
        take();

        client.pushPackage(orderId, new byte[]{1, 2, 3}, meta());

        Request push = take();
        assertThat(push.path()).isEqualTo("/api/gusmev/push");
        assertThat(push.body()).contains("name=\"orderId\"");
        assertThat(push.body()).contains("name=\"file\"");
        assertThat(push.body()).contains("\"region\":\"45000000000\"");
    }

    @Test
    @DisplayName("Загрузка частями: chunk/chunks/orderId/meta и ожидание 206/200")
    void pushesChunked() throws Exception {
        properties.setChunkSize(2);
        long orderId = client.createOrder(meta());
        take();

        client.pushPackage(orderId, new byte[]{1, 2, 3, 4, 5}, meta());

        Request first = take();
        assertThat(first.path()).isEqualTo("/api/gusmev/push/chunked");
        assertThat(first.body()).contains("name=\"chunk\"")
                .contains("\r\n\r\n1\r\n")
                .contains("name=\"chunks\"")
                .contains("\r\n\r\n3\r\n");
        Request second = take();
        assertThat(second.body()).contains("\r\n\r\n2\r\n");
        Request third = take();
        assertThat(third.body()).contains("\r\n\r\n3\r\n");
    }

    @Test
    @DisplayName("Детали заявки: разбор вложенного JSON и ссылок terrabyte")
    void readsOrder() throws Exception {
        long orderId = client.createOrder(meta());
        take();

        GosklyuchOrderInfo info = client.getOrder(orderId);

        Request details = take();
        assertThat(details.path()).isEqualTo("/api/gusmev/order/2058854583");
        assertThat(details.query()).isEqualTo("embed=STATUS_HISTORY");
        assertThat(info.statusCode()).isEqualTo(3);
        assertThat(info.statusName()).isEqualTo("Документы подписаны");
        assertThat(info.files()).extracting(GosklyuchFile::fileName)
                .containsExactly("document_1.pdf.sig", "document_1.pdf");
        assertThat(info.files().get(0).version()).isEqualTo(3);
    }

    @Test
    @DisplayName("Скачивание подписи: путь storage и мнемоника")
    void downloadsFile() throws Exception {
        byte[] data = client.downloadFile(2058854583L,
                new GosklyuchFile("document_1.pdf", 3, "application/pdf"));

        Request download = take();
        assertThat(download.path()).isEqualTo("/api/storage/v2/files/2058854583/3/download");
        assertThat(download.query()).isEqualTo("mnemonic=document_1.pdf");
        assertThat(new String(data, StandardCharsets.UTF_8)).isEqualTo("SIGNATURE-BYTES");
    }

    @Test
    @DisplayName("Ошибка HTTP возвращается как GosklyuchClientException со статусом")
    void reportsHttpErrors() throws Exception {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            byte[] error = "{\"code\":\"ERROR\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
        });

        assertThatThrownBy(() -> client.createOrder(meta()))
                .isInstanceOf(GosklyuchClientException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("Без базового адреса клиент не создаётся")
    void requiresBaseUrl() {
        properties.setBaseUrl("  ");
        assertThatThrownBy(() -> new EpguGosklyuchClient(properties,
                () -> "token", new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    // ── управление тестовым сервером ───────────────────────

    private GosklyuchOrderMeta meta() {
        return new GosklyuchOrderMeta("45000000000", "10000000374", "-10000000374");
    }

    private Request take() throws InterruptedException {
        Request r = requests.poll(5, TimeUnit.SECONDS);
        assertThat(r).as("запрос к тестовому серверу").isNotNull();
        return r;
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        requests.add(new Request(path, query,
                exchange.getRequestHeaders().getFirst("X-Auth"),
                new String(body, StandardCharsets.UTF_8)));

        String response;
        int status = 200;
        if (path.equals("/api/gusmev/order")) {
            response = "{\"orderId\":2058854583}";
        } else if (path.startsWith("/api/gusmev/push")) {
            response = "{\"orderId\":2058854583}";
            if (path.endsWith("/chunked") && bodyContainsChunk(body, 1)) {
                status = 206;
            }
        } else if (path.startsWith("/api/gusmev/order/")) {
            response = orderResponse();
        } else if (path.startsWith("/api/storage/v2/files/")) {
            byte[] payload = "SIGNATURE-BYTES".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
            return;
        } else {
            response = "{}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static boolean bodyContainsChunk(byte[] body, int chunk) {
        return new String(body, StandardCharsets.UTF_8).contains("\r\n\r\n" + chunk + "\r\n");
    }

    private static String orderResponse() {
        String order = "{\"orderStatusId\":3,\"orderStatusName\":\"Документы подписаны\","
                + "\"orderResponseFiles\":["
                + "{\"fileName\":\"document_1.pdf.sig\",\"mimeType\":\"application/octet-stream\","
                + "\"link\":\"terrabyte://00/2058854583/document_1.pdf/3\"},"
                + "{\"fileName\":\"document_1.pdf\",\"mimeType\":\"application/pdf\","
                + "\"link\":\"terrabyte://00/2058854583/document_1.pdf_attach/3\"}]}";
        try {
            return "{\"code\":\"OK\",\"message\":null,\"order\":"
                    + new ObjectMapper().writeValueAsString(order) + "}";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Request(String path, String query, String authHeader, String body) {
    }
}
