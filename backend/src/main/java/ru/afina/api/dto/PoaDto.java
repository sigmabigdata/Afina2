package ru.afina.api.dto;

import java.time.Instant;
import java.time.LocalDate;

/** Машиночитаемая доверенность (МЧД). */
public record PoaDto(
        Long id,
        String fio,
        String snils,
        String inn,
        String orgName,
        String orgInn,
        String docNumber,
        String stampNumber,
        LocalDate docDate,
        LocalDate validFrom,
        LocalDate validUntil,
        String notaryName,
        boolean active,
        boolean effective,
        String createdBy,
        Instant createdAt) {
}
