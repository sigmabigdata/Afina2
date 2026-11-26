package ru.afina.demo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DemoRoutingDataSource: соединение по тенанту сессии")
class DemoRoutingDataSourceTest {

    private final DemoTenantConnectionProvider provider = mock(DemoTenantConnectionProvider.class);
    private final DemoRoutingDataSource routing = new DemoRoutingDataSource(provider);

    @AfterEach
    void clear() {
        DemoTenantContext.clear();
    }

    @Test
    @DisplayName("соединение берётся из БД текущего тенанта")
    void routesByTenant() throws Exception {
        Connection conn = mock(Connection.class);
        when(provider.getConnection("s-tenant")).thenReturn(conn);
        DemoTenantContext.set("s-tenant");

        assertThat(routing.getConnection()).isSameAs(conn);
        verify(provider).getConnection("s-tenant");
        assertThat(routing.getConnection("u", "p")).isSameAs(conn);
    }

    @Test
    @DisplayName("без тенанта — шаблонная БД")
    void defaultsToTemplate() throws Exception {
        Connection conn = mock(Connection.class);
        when(provider.getConnection(DemoTenantContext.TEMPLATE_TENANT)).thenReturn(conn);

        assertThat(routing.getConnection()).isSameAs(conn);
        verify(provider).getConnection(DemoTenantContext.TEMPLATE_TENANT);
    }

    @Test
    @DisplayName("unwrap отдаёт сам DataSource")
    void unwrapSelf() throws Exception {
        assertThat(routing.isWrapperFor(DemoRoutingDataSource.class)).isTrue();
        assertThat(routing.unwrap(DemoRoutingDataSource.class)).isSameAs(routing);
    }

    @Test
    @DisplayName("служебные методы делегируются шаблонной БД")
    void delegatesToTemplate() throws Exception {
        DataSource template = mock(DataSource.class);
        when(provider.poolOf(DemoTenantContext.TEMPLATE_TENANT)).thenReturn(template);
        when(template.getLoginTimeout()).thenReturn(7);

        assertThat(routing.getLoginTimeout()).isEqualTo(7);
        routing.setLoginTimeout(3);
        verify(template).setLoginTimeout(3);
    }
}
