package ru.afina.api.dto;

import java.time.Instant;
import java.util.List;

/** Заявка на подписание в мобильном приложении «Госключ». */
public record GosklyuchOrderDto(
        Long id,
        Long documentId,
        String documentName,
        Long ownerId,
        String ownerName,
        String signerSnils,
        Long externalOrderId,
        String status,
        String statusName,
        Integer statusCode,
        String error,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        List<ItemDto> items) {

    /** Документ внутри пакетной заявки. */
    public record ItemDto(
            Long id,
            Long documentId,
            String fileName,
            boolean signed,
            boolean applied) {
    }
}
