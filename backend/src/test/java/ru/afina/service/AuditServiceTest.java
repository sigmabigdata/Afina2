package ru.afina.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditService: фасад над AuditEventWriter")
class AuditServiceTest {

    @Mock
    private AuditEventWriter writer;

    @InjectMocks
    private AuditService service;

    private AuditEntry captureEntry() {
        ArgumentCaptor<AuditEntry> cap = ArgumentCaptor.forClass(AuditEntry.class);
        verify(writer).write(cap.capture());
        return cap.getValue();
    }

    // ─── Auth ───────────────────────────────────────

    @Test
    @DisplayName("loginSuccess: OK, role, без target")
    void loginSuccess() {
        service.loginSuccess("a@b.ru", "ROLE_USER");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("LOGIN_SUCCESS");
        assertThat(e.result()).isEqualTo("SUCCESS");
        assertThat(e.actorEmail()).isEqualTo("a@b.ru");
        assertThat(e.actorRole()).isEqualTo("ROLE_USER");
        assertThat(e.targetType()).isNull();
    }

    @Test
    @DisplayName("loginFail: FAIL + reason в details")
    void loginFail() {
        service.loginFail("a@b.ru", "bad token");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("LOGIN_FAIL");
        assertThat(e.result()).isEqualTo("FAIL");
        assertThat(e.details()).isEqualTo("bad token");
    }

    @Test
    @DisplayName("adminLoginSuccess: ROLE_ADMIN")
    void adminLoginSuccess() {
        service.adminLoginSuccess("Иванов Иван");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("ADMIN_LOGIN_SUCCESS");
        assertThat(e.actorRole()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    @DisplayName("adminLoginFail: FAIL + reason")
    void adminLoginFail() {
        service.adminLoginFail("CN", "cert revoked");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("ADMIN_LOGIN_FAIL");
        assertThat(e.result()).isEqualTo("FAIL");
        assertThat(e.details()).isEqualTo("cert revoked");
    }

    // ─── Users ──────────────────────────────────────

    @Test
    @DisplayName("userCreate: target USER #id")
    void userCreate() {
        service.userCreate("admin", 42L, "u@b.ru");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("USER_CREATE");
        assertThat(e.targetType()).isEqualTo("USER");
        assertThat(e.targetId()).isEqualTo("42");
        assertThat(e.targetInfo()).isEqualTo("u@b.ru");
    }

    @Test
    @DisplayName("userUpdate / userDelete: target USER #id")
    void userUpdateDelete() {
        service.userUpdate("admin", 1L, "u@b.ru");
        service.userDelete("admin", 2L, "d@b.ru");

        ArgumentCaptor<AuditEntry> cap = ArgumentCaptor.forClass(AuditEntry.class);
        verify(writer, times(2)).write(cap.capture());
        assertThat(cap.getAllValues().get(0).type()).isEqualTo("USER_UPDATE");
        assertThat(cap.getAllValues().get(1).type()).isEqualTo("USER_DELETE");
    }

    // ─── Documents ──────────────────────────────────

    @Test
    @DisplayName("documentUpload: target DOCUMENT #id")
    void documentUpload() {
        service.documentUpload("admin", 7L, "file.pdf");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("DOC_UPLOAD");
        assertThat(e.targetType()).isEqualTo("DOCUMENT");
        assertThat(e.targetId()).isEqualTo("7");
        assertThat(e.targetInfo()).isEqualTo("file.pdf");
    }

    @Test
    @DisplayName("documentDelete: signed → details")
    void documentDeleteSigned() {
        service.documentDelete("admin", 7L, "f.pdf", true);
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("DOC_DELETE");
        assertThat(e.details()).contains("Подписанный");
    }

    @Test
    @DisplayName("documentDelete: unsigned → details=null")
    void documentDeleteUnsigned() {
        service.documentDelete("admin", 7L, "f.pdf", false);
        assertThat(captureEntry().details()).isNull();
    }

    // ─── Sign ───────────────────────────────────────

    @Test
    @DisplayName("signSuccess: OK + subject")
    void signSuccess() {
        service.signSuccess("u@b.ru", 10L, "CN=Иванов");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("SIGN_SUCCESS");
        assertThat(e.targetInfo()).isEqualTo("CN=Иванов");
    }

    @Test
    @DisplayName("signFail: FAIL + reason")
    void signFail() {
        service.signFail("u@b.ru", 10L, "mismatch");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("SIGN_FAIL");
        assertThat(e.result()).isEqualTo("FAIL");
        assertThat(e.details()).isEqualTo("mismatch");
    }

    // ─── Backup / app ──────────────────────────────

    @Test
    @DisplayName("backupCreate: OK")
    void backupCreate() {
        service.backupCreate("admin", "afina_20240101.sql.gz.enc");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("BACKUP_CREATE");
        assertThat(e.targetType()).isEqualTo("BACKUP");
    }

    @Test
    @DisplayName("backupRestore: WARN")
    void backupRestore() {
        service.backupRestore("admin", "afina.sql.gz.enc");
        assertThat(captureEntry().result()).isEqualTo("WARN");
    }

    @Test
    @DisplayName("appRestart: WARN")
    void appRestart() {
        service.appRestart("admin");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("APP_RESTART");
        assertThat(e.result()).isEqualTo("WARN");
    }

    @Test
    @DisplayName("settingsUpdate: OK + keys")
    void settingsUpdate() {
        service.settingsUpdate("admin", "smtp.host,monitor.mail_to");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("SETTINGS_UPDATE");
        assertThat(e.targetInfo()).isEqualTo("smtp.host,monitor.mail_to");
    }

    // ─── Email ──────────────────────────────────────

    @Test
    @DisplayName("emailSent: 2-арг вариант → details=null")
    void emailSentShort() {
        service.emailSent("to@b.ru", "Subject");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("EMAIL_SENT");
        assertThat(e.actorEmail()).isEqualTo("to@b.ru");
        assertThat(e.targetInfo()).isEqualTo("Subject");
        assertThat(e.details()).isNull();
    }

    @Test
    @DisplayName("emailSent: 3-арг вариант → details")
    void emailSentLong() {
        service.emailSent("to@b.ru", "S", "extra");
        assertThat(captureEntry().details()).isEqualTo("extra");
    }

    @Test
    @DisplayName("emailFail: FAIL + reason")
    void emailFail() {
        service.emailFail("to@b.ru", "S", "smtp down");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("EMAIL_FAIL");
        assertThat(e.result()).isEqualTo("FAIL");
        assertThat(e.details()).isEqualTo("smtp down");
    }

    // ─── Folders ───────────────────────────────────

    @Test
    @DisplayName("folderCreate: OK + name")
    void folderCreate() {
        service.folderCreate("u@b.ru", 3L, "Раздел");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("FOLDER_CREATE");
        assertThat(e.targetType()).isEqualTo("FOLDER");
        assertThat(e.targetInfo()).isEqualTo("Раздел");
    }

    @Test
    @DisplayName("folderRename: OK")
    void folderRename() {
        service.folderRename("u@b.ru", 3L, "New");
        assertThat(captureEntry().type()).isEqualTo("FOLDER_RENAME");
    }

    @Test
    @DisplayName("folderDelete: signedCount>0 → WARN + details")
    void folderDeleteSigned() {
        service.folderDelete("u@b.ru", 3L, "Раздел", 5);
        AuditEntry e = captureEntry();
        assertThat(e.result()).isEqualTo("WARN");
        assertThat(e.details()).contains("5");
    }

    @Test
    @DisplayName("folderDelete: signedCount=0 → OK")
    void folderDeleteEmpty() {
        service.folderDelete("u@b.ru", 3L, "Раздел", 0);
        assertThat(captureEntry().result()).isEqualTo("SUCCESS");
    }

    // ─── POA ────────────────────────────────────────

    @Test
    @DisplayName("poaUpload / Toggle / Delete")
    void poaOperations() {
        service.poaUpload("admin", 1L, "Иванов", "2025-12-31");
        service.poaToggle("admin", 1L, true);
        service.poaDelete("admin", 1L, "41/26-319");

        ArgumentCaptor<AuditEntry> cap = ArgumentCaptor.forClass(AuditEntry.class);
        verify(writer, times(3)).write(cap.capture());
        assertThat(cap.getAllValues().get(0).type()).isEqualTo("POA_UPLOAD");
        assertThat(cap.getAllValues().get(0).details()).contains("2025-12-31");
        assertThat(cap.getAllValues().get(1).type()).isEqualTo("POA_TOGGLE");
        assertThat(cap.getAllValues().get(2).type()).isEqualTo("POA_DELETE");
        assertThat(cap.getAllValues().get(2).result()).isEqualTo("WARN");
    }

    // ─── Keys / generic ────────────────────────────

    @Test
    @DisplayName("keysDownload: WARN + ROLE_ADMIN")
    void keysDownload() {
        service.keysDownload("admin", "afina-keys.zip");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("KEYS_DOWNLOAD");
        assertThat(e.result()).isEqualTo("WARN");
        assertThat(e.actorRole()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    @DisplayName("event: произвольное событие")
    void genericEvent() {
        service.event("CUSTOM", "OK", "admin", "TARGET", "42", "info", "details");
        AuditEntry e = captureEntry();
        assertThat(e.type()).isEqualTo("CUSTOM");
        assertThat(e.targetId()).isEqualTo("42");
    }
}
