package ru.afina.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PowerOfAttorneyXmlParser: парсинг МЧД (ЭМЧД ФНС)")
class PowerOfAttorneyXmlParserTest {

    /** Минимально-валидный XML: все обязательные поля присутствуют. */
    private static final String VALID_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Документ>
              <СвДов ВнНомДовер="41/26-319"
                     НомДовер="39725C00-69A5-4B1C-A2F4-1234567890AB"
                     ДатаВыдДовер="2024-03-15"
                     СрокДейст="2025-12-31"/>
              <СвУпПред>
                <ФИО Фамилия="Иванов" Имя="Иван" Отчество="Иванович"/>
                <СведФизЛ СНИЛС="123-456-789 01" ИННФЛ="770123456789"/>
              </СвУпПред>
              <СвРосОрг НаимОрг="ПАО НБД-Банк" ИННЮЛ="5260000001"/>
              <ФИОНотДейств Фамилия="Петров" Имя="Пётр" Отчество="Петрович"/>
            </Документ>
            """;

    @Test
    @DisplayName("валидный XML → все поля извлечены")
    void parsesAllFields() throws Exception {
        var p = PowerOfAttorneyXmlParser.parse(VALID_XML.getBytes(StandardCharsets.UTF_8));

        assertThat(p.fio()).isEqualTo("Иванов Иван Иванович");
        assertThat(p.snils()).isEqualTo("123-456-789 01");
        assertThat(p.inn()).isEqualTo("770123456789");
        assertThat(p.orgName()).isEqualTo("ПАО НБД-Банк");
        assertThat(p.orgInn()).isEqualTo("5260000001");
        assertThat(p.docNumber()).isEqualTo("41/26-319");
        assertThat(p.stampNumber()).isEqualTo("39725C00-69A5-4B1C-A2F4-1234567890AB");
        assertThat(p.docGuid()).isEqualTo(p.stampNumber());
        assertThat(p.docDate()).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(p.validFrom()).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(p.validUntil()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(p.notaryName()).isEqualTo("Петров Пётр Петрович");
    }

    @Test
    @DisplayName("без отчества ФИО → собирается из двух частей без двойных пробелов")
    void fioWithoutPatronymic() throws Exception {
        String xml = """
                <Документ>
                  <СвДов НомДовер="GUID-1" СрокДейст="2025-06-01"/>
                  <СвУпПред><ФИО Фамилия="Петров" Имя="Пётр"/></СвУпПред>
                </Документ>
                """;
        var p = PowerOfAttorneyXmlParser.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(p.fio()).isEqualTo("Петров Пётр");
    }

    @Test
    @DisplayName("нет СНИЛС/ИНН/нотариуса → не падает, поля null")
    void optionalFieldsAbsent() throws Exception {
        String xml = """
                <Документ>
                  <СвДов НомДовер="GUID-2" СрокДейст="2025-12-31"/>
                  <СвУпПред><ФИО Фамилия="Сидоров" Имя="Сидор" Отчество="Сидорович"/></СвУпПред>
                </Документ>
                """;
        var p = PowerOfAttorneyXmlParser.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(p.snils()).isNull();
        assertThat(p.inn()).isNull();
        assertThat(p.orgName()).isNull();
        assertThat(p.notaryName()).isNull();
        assertThat(p.fio()).isEqualTo("Сидоров Сидор Сидорович");
    }

    @Test
    @DisplayName("нет СвДов → ParseException")
    void missingSvdov() {
        String xml = """
                <Документ>
                  <СвУпПред><ФИО Фамилия="Иванов" Имя="Иван"/></СвУпПред>
                </Документ>
                """;
        assertThatThrownBy(() -> PowerOfAttorneyXmlParser.parse(
                xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PowerOfAttorneyXmlParser.ParseException.class)
                .hasMessageContaining("СвДов");
    }

    @Test
    @DisplayName("нет СрокДейст → ParseException")
    void missingValidUntil() {
        String xml = """
                <Документ>
                  <СвДов НомДовер="GUID-3"/>
                  <СвУпПред><ФИО Фамилия="Иванов" Имя="Иван"/></СвУпПред>
                </Документ>
                """;
        assertThatThrownBy(() -> PowerOfAttorneyXmlParser.parse(
                xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PowerOfAttorneyXmlParser.ParseException.class)
                .hasMessageContaining("СрокДейст");
    }

    @Test
    @DisplayName("нет СвУпПред → ParseException")
    void missingRepresentative() {
        String xml = """
                <Документ>
                  <СвДов НомДовер="GUID-4" СрокДейст="2025-12-31"/>
                </Документ>
                """;
        assertThatThrownBy(() -> PowerOfAttorneyXmlParser.parse(
                xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PowerOfAttorneyXmlParser.ParseException.class)
                .hasMessageContaining("СвУпПред");
    }

    @Test
    @DisplayName("нет ФИО в СвУпПред → ParseException")
    void missingFio() {
        String xml = """
                <Документ>
                  <СвДов НомДовер="GUID-5" СрокДейст="2025-12-31"/>
                  <СвУпПред><СведФизЛ СНИЛС="123-456-789 01"/></СвУпПред>
                </Документ>
                """;
        assertThatThrownBy(() -> PowerOfAttorneyXmlParser.parse(
                xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PowerOfAttorneyXmlParser.ParseException.class)
                .hasMessageContaining("ФИО");
    }

    @Test
    @DisplayName("XXE: внешняя сущность в DOCTYPE не раскрывается → ParseException")
    void xxeBlocked() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE Документ [
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <Документ>
                  <СвДов НомДовер="GUID-6" СрокДейст="2025-12-31"/>
                  <СвУпПред><ФИО Фамилия="&xxe;" Имя="Иван"/></СвУпПред>
                </Документ>
                """;
        assertThatThrownBy(() -> PowerOfAttorneyXmlParser.parse(
                xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PowerOfAttorneyXmlParser.ParseException.class)
                .hasMessageContaining("Не удалось разобрать XML");
    }

    @Test
    @DisplayName("DOCTYPE без внешних сущностей допустим (файлы МЧД из некоторых УЦ)")
    void doctypeWithoutEntitiesAllowed() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE Документ>
                <Документ>
                  <СвДов НомДовер="GUID-7" СрокДейст="2029-01-15"/>
                  <СвУпПред><ФИО Фамилия="Демо" Имя="Подписант"/></СвУпПред>
                </Документ>
                """;
        var parsed = PowerOfAttorneyXmlParser.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.stampNumber()).isEqualTo("GUID-7");
    }

    @Test
    @DisplayName("невалидный XML (мусор) → ParseException")
    void garbageInput() {
        assertThatThrownBy(() -> PowerOfAttorneyXmlParser.parse(
                "not xml at all".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PowerOfAttorneyXmlParser.ParseException.class);
    }

    @Test
    @DisplayName("дата в неверном формате → docDate/validUntil = null")
    void invalidDateFormat() throws Exception {
        String xml = """
                <Документ>
                  <СвДов НомДовер="GUID-7" ДатаВыдДовер="15.03.2024" СрокДейст="2025-12-31"/>
                  <СвУпПред><ФИО Фамилия="Иванов" Имя="Иван" Отчество="Иванович"/></СвУпПред>
                </Документ>
                """;
        var p = PowerOfAttorneyXmlParser.parse(xml.getBytes(StandardCharsets.UTF_8));
        // ДатаВыдДовер не распарсилась (не ISO) → null
        assertThat(p.docDate()).isNull();
        assertThat(p.validFrom()).isNull();
        assertThat(p.validUntil()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Иванов Иван Иванович",
            "Иванов   Иван  Иванович",
            " Иванов Иван Иванович "
    })
    @DisplayName("normalizeFio: схлопывает пробелы, uppercase")
    void normalizeFio(String input) {
        String n = PowerOfAttorneyXmlParser.normalizeFio(input);
        assertThat(n).isEqualTo("ИВАНОВ ИВАН ИВАНОВИЧ");
    }

    @Test
    @DisplayName("normalizeFio: null → пустая строка")
    void normalizeFioNull() {
        assertThat(PowerOfAttorneyXmlParser.normalizeFio(null)).isEmpty();
    }
}
