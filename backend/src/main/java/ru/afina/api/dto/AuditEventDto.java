package ru.afina.api.dto;

import java.time.Instant;

/** Событие журнала аудита. */
public record AuditEventDto(
        Long id,
        Instant eventTime,
        String eventType,
        String result,
        String actorEmail,
        String actorRole,
        String actorIp,
        String targetType,
        String targetId,
        String targetInfo,
        String details) {
}
