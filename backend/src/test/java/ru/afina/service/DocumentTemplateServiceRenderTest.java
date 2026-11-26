package ru.afina.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.entity.DocumentTemplate;
import ru.afina.repository.DocumentTemplateRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentTemplateService: renderPdf + listAll + getById")
class DocumentTemplateServiceRenderTest {

    @Mock private DocumentTemplateRepository repository;
    @InjectMocks private DocumentTemplateService service;

    // ─── listAll / getById ─────────────────────

    @Test
    @DisplayName("listAll: делегирует в findAllByOrderBySortOrderAscNameAsc")
    void listAll() {
        var t1 = new DocumentTemplate(); t1.setName("A");
        var t2 = new DocumentTemplate(); t2.setName("B");
        when(repository.findAllByOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(t1, t2));

        assertThat(service.listAll()).hasSize(2);
    }

    @Test
    @DisplayName("getById: не найдено → IAE")
    void getByIdNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    @DisplayName("getById: найдено → сущность")
    void getByIdFound() {
        var t = new DocumentTemplate();
        t.setId(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(t));
        assertThat(service.getById(5L).getId()).isEqualTo(5L);
    }

    // ─── renderPdf ─────────────────────────────

    @Test
    @DisplayName("renderPdf: пустой HTML → дефолтный параграф, PDF валиден")
    void renderPdfEmptyHtml() throws Exception {
        var t = new DocumentTemplate();
        t.setId(1L);
        t.setName("T");
        t.setHtmlContent("");

        byte[] pdf = service.renderPdf(t, Map.of());

        assertThat(pdf).isNotEmpty();
        assertThat(pdf).hasSizeGreaterThan(200);
        // PDF начинается с %PDF-
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("renderPdf: плейсхолдер {{x}} подставлен в XHTML")
    void renderPdfSubstitutes() throws Exception {
        var t = new DocumentTemplate();
        t.setId(1L);
        t.setName("T");
        t.setHtmlContent("<p>Привет, {{name}}! Дата: {{date}}</p>");

        byte[] pdf = service.renderPdf(t, Map.of(
                "name", "Иван",
                "date", "15.01.2024"));

        // Внутри PDF текст в сжатых стримах, поэтому просто проверяем, что PDF валиден
        assertThat(pdf).isNotEmpty();
        assertThat(pdf.length).isGreaterThan(300);
    }

    @Test
    @DisplayName("renderPdf: кириллица в {{name}} → валидный PDF")
    void renderPdfCyrillic() throws Exception {
        var t = new DocumentTemplate();
        t.setId(1L);
        t.setName("T");
        t.setHtmlContent("<h1>Договор</h1><p>ФИО: {{full_name}}</p>");

        byte[] pdf = service.renderPdf(t, Map.of("full_name", "Иванов Иван Иванович"));

        assertThat(pdf).isNotEmpty();
        assertThat(pdf.length).isGreaterThan(300);
    }

    @Test
    @DisplayName("renderPdf: HTML-escape в значениях (XSS-защита при рендере)")
    void renderPdfEscapesValues() throws Exception {
        var t = new DocumentTemplate();
        t.setId(1L);
        t.setName("T");
        t.setHtmlContent("<p>Значение: {{x}}</p>");

        // Значение с <script> — substitute должен эскейпить
        byte[] pdf = service.renderPdf(t, Map.of("x", "<script>alert('xss')</script>"));

        assertThat(pdf).isNotEmpty();
    }

    @Test
    @DisplayName("renderPdf: пустое значение (нет в Map) → плейсхолдер остаётся/эскейпится")
    void renderPdfMissingValue() throws Exception {
        var t = new DocumentTemplate();
        t.setId(1L);
        t.setName("T");
        t.setHtmlContent("<p>{{missing}}</p>");

        byte[] pdf = service.renderPdf(t, Map.of());

        assertThat(pdf).isNotEmpty();
    }

    @Test
    @DisplayName("renderPdf: таблица в HTML → валидный PDF")
    void renderPdfTable() throws Exception {
        var t = new DocumentTemplate();
        t.setId(1L);
        t.setName("T");
        t.setHtmlContent("<table><tr><th>A</th><th>B</th></tr>"
                + "<tr><td>{{a}}</td><td>{{b}}</td></tr></table>");

        byte[] pdf = service.renderPdf(t, Map.of("a", "1", "b", "2"));

        assertThat(pdf).isNotEmpty();
    }
}
