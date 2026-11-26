package ru.afina.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.afina.repository.AuditEventRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Периодическая очистка старых записей аудита.
 *
 * Retention задаётся в app_settings → audit.retention_days (по умолчанию 365).
 * Запускается ежедневно в 04:00.
 */
@Service
public class AuditCleanupService {

    private static final Logger log = LoggerFactory.getLogger(AuditCleanupService.class);
    /** В демо фоновые задачи не нужны: у каждой сессии своя одноразовая БД. */
    @org.springframework.beans.factory.annotation.Value("${app.demo.enabled:false}")
    private boolean demoMode;

    private final AuditEventRepository repo;
    private final SettingsService settings;

    public AuditCleanupService(AuditEventRepository repo, SettingsService settings) {
        this.repo = repo;
        this.settings = settings;
    }

    /** Ежедневно в 04:00 по TZ контейнера. */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void dailyCleanup() {
        if (demoMode) return;
        int days = settings.getIntOrDefault("audit.retention_days", 365);
        if (days <= 0) {
            log.info("audit cleanup: retention={} — отключено", days);
            return;
        }
        try {
            long deleted = doCleanup(days);
            settings.set("audit.last_cleanup_at", Instant.now().toString(), "system");
            settings.set("audit.last_cleanup_deleted", String.valueOf(deleted), "system");
            log.info("audit cleanup: удалено {} записей старше {} дней", deleted, days);
        } catch (Exception e) {
            log.error("audit cleanup failed", e);
        }
    }

    /** Ручной запуск из UI. */
    @Transactional
    public long cleanup(int days) {
        return doCleanup(days);
    }

    /**
     * Тело очистки. Вызывается из публичных @Transactional методов
     * (cleanup() и dailyCleanup()), чтобы не было self-invocation
     * (Sonar java:S2229).
     *
     * <p><b>Важно:</b> {@code deleteByEventTimeBefore()} — derived delete
     * query, она <b>требует активной транзакции</b>. Без {@code @Transactional}
     * на вызывающем методе падает с {@code TransactionRequiredException}.
     * Поэтому @Transactional должен быть на обоих публичных методах.</p>
     */
    private long doCleanup(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        long before = repo.count();
        long deleted = repo.deleteByEventTimeBefore(cutoff);
        long after = repo.count();
        log.info("audit cleanup: было {}, стало {}, удалено {}", before, after, deleted);
        return deleted;
    }
}
