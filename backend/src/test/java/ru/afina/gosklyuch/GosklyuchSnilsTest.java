package ru.afina.gosklyuch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Госключ: СНИЛС (нормализация, контрольное число, формат)")
class GosklyuchSnilsTest {

    @Test
    @DisplayName("Извлекаются только цифры")
    void digitsOnly() {
        assertThat(GosklyuchSnils.digits("112-233-445 95")).isEqualTo("11223344595");
        assertThat(GosklyuchSnils.digits("  ")).isNull();
        assertThat(GosklyuchSnils.digits(null)).isNull();
    }

    @Test
    @DisplayName("Контрольное число проверяется")
    void validatesChecksum() {
        assertThat(GosklyuchSnils.isValid("11223344595")).isTrue();
        assertThat(GosklyuchSnils.isValid("112-233-445 95")).isTrue();
        assertThat(GosklyuchSnils.isValid("11223344596")).isFalse();
        assertThat(GosklyuchSnils.isValid("1122334459")).isFalse();
        assertThat(GosklyuchSnils.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("Формат для заявки: XXX-XXX-XXX YY")
    void formatsForRequest() {
        assertThat(GosklyuchSnils.formatted("11223344595")).isEqualTo("112-233-445 95");
        assertThatThrownBy(() -> GosklyuchSnils.formatted("123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("11 цифр");
    }
}
