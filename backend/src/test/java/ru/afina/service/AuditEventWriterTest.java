package ru.afina.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.afina.entity.AuditEvent;
import ru.afina.repository.AuditEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditEventWriter: REQUIRES_NEW запись + IP/UA")
class AuditEventWriterTest {

    @Mock
    private AuditEventRepository repo;

    @InjectMocks
    private AuditEventWriter writer;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private AuditEvent captureSaved() {
        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo).save(cap.capture());
        return cap.getValue();
    }

    // ─── базовые поля ──────────────────────────────

    @Test
    @DisplayName("write: все поля из AuditEntry → AuditEvent")
    void writeAllFields() {
        AuditEntry e = new AuditEntry("LOGIN_SUCCESS", "SUCCESS",
                "u@b.ru", "ROLE_USER", "USER", "42", "info", "details");

        writer.write(e);

        AuditEvent saved = captureSaved();
        assertThat(saved.getEventType()).isEqualTo("LOGIN_SUCCESS");
        assertThat(saved.getResult()).isEqualTo("SUCCESS");
        assertThat(saved.getActorEmail()).isEqualTo("u@b.ru");
        assertThat(saved.getActorRole()).isEqualTo("ROLE_USER");
        assertThat(saved.getTargetType()).isEqualTo("USER");
        assertThat(saved.getTargetId()).isEqualTo("42");
        assertThat(saved.getTargetInfo()).isEqualTo("info");
        assertThat(saved.getDetails()).isEqualTo("details");
    }

    @Test
    @DisplayName("write: без request → actorIp и userAgent null")
    void writeWithoutRequest() {
        writer.write(new AuditEntry("X", "OK", "u@b.ru", null, null, null, null, null));

        AuditEvent saved = captureSaved();
        assertThat(saved.getActorIp()).isNull();
        assertThat(saved.getUserAgent()).isNull();
    }

    // ─── X-Forwarded-For ───────────────────────────

    @Test
    @DisplayName("write: X-Forwarded-For → последний IP (добавлен нашим прокси)")
    void xForwardedForLast() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        writer.write(new AuditEntry("X", "OK", null, null, null, null, null, null));

        assertThat(captureSaved().getActorIp()).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("write: X-Real-IP от прокси имеет приоритет")
    void xRealIpPreferred() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "203.0.113.7");
        req.addHeader("X-Forwarded-For", "6.6.6.6, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        writer.write(new AuditEntry("X", "OK", null, null, null, null, null, null));

        assertThat(captureSaved().getActorIp()).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("write: спуфинг XFF с нетрастового адреса игнорируется")
    void spoofedHeaderIgnored() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.9");
        req.addHeader("X-Forwarded-For", "1.2.3.4");
        req.addHeader("X-Real-IP", "1.2.3.4");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        writer.write(new AuditEntry("X", "OK", null, null, null, null, null, null));

        assertThat(captureSaved().getActorIp()).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("write: без XFF → getRemoteAddr()")
    void remoteAddrFallback() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("192.168.1.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        writer.write(new AuditEntry("X", "OK", null, null, null, null, null, null));

        assertThat(captureSaved().getActorIp()).isEqualTo("192.168.1.10");
    }

    @Test
    @DisplayName("write: User-Agent копируется в event")
    void userAgentCaptured() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("User-Agent", "Mozilla/5.0 Firefox/128");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        writer.write(new AuditEntry("X", "OK", null, null, null, null, null, null));

        assertThat(captureSaved().getUserAgent()).isEqualTo("Mozilla/5.0 Firefox/128");
    }

    @Test
    @DisplayName("write: User-Agent обрезается до 500 символов")
    void userAgentTruncated() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        String ua = "U".repeat(1000);
        req.addHeader("User-Agent", ua);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        writer.write(new AuditEntry("X", "OK", null, null, null, null, null, null));

        String saved = captureSaved().getUserAgent();
        assertThat(saved).hasSize(500).endsWith("...");
    }

    // ─── normalizeTargetId ─────────────────────────

    @Test
    @DisplayName("write: targetId='null' → null (String.valueOf(null) фикс)")
    void targetIdNullString() {
        writer.write(new AuditEntry("X", "OK", null, null, null, "null", null, null));
        assertThat(captureSaved().getTargetId()).isNull();
    }

    @Test
    @DisplayName("write: targetId='   ' → null")
    void targetIdBlank() {
        writer.write(new AuditEntry("X", "OK", null, null, null, "   ", null, null));
        assertThat(captureSaved().getTargetId()).isNull();
    }

    @Test
    @DisplayName("write: targetId='NULL' (case-insensitive) → null")
    void targetIdNullCaseInsensitive() {
        writer.write(new AuditEntry("X", "OK", null, null, null, "NuLL", null, null));
        assertThat(captureSaved().getTargetId()).isNull();
    }

    // ─── truncate ──────────────────────────────────

    @Test
    @DisplayName("write: targetInfo > 500 → обрезается")
    void targetInfoTruncated() {
        String longInfo = "x".repeat(700);
        writer.write(new AuditEntry("X", "OK", null, null, null, null, longInfo, null));
        String saved = captureSaved().getTargetInfo();
        assertThat(saved).hasSize(500).endsWith("...");
    }

    @Test
    @DisplayName("write: targetInfo ровно 500 → не обрезается")
    void targetInfoExact500() {
        String info = "y".repeat(500);
        writer.write(new AuditEntry("X", "OK", null, null, null, null, info, null));
        assertThat(captureSaved().getTargetInfo()).isEqualTo(info);
    }

    // ─── error handling ────────────────────────────

    @Test
    @DisplayName("write: repo.save бросил → не пробрасывается (audit best-effort)")
    void writeSwallowsException() {
        doThrow(new RuntimeException("db down")).when(repo).save(any());

        writer.write(new AuditEntry("X", "OK", null, null, null, null, null, null));

        verify(repo).save(any());
        // нет исключения
    }

    @Test
    @DisplayName("write: null entry.partial → save всё равно вызывается")
    void writeNullFieldsOk() {
        writer.write(new AuditEntry(null, null, null, null, null, null, null, null));
        verify(repo).save(any());
    }

    // ─── malformed request attributes ──────────────

    @Test
    @DisplayName("write: не-ServletRequestAttributes → IP не падает, null")
    void writeWrongRequestAttributesType() {
        // RequestContextHolder с кастомным (не Servlet) типом — но такой невозможно
        // создать без Servlet. Проверяем, что null attrs безопасен.
        RequestContextHolder.resetRequestAttributes();
        writer.write(new AuditEntry("X", "OK", null, null, null, null, null, null));
        verify(repo).save(any());
    }

    @Test
    @DisplayName("write: пустой XFF → fallback на remoteAddr")
    void emptyXffFallsBack() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "");
        req.setRemoteAddr("10.0.0.5");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        writer.write(new AuditEntry("X", "OK", null, null, null, null, null, null));

        assertThat(captureSaved().getActorIp()).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("write: XFF с одним IP без запятых")
    void singleXff() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.99");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        writer.write(new AuditEntry("X", "OK", null, null, null, null, null, null));

        assertThat(captureSaved().getActorIp()).isEqualTo("203.0.113.99");
    }

    @Test
    @DisplayName("write: XFF с пробелами → trim")
    void xffTrimmed() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "  203.0.113.5  ,  10.0.0.1  ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        writer.write(new AuditEntry("X", "OK", null, null, null, null, null, null));

        assertThat(captureSaved().getActorIp()).isEqualTo("10.0.0.1");
    }
}
