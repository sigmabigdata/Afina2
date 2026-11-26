package ru.afina.api.dto;

import java.time.Instant;

/** Шаблон документа (генерация PDF по форме). */
public record TemplateDto(
        Long id,
        String kind,
        String name,
        String description,
        String htmlContent,
        String fieldsJson,
        boolean active,
        int sortOrder,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        int fieldsCount) {
}
