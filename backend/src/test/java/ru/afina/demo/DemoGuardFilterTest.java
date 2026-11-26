package ru.afina.demo;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("DemoGuardFilter: закрытые разделы и вход админа")
class DemoGuardFilterTest {

    private final DemoGuardFilter filter = new DemoGuardFilter();

    @Test
    @DisplayName("/admin/system → заглушка со ссылкой в демо-админку")
    void blockedSectionShowsNotice() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/demo/admin/system");
        req.setRequestURI("/demo/admin/system");
        req.setContextPath("/demo");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getContentAsString())
                .contains("В демо-версии недоступно")
                .contains("href=\"/demo/admin\"");
    }

    @Test
    @DisplayName("/admin/login в демо → редирект на авто-вход")
    void adminLoginRedirects() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/demo/admin/login");
        req.setRequestURI("/demo/admin/login");
        req.setContextPath("/demo");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, mock(FilterChain.class));

        assertThat(resp.getStatus()).isEqualTo(302);
        assertThat(resp.getRedirectedUrl()).isEqualTo("/demo/enter");
    }

    @Test
    @DisplayName("обычная страница проходит дальше")
    void normalPagePasses() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/demo/admin/users");
        req.setRequestURI("/demo/admin/users");
        req.setContextPath("/demo");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
    }

    @Test
    @DisplayName("вне демо-контекста фильтр не блокирует")
    void ignoresForeignPaths() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/users");
        req.setRequestURI("/admin/users");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        assertThat(resp.getContentAsString()).doesNotContain("В демо-версии недоступно");
    }
}
