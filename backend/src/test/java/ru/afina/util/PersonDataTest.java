package ru.afina.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PersonData: ФИО и телефон")
class PersonDataTest {

    @Test
    @DisplayName("ФИО приводится к «Фамилия Имя Отчество»")
    void fullName() {
        assertThat(PersonData.normalizeFullName("  иванов   иван  иванович "))
                .isEqualTo("Иванов Иван Иванович");
        assertThat(PersonData.normalizeFullName("ПЕТРОВ ПЁТР"))
                .isEqualTo("Петров Пётр");
        assertThat(PersonData.normalizeFullName("иванова-петрова анна сергеевна"))
                .isEqualTo("Иванова-петрова Анна Сергеевна");
    }

    @Test
    @DisplayName("некорректное ФИО отклоняется")
    void badFullName() {
        assertThat(PersonData.normalizeFullName("Иванов")).isNull();
        assertThat(PersonData.normalizeFullName("Иванов Иван 123")).isNull();
        assertThat(PersonData.normalizeFullName("   ")).isNull();
        assertThat(PersonData.normalizeFullName(null)).isNull();
    }

    @Test
    @DisplayName("телефон приводится к +7XXXXXXXXXX")
    void phone() {
        assertThat(PersonData.normalizePhone("8 900 111-22-33")).isEqualTo("+79001112233");
        assertThat(PersonData.normalizePhone("9001112233")).isEqualTo("+79001112233");
        assertThat(PersonData.normalizePhone("+7 (900) 111-22-33")).isEqualTo("+79001112233");
        assertThat(PersonData.normalizePhone("7-900-111-22-33")).isEqualTo("+79001112233");
    }

    @Test
    @DisplayName("CN из субъекта сертификата")
    void cn() {
        assertThat(PersonData.extractCn("CN=Иванов Иван Иванович, O=Банк, C=RU"))
                .isEqualTo("Иванов Иван Иванович");
        assertThat(PersonData.extractCn("1.2.840.113549.1.9.1=#160e...,CN=\"Иванов Иван\",O=Банк"))
                .isEqualTo("Иванов Иван");
        assertThat(PersonData.extractCn("O=Банк")).isNull();
        assertThat(PersonData.extractCn(null)).isNull();
    }

    @Test
    @DisplayName("фамилия и имя в сертификате и карточке совпадают")
    void namesMatch() {
        assertThat(PersonData.namesMatch("Дырул Анатолий Юрьевич", "Дырул Анатолий Юрьевич")).isTrue();
        assertThat(PersonData.namesMatch("Дырул Анатолий Юрьевич", "Дырул Анатолий")).isTrue();
        assertThat(PersonData.namesMatch("Петров Пётр", "ПЕТРОВ ПЕТР ИВАНОВИЧ")).isTrue();
        assertThat(PersonData.namesMatch("Петров Пётр", "Петров П.И.")).isTrue();
        assertThat(PersonData.namesMatch("Петров Пётр", "Петров Иван")).isFalse();
        assertThat(PersonData.namesMatch("Петров Пётр", "Иванов Пётр")).isFalse();
        assertThat(PersonData.namesMatch(null, "Иванов Иван")).isTrue();
        assertThat(PersonData.namesMatch("Петров Пётр", null)).isTrue();
    }

    @Test
    @DisplayName("текст предупреждения о несовпадении ФИО")
    void mismatchWarning() {
        assertThat(PersonData.nameMismatch("Петров Пётр Петрович",
                "CN=Иванов Иван Иванович, O=Банк"))
                .contains("не совпадают")
                .contains("Иванов Иван Иванович");
        assertThat(PersonData.nameMismatch("Петров Пётр Петрович",
                "CN=Петров Пётр Петрович, O=Банк")).isNull();
    }

    @Test
    @DisplayName("некорректный телефон отклоняется")
    void badPhone() {
        assertThat(PersonData.normalizePhone("123")).isNull();
        assertThat(PersonData.normalizePhone("59001112233")).isNull();
        assertThat(PersonData.normalizePhone("")).isNull();
        assertThat(PersonData.normalizePhone(null)).isNull();
    }
}
