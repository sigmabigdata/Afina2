package ru.afina.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import ru.afina.entity.Document;
import ru.afina.entity.User;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Работа администратора с документами клиентов. */
class AdminDocumentApiTest extends ApiTestBase {

    private Document createDoc(User owner, String text) {
        try {
            return documentService.createDocument(text.getBytes(StandardCharsets.UTF_8),
                    "Документ.txt", "text/plain", owner, folderService.ensureDefault(owner));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Админ видит карточку и содержимое документа клиента")
    void readClientDocument() throws Exception {
        User admin = createAdmin();
        User client = createClient();
        Document doc = createDoc(client, "Договор клиента");

        mockMvc.perform(asGet(admin, get("/api/v1/admin/documents/" + doc.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Документ.txt"))
                .andExpect(jsonPath("$.ownerName").value(client.getFullName()));

        mockMvc.perform(asGet(admin, get("/api/v1/admin/documents/" + doc.getId() + "/content")))
                .andExpect(status().isOk())
                .andExpect(content().bytes("Договор клиента".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(asGet(admin, get("/api/v1/admin/documents/" + doc.getId() + "/download")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Админ подписывает документ клиента своей УКЭП")
    void signAsAdmin() throws Exception {
        User admin = createAdmin();
        User client = createClient();
        Document doc = createDoc(client, "Договор для подписи админом");
        String sig = TestSignatures.detached(documentService.getBytes(doc));

        mockMvc.perform(as(admin, post("/api/v1/admin/documents/" + doc.getId() + "/sign"))
                        .contentType("application/json")
                        .content(body(Map.of("documentId", doc.getId(), "signatureBase64", sig))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        Document fresh = documentRepository.findByIdWithSignatures(doc.getId()).orElseThrow();
        assertThat(fresh.isSigned()).isTrue();
        assertThat(fresh.getSignatures().get(0).getSignerUser().getId()).isEqualTo(admin.getId());
    }

    @Test
    @DisplayName("Админ не может подписать один документ дважды")
    void signAsAdminTwice() throws Exception {
        User admin = createAdmin();
        User client = createClient();
        Document doc = createDoc(client, "Документ");
        String sig = TestSignatures.detached(documentService.getBytes(doc));

        mockMvc.perform(as(admin, post("/api/v1/admin/documents/" + doc.getId() + "/sign"))
                        .contentType("application/json")
                        .content(body(Map.of("documentId", doc.getId(), "signatureBase64", sig))))
                .andExpect(status().isOk());

        mockMvc.perform(as(admin, post("/api/v1/admin/documents/" + doc.getId() + "/sign"))
                        .contentType("application/json")
                        .content(body(Map.of("documentId", doc.getId(), "signatureBase64", sig))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Админ скачивает подпись и архив подписей клиента")
    void downloadSignatures() throws Exception {
        User admin = createAdmin();
        User client = createClient();
        Document doc = createDoc(client, "Документ");
        String sig = TestSignatures.detached(documentService.getBytes(doc));
        mockMvc.perform(as(admin, post("/api/v1/admin/documents/" + doc.getId() + "/sign"))
                        .contentType("application/json")
                        .content(body(Map.of("documentId", doc.getId(), "signatureBase64", sig))))
                .andExpect(status().isOk());

        byte[] signature = mockMvc.perform(asGet(admin,
                        get("/api/v1/admin/documents/" + doc.getId() + "/signature")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(signature).isNotEmpty();

        byte[] zip = mockMvc.perform(asGet(admin,
                        get("/api/v1/admin/clients/" + client.getId() + "/signatures.zip")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(zip[0]).isEqualTo((byte) 'P');
    }

    @Test
    @DisplayName("Админ загружает документ за клиента")
    void uploadForClient() throws Exception {
        User admin = createAdmin();
        User client = createClient();
        var folder = folderService.ensureDefault(client);

        mockMvc.perform(as(admin, multipart("/api/v1/admin/clients/" + client.getId() + "/documents")
                        .file(new MockMultipartFile("file", "Загруженный.txt", "text/plain",
                                "текст".getBytes(StandardCharsets.UTF_8)))
                        .param("folderId", String.valueOf(folder.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Загруженный.txt"));

        assertThat(documentRepository.countByOwner(client)).isEqualTo(1);
    }

    @Test
    @DisplayName("Админ удаляет документ клиента")
    void deleteClientDocument() throws Exception {
        User admin = createAdmin();
        User client = createClient();
        Document doc = createDoc(client, "На удаление");

        mockMvc.perform(as(admin, delete("/api/v1/admin/documents/" + doc.getId())))
                .andExpect(status().isNoContent());
        assertThat(documentRepository.findById(doc.getId())).isEmpty();
    }

    @Test
    @DisplayName("Клиенту админские операции с документами недоступны")
    void clientCannotUseAdminDocumentApi() throws Exception {
        User client = createClient();
        Document doc = createDoc(client, "Документ");

        mockMvc.perform(asGet(client, get("/api/v1/admin/documents/" + doc.getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, delete("/api/v1/admin/documents/" + doc.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Несуществующий документ → 404")
    void missingDocument() throws Exception {
        User admin = createAdmin();
        mockMvc.perform(asGet(admin, get("/api/v1/admin/documents/999999")))
                .andExpect(status().isNotFound());
    }
}
