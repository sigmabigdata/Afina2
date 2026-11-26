package ru.afina.demo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DemoTenantContext: текущий тенант")
class DemoTenantContextTest {

    @AfterEach
    void clear() {
        DemoTenantContext.clear();
    }

    @Test
    @DisplayName("по умолчанию — шаблонный тенант")
    void defaultsToTemplate() {
        assertThat(DemoTenantContext.get()).isEqualTo(DemoTenantContext.TEMPLATE_TENANT);
    }

    @Test
    @DisplayName("set/get/clear")
    void setGetClear() {
        DemoTenantContext.set("s123");
        assertThat(DemoTenantContext.get()).isEqualTo("s123");
        DemoTenantContext.clear();
        assertThat(DemoTenantContext.get()).isEqualTo(DemoTenantContext.TEMPLATE_TENANT);
    }

    @Test
    @DisplayName("newTenantId: уникальные идентификаторы нужного формата")
    void newTenantIds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String id = DemoTenantContext.newTenantId();
            assertThat(id).startsWith("s").hasSize(13);
            ids.add(id);
        }
        assertThat(ids).hasSize(100);
    }
}
