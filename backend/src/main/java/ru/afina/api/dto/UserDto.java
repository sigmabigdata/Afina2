package ru.afina.api.dto;

import java.time.Instant;

/** Краткая информация о пользователе (профиль, шапка кабинета). */
public record UserDto(
        Long id,
        String email,
        String fullName,
        String phone,
        String snils,
        String role,
        boolean enabled,
        boolean profileComplete,
        boolean consentSigned,
        Instant consentSignedAt,
        Instant lastLoginAt,
        Instant createdAt,
        long documentsTotal,
        long documentsSigned) {
}
