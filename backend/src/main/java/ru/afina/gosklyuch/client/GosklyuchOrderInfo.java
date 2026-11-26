package ru.afina.gosklyuch.client;

import java.util.List;

/**
 * Детали заявки по ответу {@code POST /api/gusmev/order/{orderId}?embed=STATUS_HISTORY}.
 *
 * @param orderId    номер заявки в ЕПГУ
 * @param statusCode код статуса (0, 17, 3, 4, 5, 24 и т.п.)
 * @param statusName текстовое название статуса
 * @param files      файлы результата ({@code orderResponseFiles})
 */
public record GosklyuchOrderInfo(long orderId, int statusCode, String statusName,
                                 List<GosklyuchFile> files) {

    public GosklyuchOrderInfo {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
