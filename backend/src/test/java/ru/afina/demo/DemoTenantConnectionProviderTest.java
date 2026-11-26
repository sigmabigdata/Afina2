package ru.afina.demo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("DemoTenantConnectionProvider: жизненный цикл персональной БД")
class DemoTenantConnectionProviderTest {

    private final DemoTenantConnectionProvider provider = new DemoTenantConnectionProvider();

    @AfterEach
    void cleanup() {
        for (String t : provider.activeTenants()) {
            provider.dropTenant(t);
        }
        DemoTenantContext.clear();
    }

    @Test
    @DisplayName("новая сессия: БД создаётся и требует наполнения данными")
    void newTenantIsMarked() {
        var ds = provider.poolOf("s-test-1");

        assertThat(ds).isNotNull();
        assertThat(provider.isNewTenant("s-test-1")).isTrue();
        assertThat(provider.activeTenants()).contains("s-test-1");

        provider.markSeeded("s-test-1");
        assertThat(provider.isNewTenant("s-test-1")).isFalse();
    }

    @Test
    @DisplayName("dropTenant удаляет БД сессии, шаблон и null не трогает")
    void dropTenant() {
        provider.poolOf("s-test-2");
        provider.poolOf(DemoTenantContext.TEMPLATE_TENANT);

        provider.dropTenant("s-test-2");
        assertThat(provider.activeTenants()).doesNotContain("s-test-2");

        assertThatCode(() -> provider.dropTenant(null)).doesNotThrowAnyException();
        assertThatCode(() -> provider.dropTenant(DemoTenantContext.TEMPLATE_TENANT))
                .doesNotThrowAnyException();
        assertThat(provider.activeTenants()).contains(DemoTenantContext.TEMPLATE_TENANT);
    }

    @Test
    @DisplayName("при переполнении вытесняется самая старая демо-БД")
    void evictsOldestWhenFull() {
        ReflectionTestUtils.setField(provider, "maxTenants", 2);

        provider.poolOf("s-old");
        provider.poolOf("s-new-1");
        provider.poolOf("s-new-2");

        assertThat(provider.activeTenants()).doesNotContain("s-old");
        assertThat(provider.activeTenants()).contains("s-new-1", "s-new-2");
    }
}
