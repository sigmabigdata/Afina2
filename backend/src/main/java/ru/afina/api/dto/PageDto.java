package ru.afina.api.dto;

import java.util.List;

/** Постраничная выдача. */
public record PageDto<T>(
        List<T> items,
        long total,
        int page,
        int size,
        int pages) {

    public static <T> PageDto<T> of(List<T> items, long total, int page, int size) {
        int pages = size <= 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageDto<>(items, total, page, size, pages);
    }

    public static <T> PageDto<T> single(List<T> items) {
        return new PageDto<>(items, items.size(), 0, items.size(), 1);
    }
}
