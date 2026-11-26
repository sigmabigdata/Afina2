package ru.afina.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * DataSource-маршрутизатор: каждая транзакция берёт соединение из H2-базы
 * своего демо-тенанта ({@link DemoTenantContext}). Так у каждой сессии демо —
 * полностью отдельные данные, а Hibernate видит один обычный DataSource.
 */
@Component
@Profile("demo")
public class DemoRoutingDataSource implements DataSource {

    private final DemoTenantConnectionProvider provider;

    public DemoRoutingDataSource(DemoTenantConnectionProvider provider) {
        this.provider = provider;
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DemoRoutingDataSource.class);

    @Override
    public Connection getConnection() throws SQLException {
        String t = DemoTenantContext.get();
        log.debug("DEMO ds connection → tenant {}", t);
        return provider.getConnection(t);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    // ─── остальное делегируем в шаблонную базу ───────────────────

    private DataSource template() {
        return provider.poolOf(DemoTenantContext.TEMPLATE_TENANT);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return template().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        template().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        template().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return template().getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        return template().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        if (iface.isInstance(this)) return true;
        try {
            return template().isWrapperFor(iface);
        } catch (SQLException e) {
            return false;
        }
    }
}
