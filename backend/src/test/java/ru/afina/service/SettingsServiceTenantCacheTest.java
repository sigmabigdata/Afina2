package ru.afina.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.afina.demo.DemoTenantContext;
import ru.afina.entity.AppSetting;
import ru.afina.repository.AppSettingRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * В демо у каждой сессии своя БД — кэш настроек обязан быть разделён по тенанту,
 * иначе настройки «протекают» между посетителями демо.
 */
@DisplayName("SettingsService: tenant-scoped кэш в демо")
class SettingsServiceTenantCacheTest {

    private static AppSetting setting(String key, String value) {
        AppSetting s = new AppSetting();
        s.setKey(key);
        s.setValue(value);
        return s;
    }

    @AfterEach
    void clear() {
        DemoTenantContext.clear();
    }

    @Test
    @DisplayName("два тенанта не видят настройки друг друга")
    void tenantCacheIsolated() {
        AppSettingRepository repo = mock(AppSettingRepository.class);
        SettingsService service = new SettingsService(repo);
        ReflectionTestUtils.setField(service, "demoTenantScoped", true);

        DemoTenantContext.set("sA");
        when(repo.findById("k")).thenReturn(Optional.of(setting("k", "value-A")));
        assertThat(service.get("k")).contains("value-A");

        DemoTenantContext.set("sB");
        when(repo.findById("k")).thenReturn(Optional.of(setting("k", "value-B")));
        assertThat(service.get("k")).contains("value-B");

        // оба значения закэшированы раздельно
        verify(repo, times(2)).findById("k");
        DemoTenantContext.set("sA");
        assertThat(service.get("k")).contains("value-A");
        verify(repo, times(2)).findById("k");
    }

    @Test
    @DisplayName("в проде (флаг выключен) кэш общий")
    void cacheSharedInProd() {
        AppSettingRepository repo = mock(AppSettingRepository.class);
        SettingsService service = new SettingsService(repo);
        ReflectionTestUtils.setField(service, "demoTenantScoped", false);
        when(repo.findById("k")).thenReturn(Optional.of(setting("k", "v")));

        assertThat(service.get("k")).contains("v");
        DemoTenantContext.set("sX");
        assertThat(service.get("k")).contains("v");
        verify(repo, times(1)).findById("k");
    }
}
