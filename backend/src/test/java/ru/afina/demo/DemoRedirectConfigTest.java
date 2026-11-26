package ru.afina.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DemoRedirectConfig: страница входа ведёт в демо")
class DemoRedirectConfigTest {

    @Test
    @DisplayName("GET /login → редирект на /enter (с учётом context path)")
    void redirectsGetLogin() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/demo/login");
        req.setRequestURI("/demo/login");
        req.setContextPath("/demo");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean proceed = DemoRedirectConfig.demoLoginRedirect(req, resp);

        assertThat(proceed).isFalse();
        assertThat(resp.getRedirectedUrl()).isEqualTo("/demo/enter");
    }

    @Test
    @DisplayName("POST /login не перехватывается")
    void allowsPostLogin() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/demo/login");
        req.setRequestURI("/demo/login");
        req.setContextPath("/demo");

        assertThat(DemoRedirectConfig.demoLoginRedirect(req, new MockHttpServletResponse()))
                .isTrue();
    }

    @Test
    @DisplayName("прочие пути не перехватываются")
    void allowsOtherPaths() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/demo/admin");
        req.setRequestURI("/demo/admin");
        req.setContextPath("/demo");

        assertThat(DemoRedirectConfig.demoLoginRedirect(req, new MockHttpServletResponse()))
                .isTrue();
    }
}
