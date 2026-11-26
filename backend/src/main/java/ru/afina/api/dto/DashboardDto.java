package ru.afina.api.dto;

import java.time.Instant;
import java.util.List;

/** Сводка для главного экрана кабинета. */
public record DashboardDto(
        long documentsTotal,
        long documentsSigned,
        long documentsUnsigned,
        long foldersTotal,
        long gosklyuchPending,
        long signaturesToday,
        String averageSigningTime,
        List<Point> activity,
        List<DocumentDto> recentDocuments,
        List<GosklyuchOrderDto> recentOrders) {

    /** Точка графика активности подписания. */
    public record Point(Instant date, String label, long signed, long uploaded) {
    }
}
