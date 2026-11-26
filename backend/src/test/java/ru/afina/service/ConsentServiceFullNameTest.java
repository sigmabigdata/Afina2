package ru.afina.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConsentService: нормализация ФИО")
class ConsentServiceFullNameTest {

    @Test
    @DisplayName("ФИО приводится к «Фамилия Имя Отчество»")
    void normalizes() {
        assertThat(ConsentService.normalizeFullName("  иванов   иван  иванович "))
                .isEqualTo("Иванов Иван Иванович");
        assertThat(ConsentService.normalizeFullName("ПЕТРОВ ПЁТР"))
                .isEqualTo("Петров Пётр");
    }

    @Test
    @DisplayName("двойная фамилия с дефисом сохраняется")
    void hyphenated() {
        assertThat(ConsentService.normalizeFullName("иванова-петрова анна сергеевна"))
                .isEqualTo("Иванова-петрова Анна Сергеевна");
    }

    @Test
    @DisplayName("одно слово, цифры и мусор отклоняются")
    void rejectsInvalid() {
        assertThat(ConsentService.normalizeFullName("Иванов")).isNull();
        assertThat(ConsentService.normalizeFullName("Иванов Иван 123")).isNull();
        assertThat(ConsentService.normalizeFullName("   ")).isNull();
        assertThat(ConsentService.normalizeFullName(null)).isNull();
    }
}
