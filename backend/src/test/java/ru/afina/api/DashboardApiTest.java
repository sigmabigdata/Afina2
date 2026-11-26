package ru.afina.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.User;

import java.time.Instant;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сводка главного экрана кабинета и разбивка документов по расширениям.
 */
@DisplayName("DashboardApi: сводка кабинета")
class DashboardApiTest extends ApiTestBase {

    @Test
    @DisplayName("Сводка: документы, подписи, разделы и активность")
    void dashboard() throws Exception {
        User client = createClient();
        DocumentFolder folder = createFolder(client, "Договоры");
        createDocument(client, folder, "Договор.pdf");
        markSigned(createDocument(client, folder, "Акт.pdf"));

        // Документ другого клиента не должен влиять на сводку.
        createDocument(createClient(), "Чужой.pdf");

        mockMvc.perform(asGet(client, get("/api/v1/dashboard")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsTotal").value(2))
                .andExpect(jsonPath("$.documentsSigned").value(1))
                .andExpect(jsonPath("$.documentsUnsigned").value(1))
                .andExpect(jsonPath("$.foldersTotal").value(1))
                .andExpect(jsonPath("$.gosklyuchPending").value(0))
                .andExpect(jsonPath("$.signaturesToday").value(1))
                .andExpect(jsonPath("$.averageSigningTime").isNotEmpty())
                .andExpect(jsonPath("$.activity.length()").value(7))
                .andExpect(jsonPath("$.activity[6].uploaded").value(2))
                .andExpect(jsonPath("$.activity[6].signed").value(1))
                .andExpect(jsonPath("$.activity[0].label").isNotEmpty())
                .andExpect(jsonPath("$.recentDocuments.length()").value(2))
                .andExpect(jsonPath("$.recentOrders").isArray());
    }

    @Test
    @DisplayName("Параметр days задаёт ширину окна активности")
    void dashboardWindow() throws Exception {
        User client = createClient();
        createDocument(client, "Документ.pdf");

        mockMvc.perform(asGet(client, get("/api/v1/dashboard").param("days", "3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activity.length()").value(3))
                .andExpect(jsonPath("$.activity[2].uploaded").value(1));
    }

    @Test
    @DisplayName("/stats: документы по расширениям только текущего клиента")
    void stats() throws Exception {
        User client = createClient();
        createDocument(client, "Договор.pdf");
        createDocument(client, "Акт.docx");
        createDocument(createClient(), "Чужой.pdf");

        mockMvc.perform(asGet(client, get("/api/v1/dashboard/stats")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(2))
                .andExpect(jsonPath("$.byExtension.pdf").value(1))
                .andExpect(jsonPath("$.byExtension.docx").value(1))
                .andExpect(jsonPath("$.signatures").isNumber());
    }

    // ──────────────────────────── helpers ────────────────────────────

    private void markSigned(Document doc) {
        doc.setSigned(true);
        doc.setSignedAt(Instant.now());
        documentRepository.save(doc);
    }
}
