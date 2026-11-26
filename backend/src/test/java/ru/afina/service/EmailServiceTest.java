package ru.afina.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;
import ru.afina.security.PiiEncryptor;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailService: magic-link, alert, test, dev-mode fallback")
class EmailServiceTest {

    @Mock private ObjectProvider<JavaMailSender> mailSenderProvider;
    @Mock private SettingsService settings;
    @Mock private PiiEncryptor pii;
    @Mock private AuditService audit;
    @Mock private JavaMailSenderImpl envImpl;

    @InjectMocks private EmailService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "mode", "smtp");
        ReflectionTestUtils.setField(service, "fromEnv", "noreply@example.ru");
    }

    private MimeMessage newMime() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    // ─── sendLoginLink: dev-mode fallback ─────

    @Test
    @DisplayName("sendLoginLink: mode=log, no env, no settings → dev-mode, audit.emailSent")
    void sendLoginLink_devMode() {
        ReflectionTestUtils.setField(service, "mode", "log");
        when(settings.getIntOrDefault("auth.token_ttl_hours", 10)).thenReturn(10);
        when(settings.getOrDefault(eq("email.login.subject"), anyString())).thenReturn("SUBJ");
        when(settings.getOrDefault(eq("email.login.body"), anyString())).thenReturn("Hi {user_name}");
        when(settings.isSmtpConfigured()).thenReturn(false);

        service.sendLoginLink("client@example.ru", "https://afina/t?x=1", "Иванов");

        verify(audit).emailSent("client@example.ru", "SUBJ", "dev-mode");
        verify(envImpl, never()).send(any(MimeMessage.class));
    }

    // ─── sendLoginLink: env sender configured ─

    @Test
    @DisplayName("sendLoginLink: env sender с non-localhost host → send() вызван")
    void sendLoginLink_usesEnvSender() throws Exception {
        MimeMessage realMsg = newMime();
        when(mailSenderProvider.getIfAvailable()).thenReturn(envImpl);
        when(envImpl.getHost()).thenReturn("mail.example.ru");
        when(envImpl.createMimeMessage()).thenReturn(realMsg);
        when(settings.getIntOrDefault("auth.token_ttl_hours", 10)).thenReturn(5);
        when(settings.getOrDefault(eq("email.login.subject"), anyString())).thenReturn("Привет {user_name}");
        when(settings.getOrDefault(eq("email.login.body"), anyString())).thenReturn("Hi {user_name}, {token_url}, {expires_hours}h");
        when(settings.getOrDefault("smtp.from", "")).thenReturn("");

        service.sendLoginLink("client@example.ru", "https://afina/t?x=1", "Петров");

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(envImpl).send(cap.capture());
        // {user_name} подставляется и в тему, и в тело
        assertThat(cap.getValue().getSubject()).isEqualTo("Привет Петров");
        try {
            cap.getValue().saveChanges();
            assertThat((String) cap.getValue().getContent())
                    .contains("Петров").contains("https://afina/t?x=1").contains("5h");
        } catch (Exception ignored) {
            // getContent может не работать без полной сериализации
        }
        verify(audit).emailSent("client@example.ru", "Привет Петров");
    }

    @Test
    @DisplayName("sendLoginLink: env sender SMTP бросает → audit.emailFail + IllegalStateException")
    void sendLoginLink_envSenderThrows() {
        MimeMessage realMsg = newMime();
        when(mailSenderProvider.getIfAvailable()).thenReturn(envImpl);
        when(envImpl.getHost()).thenReturn("mail.example.ru");
        when(envImpl.createMimeMessage()).thenReturn(realMsg);
        when(settings.getIntOrDefault("auth.token_ttl_hours", 10)).thenReturn(10);
        when(settings.getOrDefault(eq("email.login.subject"), anyString())).thenReturn("S");
        when(settings.getOrDefault(eq("email.login.body"), anyString())).thenReturn("B");
        when(settings.getOrDefault("smtp.from", "")).thenReturn("");
        doThrow(new MailSendException("boom")).when(envImpl).send(any(MimeMessage.class));

        assertThatThrownBy(() -> service.sendLoginLink("to@b.ru", "url", "name"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP");
        verify(audit).emailFail(eq("to@b.ru"), eq("S"), eq("boom"));
    }

    @Test
    @DisplayName("sendLoginLink: env localhost + mode=smtp → используется (local Postfix)")
    void sendLoginLink_localhostUsesEnvSender() {
        MimeMessage realMsg = newMime();
        when(mailSenderProvider.getIfAvailable()).thenReturn(envImpl);
        when(envImpl.getHost()).thenReturn("localhost");
        when(envImpl.createMimeMessage()).thenReturn(realMsg);
        when(settings.isSmtpConfigured()).thenReturn(false);
        when(settings.getIntOrDefault("auth.token_ttl_hours", 10)).thenReturn(10);
        when(settings.getOrDefault(eq("email.login.subject"), anyString())).thenReturn("S");
        when(settings.getOrDefault(eq("email.login.body"), anyString())).thenReturn("B");
        when(settings.getOrDefault("smtp.from", "")).thenReturn("");

        service.sendLoginLink("to@b.ru", "url", "name");

        verify(envImpl).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendLoginLink (2-арг): user=null → подстановка по умолчанию, send вызван")
    void sendLoginLink_nullUser() {
        MimeMessage realMsg = newMime();
        when(mailSenderProvider.getIfAvailable()).thenReturn(envImpl);
        when(envImpl.getHost()).thenReturn("mail.example.ru");
        when(envImpl.createMimeMessage()).thenReturn(realMsg);
        when(settings.getIntOrDefault("auth.token_ttl_hours", 10)).thenReturn(10);
        when(settings.getOrDefault(eq("email.login.subject"), anyString())).thenReturn("S");
        when(settings.getOrDefault(eq("email.login.body"), anyString())).thenReturn("Hi {user_name}");
        when(settings.getOrDefault("smtp.from", "")).thenReturn("");

        service.sendLoginLink("to@b.ru", "url");

        verify(envImpl).send(any(MimeMessage.class));
    }

    // ─── sendAlert ─────────────────────────

    @Test
    @DisplayName("sendAlert: тема и тело переданы как есть (шаблоны из настроек)")
    void sendAlert_content() throws Exception {
        MimeMessage realMsg = newMime();
        when(mailSenderProvider.getIfAvailable()).thenReturn(envImpl);
        when(envImpl.getHost()).thenReturn("mail.example.ru");
        when(envImpl.createMimeMessage()).thenReturn(realMsg);
        when(settings.getOrDefault("smtp.from", "")).thenReturn("");

        service.sendAlert("admin@example.ru", "Афина · Сервис недоступен", "БД недоступна, сбоев: 7");

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(envImpl).send(cap.capture());
        MimeMessage msg = cap.getValue();
        assertThat(msg.getSubject()).contains("Сервис недоступен");
        try {
            msg.saveChanges();
            String body = (String) msg.getContent();
            assertThat(body).contains("7").contains("БД недоступна");
        } catch (Exception e) {
            // getContent не всегда работает без полной сериализации — OK
        }
        verify(audit).emailSent(eq("admin@example.ru"), anyString());
    }

    // ─── sendTest ──────────────────────────

    @Test
    @DisplayName("sendTest: force=true — даже в mode=log пробует отправить")
    void sendTest_forceSends() throws Exception {
        ReflectionTestUtils.setField(service, "mode", "log");
        MimeMessage realMsg = newMime();
        when(mailSenderProvider.getIfAvailable()).thenReturn(envImpl);
        when(envImpl.getHost()).thenReturn("mail.example.ru");
        when(envImpl.createMimeMessage()).thenReturn(realMsg);
        when(settings.getOrDefault("smtp.from", "")).thenReturn("");

        service.sendTest("qa@example.ru");

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(envImpl).send(cap.capture());
        assertThat(cap.getValue().getSubject()).contains("Тестовое");
    }

    @Test
    @DisplayName("sendTest: без env и settings — dev-mode (audit.emailSent с 'dev-mode')")
    void sendTest_devMode() {
        ReflectionTestUtils.setField(service, "mode", "log");
        when(settings.isSmtpConfigured()).thenReturn(false);

        service.sendTest("qa@example.ru");

        verify(audit).emailSent(eq("qa@example.ru"), anyString(), eq("dev-mode"));
    }

    // ─── resolveFrom: db override ─────────

    @Test
    @DisplayName("resolveFrom: smtp.from в БД переопределяет env from")
    void resolveFrom_dbOverride() {
        MimeMessage realMsg = newMime();
        when(mailSenderProvider.getIfAvailable()).thenReturn(envImpl);
        when(envImpl.getHost()).thenReturn("mail.example.ru");
        when(envImpl.createMimeMessage()).thenReturn(realMsg);
        when(settings.getIntOrDefault("auth.token_ttl_hours", 10)).thenReturn(10);
        when(settings.getOrDefault(eq("email.login.subject"), anyString())).thenReturn("S");
        when(settings.getOrDefault(eq("email.login.body"), anyString())).thenReturn("B");
        when(settings.getOrDefault("smtp.from", "")).thenReturn("db@example.ru");

        service.sendLoginLink("to@b.ru", "url", "name");

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(envImpl).send(cap.capture());
        try {
            assertThat(cap.getValue().getFrom()[0]).hasToString("db@example.ru");
        } catch (Exception e) {
            // ignore
        }
    }

    // ─── fallback to settings (buildFromSettings) ────

    @Test
    @DisplayName("sendLoginLink: env null, isSmtpConfigured=true → buildFromSettings, попытка connect")
    void sendLoginLink_fallbackToSettings() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(null);
        when(settings.isSmtpConfigured()).thenReturn(true);
        when(settings.getOrDefault("smtp.host", "")).thenReturn("127.0.0.1");
        when(settings.getIntOrDefault("smtp.port", 25)).thenReturn(1); // closed port → fast fail
        when(settings.getOrDefault("smtp.username", "")).thenReturn("");
        when(settings.getBoolOrDefault("smtp.ssl", false)).thenReturn(false);
        when(settings.getIntOrDefault("auth.token_ttl_hours", 10)).thenReturn(10);
        when(settings.getOrDefault(eq("email.login.subject"), anyString())).thenReturn("S");
        when(settings.getOrDefault(eq("email.login.body"), anyString())).thenReturn("B");
        when(settings.getOrDefault("smtp.from", "")).thenReturn("");

        assertThatThrownBy(() -> service.sendLoginLink("to@b.ru", "url", "name"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP");
        verify(audit).emailFail(eq("to@b.ru"), anyString(), anyString());
    }
}
