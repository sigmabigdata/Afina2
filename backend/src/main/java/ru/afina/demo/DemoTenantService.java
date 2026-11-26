package ru.afina.demo;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Жизненный цикл демо-тенанта: при входе в демо создаём персональную БД и
 * наполняем её демо-данными; при завершении сессии — удаляем.
 */
@Component
@Profile("demo")
public class DemoTenantService implements HttpSessionListener {

    private static final Logger log = LoggerFactory.getLogger(DemoTenantService.class);

    private final DemoTenantConnectionProvider provider;
    private final DemoDataInitializer seeder;

    public DemoTenantService(DemoTenantConnectionProvider provider, DemoDataInitializer seeder) {
        this.provider = provider;
        this.seeder = seeder;
    }

    /** Гарантирует, что у тенанта есть своя БД и демо-данные (идемпотентно). */
    public synchronized void ensureSeeded(String tenant) {
        if (tenant == null) return;
        // сначала коснуться пула — создаст БД и схему
        provider.poolOf(tenant);
        if (!provider.isNewTenant(tenant)) {
            return;
        }
        String previous = DemoTenantContext.get();
        DemoTenantContext.set(tenant);
        try {
            seeder.seedDemoData();
            provider.markSeeded(tenant);
            log.info("DEMO: данные для сессии {} созданы", tenant);
        } catch (Exception e) {
            log.error("DEMO: не удалось создать данные для сессии {}", tenant, e);
            throw new IllegalStateException("demo seeding failed", e);
        } finally {
            DemoTenantContext.set(previous);
        }
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        Object v = se.getSession().getAttribute(DemoTenantFilter.TENANT_ATTR);
        if (v instanceof String tenant) {
            provider.dropTenant(tenant);
        }
    }
}
