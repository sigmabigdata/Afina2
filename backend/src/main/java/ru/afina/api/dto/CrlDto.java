package ru.afina.api.dto;

import java.time.Instant;

/** Список отозванных сертификатов (CRL) и его состояние. */
public record CrlDto(
        String fileName,
        long sizeBytes,
        String sizePretty,
        String issuer,
        Instant lastUpdate,
        Instant nextUpdate,
        Instant downloadedAt,
        long daysToExpiry,
        boolean manual,
        String status) {
}
