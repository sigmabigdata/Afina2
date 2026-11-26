package ru.afina.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BackupService: разбор DB_URL")
class BackupServiceDbUrlTest {

    private static BackupService service(String dbUrl) {
        BackupService s = new BackupService();
        ReflectionTestUtils.setField(s, "dbUrl", dbUrl);
        return s;
    }

    @Test
    @DisplayName("стандартный URL разбирается")
    void parsesStandardUrl() {
        var p = service("jdbc:postgresql://postgres:5432/afina_db").parseDbParams();
        assertThat(p.host()).isEqualTo("postgres");
        assertThat(p.port()).isEqualTo(5432);
        assertThat(p.name()).isEqualTo("afina_db");
    }

    @Test
    @DisplayName("параметры (?sslmode=...) отбрасываются")
    void stripsQueryParams() {
        var p = service("jdbc:postgresql://pg:6000/db?sslmode=disable").parseDbParams();
        assertThat(p.host()).isEqualTo("pg");
        assertThat(p.port()).isEqualTo(6000);
        assertThat(p.name()).isEqualTo("db");
    }

    @Test
    @DisplayName("URL без имени БД → понятная ошибка, а не StringIndexOutOfBounds")
    void rejectsUrlWithoutDbName() {
        assertThatThrownBy(() -> service("jdbc:postgresql://localhost").parseDbParams())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("имя базы");
        assertThatThrownBy(() -> service("jdbc:postgresql://localhost:5432/").parseDbParams())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(null).parseDbParams())
                .isInstanceOf(IllegalStateException.class);
    }
}
