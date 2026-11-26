package ru.afina.gosklyuch.model;

import java.util.List;

/**
 * Запрос на подписание документов в МП «Госключ»
 * (содержимое файла {@code req.xml}).
 *
 * <p>Подписант идентифицируется СНИЛС ({@code snils}) и/или OID учётной
 * записи ЕСИА ({@code oid}). По таблице параметров спецификации v1.8
 * достаточно одного из них.</p>
 */
public record GosklyuchSignRequest(String snils, String oid, List<GosklyuchDocument> documents) {

    public GosklyuchSignRequest {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }
}
