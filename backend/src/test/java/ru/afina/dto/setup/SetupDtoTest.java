package ru.afina.dto.setup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SetupDtoTest {

    @Test
    @DisplayName("SetupStateDto: геттеры/сеттеры + дефолты")
    void stateDto() {
        SetupStateDto s = new SetupStateDto();
        assertThat(s.getDraft()).isEmpty();
        assertThat(s.getTotalSteps()).isEqualTo(6);

        s.setCurrentStep(3);
        s.setComplete(true);
        s.setDomain("afina.example.ru");
        s.setTokenConfigured(true);
        s.setDraft(Map.of("domain", "afina.example.ru"));

        assertThat(s.getCurrentStep()).isEqualTo(3);
        assertThat(s.isComplete()).isTrue();
        assertThat(s.getDomain()).isEqualTo("afina.example.ru");
        assertThat(s.isTokenConfigured()).isTrue();
        assertThat(s.getDraft()).containsEntry("domain", "afina.example.ru");
    }

    @Test
    @DisplayName("SetupPreflightDto: addCheck + allOk")
    void preflightDto() {
        SetupPreflightDto p = new SetupPreflightDto();
        p.addCheck("Домен", true, "afina.example.ru");
        p.addCheck("DNS", true, "1.2.3.4");

        assertThat(p.isAllOk()).isTrue();
        assertThat(p.getChecks()).hasSize(2);
        assertThat(p.getChecks().get(0).name()).isEqualTo("Домен");
        assertThat(p.getChecks().get(0).ok()).isTrue();
    }

    @Test
    @DisplayName("SetupPreflightDto: allOk = false когда есть fail")
    void preflightDto_fail() {
        SetupPreflightDto p = new SetupPreflightDto();
        p.addCheck("Домен", true, "ok");
        p.addCheck("DNS", false, "timeout");
        assertThat(p.isAllOk()).isFalse();
    }

    @Test
    @DisplayName("SetupStepRequest: data геттер/сеттер + null → пустой map")
    void stepRequest() {
        SetupStepRequest r = new SetupStepRequest();
        assertThat(r.getData()).isEmpty();

        r.setData(Map.of("domain", "test.ru"));
        assertThat(r.getData()).containsEntry("domain", "test.ru");

        r.setData(null);
        assertThat(r.getData()).isEmpty();
    }

    @Test
    @DisplayName("SetupFinalizeResponse: конструктор + геттеры")
    void finalizeResponse() {
        SetupFinalizeResponse r = new SetupFinalizeResponse(
                true, "ok", "https://afina/admin");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getMessage()).isEqualTo("ok");
        assertThat(r.getRedirectUrl()).isEqualTo("https://afina/admin");
    }
}
