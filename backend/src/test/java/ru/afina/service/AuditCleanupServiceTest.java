package ru.afina.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.repository.AuditEventRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditCleanupService: retention + ежедневная очистка")
class AuditCleanupServiceTest {

    @Mock private AuditEventRepository repo;
    @Mock private SettingsService settings;

    @InjectMocks private AuditCleanupService service;

    // ─── dailyCleanup ────────────────────────────

    @Test
    @DisplayName("dailyCleanup: retention=0 → отключено, repo не тронут")
    void dailyCleanupDisabled() {
        when(settings.getIntOrDefault("audit.retention_days", 365)).thenReturn(0);

        service.dailyCleanup();

        verify(repo, never()).count();
        verify(repo, never()).deleteByEventTimeBefore(any());
    }

    @Test
    @DisplayName("dailyCleanup: retention=365 → count, delete, save настроек")
    void dailyCleanupRuns() {
        when(settings.getIntOrDefault("audit.retention_days", 365)).thenReturn(365);
        when(repo.count()).thenReturn(1000L, 700L);
        when(repo.deleteByEventTimeBefore(any(Instant.class))).thenReturn(300L);

        service.dailyCleanup();

        verify(repo).deleteByEventTimeBefore(any(Instant.class));
        verify(settings).set(eq("audit.last_cleanup_at"), anyString(), eq("system"));
        verify(settings).set(eq("audit.last_cleanup_deleted"), eq("300"), eq("system"));
    }

    @Test
    @DisplayName("dailyCleanup: repo упал → catch, не пробрасывает")
    void dailyCleanupSwallows() {
        when(settings.getIntOrDefault("audit.retention_days", 365)).thenReturn(365);
        when(repo.count()).thenReturn(100L);
        when(repo.deleteByEventTimeBefore(any())).thenThrow(new RuntimeException("db down"));

        service.dailyCleanup(); // не должно бросить

        verify(settings, never()).set(eq("audit.last_cleanup_at"), anyString(), anyString());
    }

    // ─── cleanup (ручной) ────────────────────────

    @Test
    @DisplayName("cleanup(30): возвращает кол-во удалённых")
    void cleanupReturnsDeleted() {
        when(repo.count()).thenReturn(500L, 480L);
        when(repo.deleteByEventTimeBefore(any(Instant.class))).thenReturn(20L);

        long n = service.cleanup(30);

        assertThat(n).isEqualTo(20L);
    }

    @Test
    @DisplayName("cleanup(365): count вызывается дважды (before/after)")
    void cleanupCountsBeforeAfter() {
        when(repo.count()).thenReturn(100L, 90L);
        when(repo.deleteByEventTimeBefore(any(Instant.class))).thenReturn(10L);

        service.cleanup(365);

        verify(repo, org.mockito.Mockito.times(2)).count();
    }

    @Test
    @DisplayName("cleanup: 0 удалено → возвращает 0")
    void cleanupZero() {
        when(repo.count()).thenReturn(50L);
        when(repo.deleteByEventTimeBefore(any(Instant.class))).thenReturn(0L);

        assertThat(service.cleanup(90)).isZero();
    }

    @Test
    @DisplayName("cleanup: использует cutoff = now - N дней")
    void cleanupCutoff() {
        when(repo.count()).thenReturn(1L, 0L);
        when(repo.deleteByEventTimeBefore(any(Instant.class))).thenReturn(1L);

        Instant before = Instant.now().minusSeconds(31L * 86400);
        service.cleanup(30);
        Instant after = Instant.now().minusSeconds(29L * 86400);

        var cap = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteByEventTimeBefore(cap.capture());
        assertThat(cap.getValue()).isBetween(before, after);
    }
}
