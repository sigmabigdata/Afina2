package ru.afina.api.dto;

import java.time.Instant;

/** Папка (раздел) документов клиента. */
public record FolderDto(
        Long id,
        String name,
        long documentsCount,
        long signedCount,
        Instant createdAt) {
}
