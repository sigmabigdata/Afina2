package ru.afina.demo;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DemoTenantService: наполнение и удаление БД сессии")
class DemoTenantServiceTest {

    private final DemoTenantConnectionProvider provider = mock(DemoTenantConnectionProvider.class);
    private final DemoDataInitializer seeder = mock(DemoDataInitializer.class);
    private final DemoTenantService service = new DemoTenantService(provider, seeder);

    @Test
    @DisplayName("новый тенант наполняется один раз")
    void seedsNewTenantOnce() throws Exception {
        when(provider.isNewTenant("s1")).thenReturn(true);

        service.ensureSeeded("s1");

        verify(seeder).seedDemoData();
        verify(provider).markSeeded("s1");
    }

    @Test
    @DisplayName("уже наполненный тенант не наполняется повторно")
    void skipsSeededTenant() {
        when(provider.isNewTenant("s2")).thenReturn(false);

        service.ensureSeeded("s2");

        verify(provider, never()).markSeeded(any());
    }

    @Test
    @DisplayName("null-тенант игнорируется")
    void ignoresNullTenant() {
        service.ensureSeeded(null);
        verify(provider, never()).poolOf(any());
    }

    @Test
    @DisplayName("при завершении сессии её БД удаляется")
    void dropsTenantOnSessionDestroyed() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(DemoTenantFilter.TENANT_ATTR, "s-destroy");
        HttpSessionEvent event = new HttpSessionEvent(session);

        service.sessionDestroyed(event);

        verify(provider).dropTenant("s-destroy");
    }

    @Test
    @DisplayName("сессия без тенанта — ничего не удаляем")
    void ignoresSessionsWithoutTenant() {
        HttpSession session = new MockHttpSession();
        service.sessionDestroyed(new HttpSessionEvent(session));
        verify(provider, never()).dropTenant(any());
    }
}
