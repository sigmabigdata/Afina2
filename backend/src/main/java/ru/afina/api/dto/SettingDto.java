package ru.afina.api.dto;

import java.time.Instant;

/** Настройка приложения (ключ/значение) для админки. */
public record SettingDto(
        String key,
        String value,
        String description,
        Instant updatedAt,
        String updatedBy,
        boolean secret) {
}
