package ru.afina.gosklyuch.client;

/**
 * Файл в ответе ЕПГУ по заявке.
 *
 * @param fileName имя файла (оно же мнемоника для скачивания)
 * @param version  версия файла из ссылки {@code terrabyte://…/<fileName>/<version>}
 * @param mimeType MIME-тип файла
 */
public record GosklyuchFile(String fileName, int version, String mimeType) {
}
