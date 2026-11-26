package ru.afina.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Служебный health-эндпоинт (не требует авторизации). */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final Instant startedAt = Instant.now();

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "afina2");
        body.put("version", "1.0.0");
        body.put("time", Instant.now().toString());
        body.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
        return body;
    }
}
