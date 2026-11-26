package ru.afina.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FileNamesTest {

    @Test
    @DisplayName("attachment() содержит filename*=UTF-8")
    void attachment_utf8() {
        String result = FileNames.attachment("Заявление.pdf");
        assertThat(result).startsWith("attachment;").contains("filename*=UTF-8''");
    }

    @Test
    @DisplayName("attachment() для ASCII")
    void attachment_ascii() {
        assertThat(FileNames.attachment("doc.pdf")).startsWith("attachment;").contains("doc.pdf");
    }

    @Test
    @DisplayName("inline() содержит inline;")
    void inline_utf8() {
        assertThat(FileNames.inline("Отчёт.docx")).startsWith("inline;").contains("filename*=UTF-8''");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("sanitizeZipEntry(null/blank) → 'file'")
    void sanitize_blank(String input) {
        assertThat(FileNames.sanitizeZipEntry(input)).isEqualTo("file");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Заявление.pdf|Заявление.pdf",
            "Отчёт (2026).pdf|Отчёт (2026).pdf",
            "file/with\\slashes.pdf|file_with_slashes.pdf",
            "file:with*chars?.pdf|file_with_chars_.pdf",
            "  .hidden  |hidden",
            "CON|_CON",
            "PRN|_PRN",
            "AUX.txt|_AUX.txt",
            "COM1|_COM1",
            "LPT9.log|_LPT9.log"
    })
    @DisplayName("sanitizeZipEntry: разные кейсы")
    void sanitize_cases(String input, String expected) {
        assertThat(FileNames.sanitizeZipEntry(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("sanitizeZipEntry: длинное имя обрезается")
    void sanitize_long_name() {
        String result = FileNames.sanitizeZipEntry("a".repeat(500) + ".pdf");
        assertThat(result.length()).isLessThanOrEqualTo(200);
        assertThat(result).endsWith(".pdf");
    }

    @Test
    @DisplayName("sanitizeZipEntry: control-символы → _")
    void sanitize_control_chars() {
        assertThat(FileNames.sanitizeZipEntry("file\u0001name.txt")).isEqualTo("file_name.txt");
    }

    @Test
    @DisplayName("sanitizeZipEntry: только точки → file")
    void sanitize_only_dots() {
        assertThat(FileNames.sanitizeZipEntry("...")).isEqualTo("file");
    }
}
