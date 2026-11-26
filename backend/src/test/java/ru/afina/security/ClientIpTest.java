package ru.afina.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Определение реального IP клиента: защита от подмены заголовков.
 *
 * <p>Заголовкам доверяем только от прокси в приватной сети — иначе клиент мог бы
 * подделать адрес и обойти IP-whitelist административной панели.</p>
 */
class ClientIpTest {

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    @Test
    @DisplayName("Прямой запрос: заголовки игнорируются, берётся remoteAddr")
    void ignoresHeadersFromUntrustedClient() {
        MockHttpServletRequest req = request("203.0.113.7");
        req.addHeader("X-Real-IP", "10.0.0.1");
        req.addHeader("X-Forwarded-For", "10.0.0.1");
        assertThat(ClientIp.resolve(req)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("Прокси в loopback: доверяем X-Real-IP")
    void trustsRealIpFromLoopback() {
        MockHttpServletRequest req = request("127.0.0.1");
        req.addHeader("X-Real-IP", "198.51.100.42");
        assertThat(ClientIp.resolve(req)).isEqualTo("198.51.100.42");
    }

    @Test
    @DisplayName("Прокси в docker-сети: доверяем X-Forwarded-For (последний в цепочке)")
    void trustsXffFromDockerNetwork() {
        MockHttpServletRequest req = request("172.18.0.5");
        req.addHeader("X-Forwarded-For", "1.1.1.1, 198.51.100.42");
        assertThat(ClientIp.resolve(req)).isEqualTo("198.51.100.42");
    }

    @Test
    @DisplayName("X-Forwarded-For с мусором: берётся последний валидный IP")
    void skipsInvalidXffValues() {
        MockHttpServletRequest req = request("10.1.2.3");
        req.addHeader("X-Forwarded-For", "not-an-ip, 198.51.100.42, 999.999.999.999");
        assertThat(ClientIp.resolve(req)).isEqualTo("198.51.100.42");
    }

    @Test
    @DisplayName("Без заголовков от прокси: возвращается адрес прокси")
    void proxyWithoutHeaders() {
        MockHttpServletRequest req = request("192.168.1.10");
        assertThat(ClientIp.resolve(req)).isEqualTo("192.168.1.10");
    }

    @Test
    @DisplayName("IPv6-loopback считается доверенным прокси")
    void ipv6LoopbackIsTrusted() {
        assertThat(ClientIp.isTrustedProxy("::1")).isTrue();
        assertThat(ClientIp.isTrustedProxy("0:0:0:0:0:0:0:1")).isTrue();
        MockHttpServletRequest req = request("::1");
        req.addHeader("X-Real-IP", "198.51.100.9");
        assertThat(ClientIp.resolve(req)).isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("Приватные диапазоны: 10/8, 192.168/16, 172.16–172.31")
    void trustedRanges() {
        assertThat(ClientIp.isTrustedProxy("10.0.0.1")).isTrue();
        assertThat(ClientIp.isTrustedProxy("192.168.0.1")).isTrue();
        assertThat(ClientIp.isTrustedProxy("172.16.0.1")).isTrue();
        assertThat(ClientIp.isTrustedProxy("172.31.255.255")).isTrue();
        assertThat(ClientIp.isTrustedProxy("172.32.0.1")).isFalse();
        assertThat(ClientIp.isTrustedProxy("172.15.0.1")).isFalse();
        assertThat(ClientIp.isTrustedProxy("172.")).isFalse();
        assertThat(ClientIp.isTrustedProxy("172.xx.0.1")).isFalse();
        assertThat(ClientIp.isTrustedProxy("8.8.8.8")).isFalse();
        assertThat(ClientIp.isTrustedProxy("")).isFalse();
        assertThat(ClientIp.isTrustedProxy(null)).isFalse();
    }

    @Test
    @DisplayName("Валидация IP-литералов: IPv4, IPv6, скобки, hostname")
    void ipLiterals() {
        assertThat(ClientIp.isIpLiteral("203.0.113.5")).isTrue();
        assertThat(ClientIp.isIpLiteral(" 203.0.113.5 ")).isTrue();
        assertThat(ClientIp.isIpLiteral("2001:db8::1")).isTrue();
        assertThat(ClientIp.isIpLiteral("[2001:db8::1]")).isTrue();
        assertThat(ClientIp.isIpLiteral("example.com")).isFalse();
        assertThat(ClientIp.isIpLiteral("1.2.3")).isFalse();
        assertThat(ClientIp.isIpLiteral("1234.2.3.4")).isFalse();
        assertThat(ClientIp.isIpLiteral("")).isFalse();
        assertThat(ClientIp.isIpLiteral(null)).isFalse();
        assertThat(ClientIp.isIpLiteral("a".repeat(50))).isFalse();
    }

    @Test
    @DisplayName("Пустой запрос → null")
    void nullRequest() {
        assertThat(ClientIp.resolve(null)).isNull();
    }

    @Test
    @DisplayName("Пустые заголовки не ломают разбор")
    void blankHeaders() {
        MockHttpServletRequest req = request("127.0.0.1");
        req.addHeader("X-Real-IP", "   ");
        req.addHeader("X-Forwarded-For", "  ");
        assertThat(ClientIp.resolve(req)).isEqualTo("127.0.0.1");
    }
}
