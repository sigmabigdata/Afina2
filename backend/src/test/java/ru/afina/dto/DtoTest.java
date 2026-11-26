package ru.afina.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DtoTest {

    @Test
    @DisplayName("CertLoginRequest: геттеры/сеттеры")
    void certLoginRequest() {
        CertLoginRequest r = new CertLoginRequest();
        r.setCn("Иванов И.И.");
        r.setSnils("12345678901");
        r.setChallenge("abc");
        r.setSignatureBase64("base64data");
        assertThat(r.getCn()).isEqualTo("Иванов И.И.");
        assertThat(r.getSnils()).isEqualTo("12345678901");
        assertThat(r.getChallenge()).isEqualTo("abc");
        assertThat(r.getSignatureBase64()).isEqualTo("base64data");
    }

    @Test
    @DisplayName("SignRequest: геттеры/сеттеры + batch")
    void signRequest() {
        SignRequest r = new SignRequest();
        r.setDocumentId(42L);
        r.setDocumentIds(List.of(1L, 2L, 3L));
        r.setSignatureBase64("sig");
        assertThat(r.getDocumentId()).isEqualTo(42L);
        assertThat(r.getDocumentIds()).containsExactly(1L, 2L, 3L);
        assertThat(r.getSignatureBase64()).isEqualTo("sig");
    }
}
