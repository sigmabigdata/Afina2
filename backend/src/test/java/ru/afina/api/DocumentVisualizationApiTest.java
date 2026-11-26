package ru.afina.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.afina.entity.Document;
import ru.afina.entity.User;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PDF-документы: генерация визуализации штампов подписи.
 *
 * <p>Визуализация возможна только для PDF — проверяется и успешный сценарий
 * (создание, кеширование, перегенерация), и отказ для прочих форматов.</p>
 */
class DocumentVisualizationApiTest extends ApiTestBase {

    private Document createPdf(User owner, String title) throws Exception {
        byte[] pdf = ru.afina.util.SimplePdfRenderer.render(title,
                "Договор поставки. Настоящий документ подтверждает условия сделки.");
        return documentService.createDocument(pdf, title + ".pdf", "application/pdf",
                owner, folderService.ensureDefault(owner));
    }

    private void sign(User owner, Document doc) throws Exception {
        byte[] sig = TestSignatures.detachedBytes(documentService.getBytes(doc));
        mockMvc.perform(as(owner, multipart("/api/v1/documents/" + doc.getId() + "/signature")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "sig.sig", "application/octet-stream", sig))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Визуализация: PDF со подписью → PDF-файл со штампами")
    void visualizationGenerated() throws Exception {
        User client = createClient();
        Document doc = createPdf(client, "Договор поставки");
        sign(client, doc);

        byte[] pdf = mockMvc.perform(asGet(client,
                        get("/api/v1/documents/" + doc.getId() + "/visualization")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("application/pdf")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(pdf.length).isGreaterThan(1000);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Визуализация: повторный запрос отдаёт закешированный файл")
    void visualizationCached() throws Exception {
        User client = createClient();
        Document doc = createPdf(client, "Акт сверки");
        sign(client, doc);

        int first = mockMvc.perform(asGet(client,
                        get("/api/v1/documents/" + doc.getId() + "/visualization")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray().length;
        int second = mockMvc.perform(asGet(client,
                        get("/api/v1/documents/" + doc.getId() + "/visualization")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray().length;
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("Визуализация: принудительная перегенерация")
    void visualizationRegenerate() throws Exception {
        User client = createClient();
        Document doc = createPdf(client, "Соглашение");
        sign(client, doc);

        mockMvc.perform(asGet(client, get("/api/v1/documents/" + doc.getId() + "/visualization")
                        .param("regen", "true")))
                .andExpect(status().isOk());

        mockMvc.perform(as(client, post("/api/v1/documents/" + doc.getId() + "/visualization")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    @DisplayName("Визуализация: для не-PDF формата возвращается ошибка")
    void visualizationUnsupportedFormat() throws Exception {
        User client = createClient();
        Document doc = documentService.createDocument(
                "просто текст".getBytes(StandardCharsets.UTF_8), "Заметка.txt",
                "text/plain", client, folderService.ensureDefault(client));
        sign(client, doc);

        mockMvc.perform(asGet(client, get("/api/v1/documents/" + doc.getId() + "/visualization")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Справка о подписании: PDF с реквизитами подписи")
    void signingCertificatePdf() throws Exception {
        User client = createClient();
        Document doc = createPdf(client, "Договор");
        sign(client, doc);

        byte[] pdf = mockMvc.perform(asGet(client,
                        get("/api/v1/documents/" + doc.getId() + "/signing-certificate")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }
}
