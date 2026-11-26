package ru.afina.util;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Простой рендер HTML → PDF.
 *
 * <p>Используется для служебных документов (демо-данные, тестовые PDF),
 * где не нужен шаблон из БД. Шрифт — встроенный DejaVuSans с кириллицей.</p>
 */
public final class SimplePdfRenderer {

    private static final String CSS = """
            @page { size: A4; margin: 20mm 18mm; }
            body { font-family: 'Afina Sans', 'Times New Roman', serif; font-size: 12pt;
                   line-height: 1.45; color: #111; }
            h1 { font-size: 17pt; text-align: center; margin: 0 0 18pt 0; }
            p { margin: 0 0 8pt 0; text-align: justify; }
            .muted { color: #666; font-size: 9pt; text-align: center; margin-top: 24pt; }
            """;

    private SimplePdfRenderer() {
    }

    /** Рендерит заголовок и текст в PDF (A4). */
    public static byte[] render(String title, String text) throws IOException {
        String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><title>"
                + escape(title) + "</title><style>" + CSS + "</style></head><body>"
                + "<h1>" + escape(title) + "</h1>"
                + "<p>" + escape(text) + "</p>"
                + "<div class=\"muted\">Сформировано в Афине</div>"
                + "</body></html>";

        Document document = Jsoup.parse(html);
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        document.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
        document.outputSettings().charset(StandardCharsets.UTF_8);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            if (PdfFonts.hasBundledFont()) {
                builder.useFont(PdfFonts::openRegular, "Afina Sans");
                builder.useFont(PdfFonts::openRegular, "Times New Roman");
                builder.useFont(PdfFonts::openRegular, "serif");
            }
            builder.withHtmlContent(document.html(), null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
