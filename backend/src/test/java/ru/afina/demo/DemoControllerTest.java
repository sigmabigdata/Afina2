package ru.afina.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.service.UserService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("DemoController: авто-вход в демо")
class DemoControllerTest {

    private final UserService userService = mock(UserService.class);
    private final HttpSessionSecurityContextRepository adminRepo =
            mock(HttpSessionSecurityContextRepository.class);
    private final DemoTenantService tenantService = mock(DemoTenantService.class);
    private final DemoController controller = new DemoController(userService, adminRepo, tenantService);

    private static User admin() {
        User u = new User();
        u.setId(1L);
        u.setEmail("demo-admin@afina.local");
        u.setFullName("Демо Администратор");
        u.setRole(Role.ROLE_ADMIN);
        u.setEnabled(true);
        return u;
    }

    @Test
    @DisplayName("вход: создаётся сессия с тенантом, наполняются данные, редирект в админку")
    void enterCreatesSessionAndTenant() {
        when(userService.findByEmail(any())).thenReturn(Optional.of(admin()));
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        String view = controller.enter(req, resp);

        assertThat(view).isEqualTo("redirect:/admin");
        MockHttpSession session = (MockHttpSession) req.getSession(false);
        assertThat(session).isNotNull();
        String tenant = (String) session.getAttribute(DemoTenantFilter.TENANT_ATTR);
        assertThat(tenant).startsWith("s");
        verify(tenantService).ensureSeeded(tenant);
        verify(adminRepo).saveContext(any(), any(), any());
    }

    @Test
    @DisplayName("повторный вход в той же сессии — тот же тенант")
    void enterReusesTenant() {
        when(userService.findByEmail(any())).thenReturn(Optional.of(admin()));
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(DemoTenantFilter.TENANT_ATTR, "s-existing");
        req.setSession(session);

        controller.enter(req, new MockHttpServletResponse());

        verify(tenantService).ensureSeeded("s-existing");
        assertThat(session.getAttribute(DemoTenantFilter.TENANT_ATTR)).isEqualTo("s-existing");
    }

    @Test
    @DisplayName("нет демо-администратора → редирект на /login?expired")
    void enterWithoutAdmin() {
        when(userService.findByEmail(any())).thenReturn(Optional.empty());

        String view = controller.enter(new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(view).isEqualTo("redirect:/login?expired");
        verifyNoInteractions(adminRepo);
    }
}
