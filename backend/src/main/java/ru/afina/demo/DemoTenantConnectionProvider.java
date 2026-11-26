package ru.afina.demo;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Изоляция демо-данных: на каждый tenant (демо-сессию) — своя H2 in-memory БД.
 *
 * <p>Схема для новой базы копируется из шаблонной базы ({@link DemoTenantContext#TEMPLATE_TENANT})
 * командой H2 {@code SCRIPT NODATA} (DDL без данных), после чего сессия наполняется
 * демо-данными (см. {@link DemoTenantService}).
 */
@Component
@Profile("demo")
public class DemoTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private static final Logger log = LoggerFactory.getLogger(DemoTenantConnectionProvider.class);

    private static final String DB_OPTIONS =
            "MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=KEY,VALUE";

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    /** Порядок создания тенантов — чтобы при переполнении вытеснять самый старый. */
    private final java.util.Deque<String> creationOrder = new java.util.concurrent.ConcurrentLinkedDeque<>();

    /** Максимум одновременно живых демо-баз (защита памяти прода). */
    @org.springframework.beans.factory.annotation.Value("${app.demo.max-tenants:50}")
    private int maxTenants = 50;
    /** Тенанты, у которых база уже создана, но данные ещё не наполнены. */
    private final Set<String> pendingSeed = ConcurrentHashMap.newKeySet();

    // ─── схема из шаблона ─────────────────────────────────────────

    private List<String> templateDdl() {
        List<String> statements = new ArrayList<>();
        try (Connection c = dataSource(DemoTenantContext.TEMPLATE_TENANT).getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SCRIPT NODATA")) {
            StringBuilder current = new StringBuilder();
            while (rs.next()) {
                String line = rs.getString(1);
                if (line == null || line.startsWith("--")) continue;
                current.append(line).append('\n');
                if (line.trim().endsWith(";")) {
                    String ddl = current.toString().trim();
                    if (!ddl.isEmpty()) statements.add(ddl);
                    current.setLength(0);
                }
            }
        } catch (Exception e) {
            log.error("DEMO: не удалось получить DDL шаблона", e);
            throw new IllegalStateException("demo template DDL failed", e);
        }
        return statements;
    }

    private HikariDataSource dataSource(String tenant) {
        evictIfNeeded(tenant);
        return pools.computeIfAbsent(tenant, t -> {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl("jdbc:h2:mem:afinademo_" + t + ";" + DB_OPTIONS);
            cfg.setUsername("sa");
            cfg.setPassword("");
            cfg.setDriverClassName("org.h2.Driver");
            cfg.setMaximumPoolSize(4);
            cfg.setPoolName("demo-" + t);
            HikariDataSource ds = new HikariDataSource(cfg);
            if (!DemoTenantContext.TEMPLATE_TENANT.equals(t)) {
                initTenantSchema(t, ds);
            }
            creationOrder.add(t);
            log.info("DEMO: создана изолированная БД для сессии {}", t);
            return ds;
        });
    }

    /** Вытесняет самые старые демо-базы, если лимит исчерпан. */
    private void evictIfNeeded(String requested) {
        while (sessionCount() >= maxTenants) {
            String oldest = null;
            for (String t : creationOrder) {
                if (!DemoTenantContext.TEMPLATE_TENANT.equals(t) && !t.equals(requested)) {
                    oldest = t;
                    break;
                }
            }
            if (oldest == null) break;
            log.warn("DEMO: лимит {} баз достигнут — удаляю самую старую сессию {}", maxTenants, oldest);
            dropTenant(oldest);
        }
    }

    /** Число демо-БАЗ (шаблонная не в счёт лимита). */
    private int sessionCount() {
        return pools.size() - (pools.containsKey(DemoTenantContext.TEMPLATE_TENANT) ? 1 : 0);
    }

    private void initTenantSchema(String tenant, HikariDataSource ds) {
        List<String> ddl = templateDdl();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String sql : ddl) {
                // CREATE USER из шаблона не нужен: пользователь уже есть в новой БД
                if (sql.toUpperCase().startsWith("CREATE USER")) continue;
                st.execute(sql);
            }
        } catch (Exception e) {
            throw new IllegalStateException("demo schema init failed for " + tenant, e);
        }
        pendingSeed.add(tenant);
    }

    // ─── API ─────────────────────────────────────────────────────

    /** true — база создана только что и требует наполнения демо-данными. */
    public boolean isNewTenant(String tenant) {
        return pendingSeed.contains(tenant);
    }

    public void markSeeded(String tenant) {
        pendingSeed.remove(tenant);
    }

    /** Полностью удаляем базу сессии (при logout/истечении сессии). */
    public void dropTenant(String tenant) {
        if (tenant == null || DemoTenantContext.TEMPLATE_TENANT.equals(tenant)) return;
        pendingSeed.remove(tenant);
        creationOrder.remove(tenant);
        HikariDataSource ds = pools.remove(tenant);
        if (ds != null) {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // база и так закроется
            }
            ds.close();
            log.info("DEMO: БД сессии {} удалена", tenant);
        }
    }

    // ─── MultiTenantConnectionProvider ───────────────────────────

    @Override
    public Connection getAnyConnection() throws java.sql.SQLException {
        return dataSource(DemoTenantContext.TEMPLATE_TENANT).getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws java.sql.SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws java.sql.SQLException {
        return dataSource(tenantIdentifier).getConnection();
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection)
            throws java.sql.SQLException {
        connection.close();
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return unwrapType.isInstance(this);
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        if (unwrapType.isInstance(this)) {
            return unwrapType.cast(this);
        }
        throw new jakarta.persistence.PersistenceException(
                "Не могу развернуть DemoTenantConnectionProvider в " + unwrapType.getName());
    }

    /** Доступ к пулу конкретного тенанта (для служебных нужд). */
    public DataSource poolOf(String tenant) {
        return dataSource(tenant);
    }

    /** Все живые тенанты — для диагностики. */
    public Set<String> activeTenants() {
        return new LinkedHashSet<>(pools.keySet());
    }
}
