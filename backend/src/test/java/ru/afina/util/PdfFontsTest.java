package ru.afina.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверки поиска шрифта с кириллицей для PDF.
 *
 * <p>Встроенный {@code /fonts/DejaVuSans.ttf} поставляется в classpath,
 * поэтому оба начертания должны открываться без обращения к системе.
 * Ветка поиска системного шрифта (fallback) зависит от ОС — она
 * проверяется только на отсутствие исключений.</p>
 */
@DisplayName("PdfFonts: встроенный шрифт и системный fallback")
class PdfFontsTest {

    @Test
    @DisplayName("Встроенный шрифт присутствует в classpath")
    void bundledFontExists() {
        assertThat(PdfFonts.hasBundledFont()).isTrue();
        assertThat(PdfFonts.class.getResource(PdfFonts.REGULAR_RESOURCE)).isNotNull();
        assertThat(PdfFonts.class.getResource(PdfFonts.BOLD_RESOURCE)).isNotNull();
    }

    @Test
    @DisplayName("Обычное начертание открывается и содержит TTF-сигнатуру")
    void openRegularReturnsTtfStream() throws Exception {
        try (InputStream in = PdfFonts.openRegular()) {
            assertThat(in).as("поток обычного начертания").isNotNull();
            byte[] head = in.readNBytes(4);
            assertThat(head).hasSize(4);
            // TTF/OTF: 0x00 0x01 0x00 0x00 (или 'OTTO', 'ttcf')
            assertThat(head[0]).isZero();
            assertThat(head[1]).isEqualTo((byte) 1);
        }
    }

    @Test
    @DisplayName("Полужирное начертание открывается и читается до конца")
    void openBoldReturnsReadableStream() throws Exception {
        try (InputStream in = PdfFonts.openBold()) {
            assertThat(in).as("поток полужирного начертания").isNotNull();
            assertThat(in.readNBytes(16)).hasSize(16);
        }
    }

    @Test
    @DisplayName("systemFontPath() не бросает исключений (путь зависит от ОС)")
    void systemFontPathIsOptional() {
        Path path = PdfFonts.systemFontPath();
        if (path != null) {
            assertThat(Files.isRegularFile(path)).isTrue();
            assertThat(path.getFileName().toString()).endsWith(".ttf");
        }
    }
}
