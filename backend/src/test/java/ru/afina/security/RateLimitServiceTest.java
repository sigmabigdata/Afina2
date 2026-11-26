package ru.afina.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RateLimitService: in-memory fixed window rate limiter")
class RateLimitServiceTest {

    private RateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RateLimitService();
    }

    @Test
    @DisplayName("tryConsume: первый запрос проходит")
    void firstRequestAllowed() {
        assertThat(service.tryConsume("k", 5, Duration.ofMinutes(15))).isTrue();
    }

    @Test
    @DisplayName("tryConsume: ровно N запросов в пределах лимита проходят")
    void upToLimitAllowed() {
        for (int i = 0; i < 5; i++) {
            assertThat(service.tryConsume("k", 5, Duration.ofMinutes(15)))
                    .as("request #%d", i + 1).isTrue();
        }
    }

    @Test
    @DisplayName("tryConsume: N+1 запрос отклоняется")
    void overLimitRejected() {
        for (int i = 0; i < 5; i++) {
            service.tryConsume("k", 5, Duration.ofMinutes(15));
        }
        assertThat(service.tryConsume("k", 5, Duration.ofMinutes(15))).isFalse();
    }

    @Test
    @DisplayName("tryConsume: разные ключи независимы")
    void differentKeysIndependent() {
        for (int i = 0; i < 5; i++) {
            service.tryConsume("a", 5, Duration.ofMinutes(15));
        }
        assertThat(service.tryConsume("a", 5, Duration.ofMinutes(15))).isFalse();
        assertThat(service.tryConsume("b", 5, Duration.ofMinutes(15))).isTrue();
    }

    @Test
    @DisplayName("tryConsume: после истечения окна счётчик сбрасывается")
    void windowExpiryResets() throws InterruptedException {
        // окно 100мс — с запасом по sleep 200мс
        assertThat(service.tryConsume("k", 1, Duration.ofMillis(100))).isTrue();
        assertThat(service.tryConsume("k", 1, Duration.ofMillis(100))).isFalse();
        Thread.sleep(200);
        assertThat(service.tryConsume("k", 1, Duration.ofMillis(100))).isTrue();
    }

    @Test
    @DisplayName("retryAfterSeconds: для существующего bucket → > 0")
    void retryAfterPositive() {
        service.tryConsume("k", 1, Duration.ofSeconds(60));
        assertThat(service.retryAfterSeconds("k")).isGreaterThan(0);
    }

    @Test
    @DisplayName("retryAfterSeconds: неизвестный ключ → 0")
    void retryAfterUnknownKey() {
        assertThat(service.retryAfterSeconds("unknown-key")).isEqualTo(0);
    }

    @Test
    @DisplayName("cleanup: удаляет протухшие buckets")
    void cleanupRemovesExpired() throws InterruptedException {
        service.tryConsume("a", 1, Duration.ofMillis(100));
        service.tryConsume("b", 1, Duration.ofMillis(100));
        Thread.sleep(200);
        service.cleanup();
        // После cleanup ключей нет — retryAfterSeconds вернёт 0
        assertThat(service.retryAfterSeconds("a")).isZero();
        assertThat(service.retryAfterSeconds("b")).isZero();
    }
}
