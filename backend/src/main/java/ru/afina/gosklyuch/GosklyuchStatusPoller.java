package ru.afina.gosklyuch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодический опрос статусов заявок в Госключе.
 *
 * <p>Пока интеграция выключена ({@code app.gosklyuch.enabled=false}),
 * опрос немедленно завершается — внешних вызовов нет. Интервал задаётся
 * конфигурацией ({@code app.gosklyuch.poll-interval-ms}, по умолчанию
 * 300000 мс = 5 минут).</p>
 */
@Component
public class GosklyuchStatusPoller {

    private static final Logger log = LoggerFactory.getLogger(GosklyuchStatusPoller.class);

    private final GosklyuchService service;
    private final GosklyuchProperties properties;

    /**
     * В демо-режиме база у каждого посетителя своя (tenant в сессии), поэтому
     * фоновый опрос без запроса не имеет смысла и только пишет ошибки в лог.
     */
    @org.springframework.beans.factory.annotation.Value("${app.demo.enabled:false}")
    private boolean demoMode;

    public GosklyuchStatusPoller(GosklyuchService service, GosklyuchProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.gosklyuch.poll-interval-ms:300000}",
            initialDelayString = "${app.gosklyuch.poll-initial-delay-ms:120000}")
    public void poll() {
        if (!properties.isEnabled() || demoMode) return;
        try {
            int processed = service.refreshPending();
            if (processed > 0) {
                log.info("Госключ: опрошено заявок — {}", processed);
            }
        } catch (RuntimeException e) {
            log.warn("Госключ: ошибка опроса статусов: {}", e.getMessage());
        }
    }
}
