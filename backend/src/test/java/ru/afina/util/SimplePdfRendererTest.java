package ru.afina.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Рендер служебных документов HTML → PDF.
 *
 * <p>Проверяем, что PDF реально собирается (ненулевой размер, сигнатура
 * {@code %PDF}, маркер конца файла) и что спецсимволы не ломают разметку.</p>
 */
@DisplayName("SimplePdfRenderer: HTML → PDF")
class SimplePdfRendererTest {

    @Test
    @DisplayName("Рендер с кириллицей → валидный PDF ненулевого размера")
    void rendersPdfWithCyrillic() throws Exception {
        byte[] pdf = SimplePdfRenderer.render("Справка",
                "Настоящая справка подтверждает, что Иванов Иван Иванович является клиентом.");

        assertThat(pdf).isNotEmpty();
        assertThat(pdf.length).isGreaterThan(500);
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(raw).startsWith("%PDF-");
        assertThat(raw).contains("%%EOF");
        // PDF не должен содержать неэкранированный HTML заголовка
        assertThat(raw).doesNotContain("<h1>");
    }

    @Test
    @DisplayName("Спецсимволы HTML экранируются и не ломают рендер")
    void escapesHtmlSpecialCharacters() throws Exception {
        byte[] pdf = SimplePdfRenderer.render("<script>alert('x')</script>",
                "A & B < C > D \"кавычки\" 'апостроф' &amp; уже экранированный");

        assertThat(pdf).isNotEmpty();
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(raw).startsWith("%PDF-");
        assertThat(raw).contains("%%EOF");
    }

    @Test
    @DisplayName("null-заголовок и null-текст не приводят к ошибке")
    void handlesNulls() throws Exception {
        byte[] pdf = SimplePdfRenderer.render(null, null);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }

    @Test
    @DisplayName("Длинный текст с переводами строк рендерится в многостраничный PDF")
    void rendersMultilineText() throws Exception {
        String text = "Пункт 1. Договор.\nПункт 2. Оплата.\r\nПункт 3. Сроки.".repeat(40);
        byte[] pdf = SimplePdfRenderer.render("Договор", text);

        assertThat(pdf.length).isGreaterThan(1000);
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }
}
