package ru.afina.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EntityTest {

    // ═══ User ═══
    @Test
    @DisplayName("User: getters/setters + defaults")
    void user() {
        User u = new User();
        assertThat(u.isEnabled()).isTrue();
        assertThat(u.getRole()).isEqualTo(Role.ROLE_USER);
        assertThat(u.getCreatedAt()).isNotNull();
        assertThat(u.getDocuments()).isEmpty();

        u.setId(1L);
        u.setEmail("a@b.c");
        u.setEmailHash("hash");
        u.setFullName("Иванов И.И.");
        u.setPhone("+79991234567");
        u.setPhoneHash("ph");
        u.setSnils("12345678901");
        u.setSnilsHash("sh");
        u.setRole(Role.ROLE_ADMIN);
        u.setEnabled(false);
        u.setLoginToken("token");
        u.setLoginTokenExpires(Instant.now().plusSeconds(3600));
        u.setLoginTokenUsedAt(Instant.now());
        u.setLastLoginAt(Instant.now());
        u.setCreatedAt(Instant.now());
        u.setDocuments(List.of());

        assertThat(u.getId()).isEqualTo(1L);
        assertThat(u.getEmail()).isEqualTo("a@b.c");
        assertThat(u.getEmailHash()).isEqualTo("hash");
        assertThat(u.getFullName()).isEqualTo("Иванов И.И.");
        assertThat(u.getPhone()).isEqualTo("+79991234567");
        assertThat(u.getPhoneHash()).isEqualTo("ph");
        assertThat(u.getSnils()).isEqualTo("12345678901");
        assertThat(u.getSnilsHash()).isEqualTo("sh");
        assertThat(u.getRole()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(u.isEnabled()).isFalse();
        assertThat(u.getLoginToken()).isEqualTo("token");
        assertThat(u.getLoginTokenExpires()).isNotNull();
        assertThat(u.getLoginTokenUsedAt()).isNotNull();
        assertThat(u.getLastLoginAt()).isNotNull();
    }

    // ═══ Document ═══
    @Test
    @DisplayName("Document: getters/setters + publicUuid default")
    void document() {
        Document d = new Document();
        assertThat(d.getPublicUuid()).isNotNull();
        assertThat(d.isSigned()).isFalse();
        assertThat(d.isEncrypted()).isFalse();
        assertThat(d.isApplyPoa()).isFalse();
        assertThat(d.getUploadedAt()).isNotNull();
        assertThat(d.getSignatures()).isEmpty();

        UUID uuid = UUID.randomUUID();
        d.setId(1L);
        d.setOriginalName("f.pdf");
        d.setStoredName("uuid_f.pdf.enc");
        d.setContentType("application/pdf");
        d.setSize(100);
        d.setFileSha256("sha");
        d.setSignatureBase64("sig");
        d.setSigned(true);
        d.setSignedAt(Instant.now());
        d.setSignerSubject("CN=test");
        d.setSignerSerial("abc");
        d.setEncryptionIv("iv");
        d.setKeyVersion("v1");
        d.setEncrypted(true);
        d.setApplyPoa(true);
        d.setUploadedAt(Instant.now());
        d.setPublicUuid(uuid);

        assertThat(d.getOriginalName()).isEqualTo("f.pdf");
        assertThat(d.getStoredName()).isEqualTo("uuid_f.pdf.enc");
        assertThat(d.getContentType()).isEqualTo("application/pdf");
        assertThat(d.getSize()).isEqualTo(100);
        assertThat(d.getFileSha256()).isEqualTo("sha");
        assertThat(d.isSigned()).isTrue();
        assertThat(d.isEncrypted()).isTrue();
        assertThat(d.isApplyPoa()).isTrue();
        assertThat(d.getPublicUuid()).isEqualTo(uuid);
    }

    @Test
    @DisplayName("Document.hasSignatureByUser: null, пусто, найдено")
    void hasSignatureByUser() {
        Document d = new Document();
        assertThat(d.hasSignatureByUser(null)).isFalse();
        assertThat(d.hasSignatureByUser(1L)).isFalse();

        User signer = new User();
        signer.setId(1L);
        DocumentSignature sig = new DocumentSignature();
        sig.setSignerUser(signer);
        d.setSignatures(List.of(sig));

        assertThat(d.hasSignatureByUser(1L)).isTrue();
        assertThat(d.hasSignatureByUser(2L)).isFalse();
    }

    @Test
    @DisplayName("Document.hasSignatureByUser: null signer в подписи")
    void hasSignatureByUser_nullSigner() {
        Document d = new Document();
        DocumentSignature sig = new DocumentSignature();
        sig.setSignerUser(null);
        d.setSignatures(List.of(sig));
        assertThat(d.hasSignatureByUser(1L)).isFalse();
    }

    // ═══ DocumentSignature ═══
    @Test
    @DisplayName("DocumentSignature: getters/setters + defaults")
    void signature() {
        DocumentSignature s = new DocumentSignature();
        assertThat(s.isApplyPoa()).isFalse();
        assertThat(s.getSignedAt()).isNotNull();

        Document doc = new Document();
        User user = new User();
        Instant now = Instant.now();
        s.setId(1L);
        s.setDocument(doc);
        s.setSignatureBase64("sig");
        s.setSignedAt(now);
        s.setSignerSubject("CN");
        s.setSignerSerial("abc");
        s.setSignerUser(user);
        s.setApplyPoa(true);

        assertThat(s.getId()).isEqualTo(1L);
        assertThat(s.getDocument()).isSameAs(doc);
        assertThat(s.getSignatureBase64()).isEqualTo("sig");
        assertThat(s.getSignedAt()).isEqualTo(now);
        assertThat(s.getSignerSubject()).isEqualTo("CN");
        assertThat(s.getSignerSerial()).isEqualTo("abc");
        assertThat(s.getSignerUser()).isSameAs(user);
        assertThat(s.isApplyPoa()).isTrue();
    }

    // ═══ DocumentFolder ═══
    @Test
    @DisplayName("DocumentFolder: getters/setters")
    void folder() {
        DocumentFolder f = new DocumentFolder();
        assertThat(f.getCreatedAt()).isNotNull();
        User owner = new User();
        f.setId(1L);
        f.setName("Учредительные");
        f.setNameHash("hash");
        f.setOwner(owner);
        f.setCreatedAt(Instant.now());

        assertThat(f.getId()).isEqualTo(1L);
        assertThat(f.getName()).isEqualTo("Учредительные");
        assertThat(f.getNameHash()).isEqualTo("hash");
        assertThat(f.getOwner()).isSameAs(owner);
    }

    // ═══ DocumentTemplate ═══
    @Test
    @DisplayName("DocumentTemplate: getters/setters + defaults")
    void template() {
        DocumentTemplate t = new DocumentTemplate();
        assertThat(t.isActive()).isTrue();
        assertThat(t.getSortOrder()).isZero();
        assertThat(t.getCreatedAt()).isNotNull();
        assertThat(t.getUpdatedAt()).isNotNull();

        t.setId(1L);
        t.setName("Заявление");
        t.setDescription("desc");
        t.setHtmlContent("<p>test</p>");
        t.setFieldsJson("[]");
        t.setCreatedBy("admin");
        t.setActive(false);
        t.setSortOrder(10);

        assertThat(t.getName()).isEqualTo("Заявление");
        assertThat(t.getHtmlContent()).isEqualTo("<p>test</p>");
        assertThat(t.isActive()).isFalse();
        assertThat(t.getSortOrder()).isEqualTo(10);
    }

    // ═══ PowerOfAttorney ═══
    @Test
    @DisplayName("PowerOfAttorney: isExpired / isEffective / isExpiringWithin")
    void poa() {
        PowerOfAttorney p = new PowerOfAttorney();
        p.setActive(true);
        assertThat(p.isExpired()).isFalse();
        assertThat(p.isEffective()).isTrue();

        p.setValidUntil(LocalDate.now().minusDays(1));
        assertThat(p.isExpired()).isTrue();
        assertThat(p.isEffective()).isFalse();

        p.setValidUntil(LocalDate.now().plusDays(20));
        assertThat(p.isExpiringWithin(30)).isTrue();
        assertThat(p.isExpiringWithin(7)).isFalse();
        assertThat(p.isExpiringSoon()).isTrue();      // ≤30
        assertThat(p.isExpiringCritical()).isFalse(); // ≤7

        p.setValidUntil(LocalDate.now().plusDays(3));
        assertThat(p.isExpiringCritical()).isTrue();
    }

    @Test
    @DisplayName("PowerOfAttorney: isExpired null → false (без NPE)")
    void poa_nullValidUntil() {
        PowerOfAttorney p = new PowerOfAttorney();
        p.setValidUntil(null);
        assertThat(p.isExpired()).isFalse();
        assertThat(p.isExpiringWithin(30)).isFalse();
        assertThat(p.isExpiringSoon()).isFalse();
        assertThat(p.isExpiringCritical()).isFalse();
    }

    @Test
    @DisplayName("PowerOfAttorney: геттеры/сеттеры")
    void poa_getters() {
        PowerOfAttorney p = new PowerOfAttorney();
        p.setFio("Иванов И.И.");
        p.setFioHash("hash");
        p.setSnils("12345678901");
        p.setInn("inn");
        p.setOrgName("ООО Тест");
        p.setOrgInn("1234567890");
        p.setDocNumber("41/26");
        p.setDocGuid("guid");
        p.setStampNumber("stamp");
        p.setDocDate(LocalDate.now());
        p.setValidFrom(LocalDate.now());
        p.setValidUntil(LocalDate.now().plusYears(1));
        p.setNotaryName("Нотариус");
        p.setXmlContent("<xml/>");
        p.setFileSha256("sha");
        p.setCreatedBy("admin");
        p.setActive(true);
        p.setNotified30d(true);
        p.setNotified7d(true);

        assertThat(p.getFio()).isEqualTo("Иванов И.И.");
        assertThat(p.getOrgName()).isEqualTo("ООО Тест");
        assertThat(p.getStampNumber()).isEqualTo("stamp");
        assertThat(p.isNotified30d()).isTrue();
        assertThat(p.isNotified7d()).isTrue();
    }

    // ═══ MonitorEvent ═══
    @Test
    @DisplayName("MonitorEvent: getters/setters")
    void monitorEvent() {
        MonitorEvent e = new MonitorEvent();
        assertThat(e.isAlertSent()).isFalse();
        assertThat(e.getCheckedAt()).isNotNull();

        Instant now = Instant.now();
        e.setId(1L);
        e.setCheckedAt(now);
        e.setStatus("FAIL");
        e.setComponent("smtp");
        e.setReason("timeout");
        e.setAlertSent(true);

        assertThat(e.getStatus()).isEqualTo("FAIL");
        assertThat(e.getComponent()).isEqualTo("smtp");
        assertThat(e.getReason()).isEqualTo("timeout");
        assertThat(e.isAlertSent()).isTrue();
        assertThat(e.getCheckedAt()).isEqualTo(now);
    }

    // ═══ AuditEvent ═══
    @Test
    @DisplayName("AuditEvent: getters/setters")
    void auditEvent() {
        AuditEvent e = new AuditEvent();
        assertThat(e.getEventTime()).isNotNull();

        Instant now = Instant.now();
        e.setId(1L);
        e.setEventTime(now);
        e.setEventType("LOGIN_SUCCESS");
        e.setResult("SUCCESS");
        e.setActorEmail("a@b.c");
        e.setActorRole("ROLE_USER");
        e.setActorIp("1.2.3.4");
        e.setTargetType("USER");
        e.setTargetId("1");
        e.setTargetInfo("info");
        e.setDetails("details");
        e.setUserAgent("UA");

        assertThat(e.getEventType()).isEqualTo("LOGIN_SUCCESS");
        assertThat(e.getResult()).isEqualTo("SUCCESS");
        assertThat(e.getActorEmail()).isEqualTo("a@b.c");
        assertThat(e.getUserAgent()).isEqualTo("UA");
    }

    // ═══ DocumentVisualization ═══
    @Test
    @DisplayName("DocumentVisualization: getters/setters")
    void visualization() {
        DocumentVisualization v = new DocumentVisualization();
        assertThat(v.getVersion()).isEqualTo(1);
        assertThat(v.getCreatedAt()).isNotNull();
        assertThat(v.getUpdatedAt()).isNotNull();

        Document doc = new Document();
        v.setId(1L);
        v.setDocument(doc);
        v.setStoredName("vis_1_ts.pdf");
        v.setSize(1000);
        v.setVersion(3);

        assertThat(v.getDocument()).isSameAs(doc);
        assertThat(v.getStoredName()).isEqualTo("vis_1_ts.pdf");
        assertThat(v.getSize()).isEqualTo(1000);
        assertThat(v.getVersion()).isEqualTo(3);
    }

    // ═══ AppSetting ═══
    @Test
    @DisplayName("AppSetting: getters/setters")
    void appSetting() {
        AppSetting s = new AppSetting();
        assertThat(s.getUpdatedAt()).isNotNull();

        Instant now = Instant.now();
        s.setKey("k");
        s.setValue("v");
        s.setDescription("d");
        s.setUpdatedAt(now);
        s.setUpdatedBy("admin");

        assertThat(s.getKey()).isEqualTo("k");
        assertThat(s.getValue()).isEqualTo("v");
        assertThat(s.getDescription()).isEqualTo("d");
        assertThat(s.getUpdatedAt()).isEqualTo(now);
        assertThat(s.getUpdatedBy()).isEqualTo("admin");
    }

    // ═══ Role ═══
    @Test
    @DisplayName("Role: значения enum")
    void role() {
        assertThat(Role.values()).containsExactly(
                Role.ROLE_USER, Role.ROLE_ADMIN, Role.ROLE_EMPLOYEE);
    }
}
