package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.entity.AppSetting;
import ru.afina.repository.AppSettingRepository;
import ru.afina.security.PiiEncryptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock AppSettingRepository repo;
    @Mock PiiEncryptor pii;

    SettingsService service;

    @BeforeEach
    void setUp() {
        service = new SettingsService(repo);
    }

    @Test
    @DisplayName("get: возвращает Optional.empty для отсутствующего ключа")
    void getMissing() {
        when(repo.findById("k")).thenReturn(Optional.empty());
        assertThat(service.get("k")).isEmpty();
    }

    @Test
    @DisplayName("get: кэширует значение (второй вызов не идёт в repo)")
    void getCaches() {
        AppSetting s = new AppSetting();
        s.setKey("k");
        s.setValue("v");
        when(repo.findById("k")).thenReturn(Optional.of(s));

        assertThat(service.get("k")).contains("v");
        assertThat(service.get("k")).contains("v");
        verify(repo, times(1)).findById("k");
    }

    @Test
    @DisplayName("get: null value возвращает пустую строку")
    void getNullValue() {
        AppSetting s = new AppSetting();
        s.setKey("k");
        s.setValue(null);
        when(repo.findById("k")).thenReturn(Optional.of(s));
        assertThat(service.get("k")).contains("");
    }

    @Test
    @DisplayName("getOrDefault: пустое значение → default")
    void getOrDefault() {
        AppSetting s = new AppSetting();
        s.setKey("k");
        s.setValue("");
        when(repo.findById("k")).thenReturn(Optional.of(s));
        assertThat(service.getOrDefault("k", "def")).isEqualTo("def");
    }

    @Test
    @DisplayName("getIntOrDefault: валидное число")
    void getInt() {
        AppSetting s = new AppSetting();
        s.setKey("n");
        s.setValue("42");
        when(repo.findById("n")).thenReturn(Optional.of(s));
        assertThat(service.getIntOrDefault("n", 10)).isEqualTo(42);
    }

    @Test
    @DisplayName("getIntOrDefault: невалидное → default")
    void getIntInvalid() {
        AppSetting s = new AppSetting();
        s.setKey("n");
        s.setValue("abc");
        when(repo.findById("n")).thenReturn(Optional.of(s));
        assertThat(service.getIntOrDefault("n", 10)).isEqualTo(10);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"true", "1", "yes", "TRUE", "Yes", "TRUE"})
    @DisplayName("getBoolOrDefault: true/1/yes → true")
    void getBoolTrue(String value) {
        AppSetting s = new AppSetting();
        s.setKey("b");
        s.setValue(value);
        when(repo.findById("b")).thenReturn(Optional.of(s));
        assertThat(service.getBoolOrDefault("b", false)).isTrue();
    }

    @Test
    @DisplayName("getBoolOrDefault: false/0/пусто → default")
    void getBoolFalse() {
        AppSetting s = new AppSetting();
        s.setKey("b");
        s.setValue("false");
        when(repo.findById("b")).thenReturn(Optional.of(s));
        assertThat(service.getBoolOrDefault("b", true)).isFalse();
    }

    @Test
    @DisplayName("set: сохраняет значение и кэширует")
    void set() {
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        service.set("k", "v", "admin");
        verify(repo).save(any(AppSetting.class));
        assertThat(service.get("k")).contains("v");
    }

    @Test
    @DisplayName("getAll: возвращает всё из repo")
    void getAll() {
        AppSetting s = new AppSetting();
        s.setKey("k");
        s.setValue("v");
        when(repo.findAll()).thenReturn(List.of(s));
        assertThat(service.getAll()).hasSize(1);
    }

    @Test
    @DisplayName("isSmtpConfigured: true если smtp.host задан")
    void isSmtpConfigured() {
        AppSetting s = new AppSetting();
        s.setKey("smtp.host");
        s.setValue("smtp.example.com");
        when(repo.findById("smtp.host")).thenReturn(Optional.of(s));
        assertThat(service.isSmtpConfigured()).isTrue();
    }

    @Test
    @DisplayName("isSmtpConfigured: false если smtp.host пуст")
    void isSmtpNotConfigured() {
        AppSetting s = new AppSetting();
        s.setKey("smtp.host");
        s.setValue("");
        when(repo.findById("smtp.host")).thenReturn(Optional.of(s));
        assertThat(service.isSmtpConfigured()).isFalse();
    }

    @Test
    @DisplayName("getSmtpPasswordDecrypted: пусто → пустая строка")
    void getSmtpPasswordEmpty() {
        AppSetting s = new AppSetting();
        s.setKey("smtp.password");
        s.setValue("");
        when(repo.findById("smtp.password")).thenReturn(Optional.of(s));
        assertThat(service.getSmtpPasswordDecrypted(pii)).isEmpty();
    }

    @Test
    @DisplayName("getSmtpPasswordDecrypted: вызывает pii.decrypt")
    void getSmtpPasswordDecrypt() {
        AppSetting s = new AppSetting();
        s.setKey("smtp.password");
        s.setValue("encrypted");
        when(repo.findById("smtp.password")).thenReturn(Optional.of(s));
        when(pii.decrypt("encrypted")).thenReturn("plain");
        assertThat(service.getSmtpPasswordDecrypted(pii)).isEqualTo("plain");
    }

    @Test
    @DisplayName("setSmtpPasswordEncrypted: пустой → пустое значение")
    void setSmtpPasswordEmpty() {
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        service.setSmtpPasswordEncrypted("", pii, "admin");
        verify(repo).save(any(AppSetting.class));
    }

    @Test
    @DisplayName("setSmtpPasswordEncrypted: null → no-op")
    void setSmtpPasswordNull() {
        service.setSmtpPasswordEncrypted(null, pii, "admin");
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("setSmtpPasswordEncrypted: шифрует через pii")
    void setSmtpPasswordEncrypt() {
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(pii.encrypt("plain")).thenReturn("enc");
        service.setSmtpPasswordEncrypted("plain", pii, "admin");
        verify(pii).encrypt("plain");
    }
}
