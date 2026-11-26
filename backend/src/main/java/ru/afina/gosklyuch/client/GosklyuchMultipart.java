package ru.afina.gosklyuch.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Минимальный сборщик {@code multipart/form-data} для запросов к API ЕПГУ
 * (без внешних зависимостей — поля и файлы передаются как есть).
 */
final class GosklyuchMultipart {

    private final String boundary;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    GosklyuchMultipart(String boundary) {
        this.boundary = boundary;
    }

    String boundary() {
        return boundary;
    }

    GosklyuchMultipart field(String name, String value) {
        write("--" + boundary + "\r\n");
        write("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        write(value == null ? "" : value);
        write("\r\n");
        return this;
    }

    GosklyuchMultipart file(String name, String fileName, byte[] content, String contentType)
            throws IOException {
        write("--" + boundary + "\r\n");
        write("Content-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + fileName + "\"\r\n");
        write("Content-Type: " + (contentType == null ? "application/octet-stream" : contentType)
                + "\r\n\r\n");
        out.write(content);
        write("\r\n");
        return this;
    }

    GosklyuchMultipart fields(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            field(e.getKey(), e.getValue());
        }
        return this;
    }

    byte[] build() {
        write("--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private void write(String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
