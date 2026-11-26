package ru.afina.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Документ в списке и в карточке. */
public record DocumentDto(
        Long id,
        String name,
        String contentType,
        long size,
        String sizePretty,
        String extension,
        String fileSha256,
        boolean signed,
        Instant signedAt,
        String signerSubject,
        Instant uploadedAt,
        boolean applyPoa,
        UUID publicUuid,
        Long folderId,
        String folderName,
        Long ownerId,
        String ownerName,
        int signaturesCount,
        List<SignatureDto> signatures,
        VisualizationDto visualization,
        GosklyuchOrderDto gosklyuchOrder) {

    /** Отсоединённая подпись CAdES-BES. */
    public record SignatureDto(
            Long id,
            String signerSubject,
            String signerSerial,
            Instant signedAt,
            boolean applyPoa,
            Long signerUserId,
            String signerName) {
    }

    /** PDF-визуализация штампов подписи. */
    public record VisualizationDto(
            int version,
            long size,
            Instant createdAt,
            Instant updatedAt) {
    }
}
