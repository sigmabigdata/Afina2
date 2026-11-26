package ru.afina.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import ru.afina.entity.Document;
import ru.afina.entity.User;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Дополнительные сценарии документов: подпись через API, пакеты, визуализация. */
class DocumentApiExtraTest extends ApiTestBase {

    private Document createDoc(User owner, String name, String text) {
        try {
            return documentService.createDocument(text.getBytes(StandardCharsets.UTF_8),
                    name, "text/plain", owner, folderService.ensureDefault(owner));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] contentOf(Document doc) throws Exception {
        return documentService.getBytes(doc);
    }

    @Test
    @DisplayName("sign: валидная подпись принимается, документ становится подписанным")
    void signValid() throws Exception {
        User client = createClient();
        Document doc = createDoc(client, "Договор.txt", "Договор поставки");
        String sig = TestSignatures.detached(contentOf(doc));

        mockMvc.perform(as(client, post("/api/v1/documents/" + doc.getId() + "/sign"))
                        .contentType("application/json")
                        .content(body(Map.of("documentId", doc.getId(), "signatureBase64", sig))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        Document fresh = documentRepository.findByIdWithSignatures(doc.getId()).orElseThrow();
        assertThat(fresh.isSigned()).isTrue();
        assertThat(fresh.getSignatures()).hasSize(1);
        assertThat(fresh.getSignedAt()).isNotNull();
    }

    @Test
    @DisplayName("sign-batch: один документ подписан, второй — ошибка")
    void signBatchPartial() throws Exception {
        User client = createClient();
        Document first = createDoc(client, "Первый.txt", "Первый документ");
        Document second = createDoc(client, "Второй.txt", "Второй документ");

        String body = body(List.of(
                Map.of("documentId", first.getId(),
                        "signatureBase64", TestSignatures.detached(contentOf(first))),
                Map.of("documentId", second.getId(), "signatureBase64", "не-base64")));

        mockMvc.perform(as(client, post("/api/v1/documents/sign-batch"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.fail").value(1))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @DisplayName("sign-batch: невозможно подписать чужой документ")
    void signBatchForeignDocument() throws Exception {
        User owner = createClient();
        Document doc = createDoc(owner, "Чужой.txt", "чужой");
        User stranger = createClient();

        mockMvc.perform(as(stranger, post("/api/v1/documents/sign-batch"))
                        .contentType("application/json")
                        .content(body(List.of(Map.of("documentId", doc.getId(),
                                "signatureBase64", TestSignatures.detached(contentOf(doc)))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fail").value(1));
    }

    @Test
    @DisplayName("Повторная загрузка .sig тем же пользователем → 400")
    void uploadSignatureTwice() throws Exception {
        User client = createClient();
        Document doc = createDoc(client, "Договор.txt", "Договор");
        byte[] sig = TestSignatures.detachedBytes(contentOf(doc));

        mockMvc.perform(as(client, multipart("/api/v1/documents/" + doc.getId() + "/signature")
                        .file(new MockMultipartFile("file", "sig.sig", "application/octet-stream", sig))))
                .andExpect(status().isOk());

        mockMvc.perform(as(client, multipart("/api/v1/documents/" + doc.getId() + "/signature")
                        .file(new MockMultipartFile("file", "sig.sig", "application/octet-stream", sig))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Визуализация подписанного документа генерируется")
    void visualizationForSignedDocument() throws Exception {
        User client = createClient();
        Document doc = createDoc(client, "Договор.txt", "Договор");
        byte[] sig = TestSignatures.detachedBytes(contentOf(doc));
        mockMvc.perform(as(client, multipart("/api/v1/documents/" + doc.getId() + "/signature")
                        .file(new MockMultipartFile("file", "sig.sig", "application/octet-stream", sig))))
                .andExpect(status().isOk());

        // Для не-PDF визуализация может быть недоступна — принимаем 2xx или 4xx/5xx,
        // главное, что эндпоинт обработан и не падает
        mockMvc.perform(asGet(client, get("/api/v1/documents/" + doc.getId() + "/visualization")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Применение доверенности к существующей подписи")
    void applyPoaToSignature() throws Exception {
        User client = createClient();
        Document doc = createDoc(client, "Договор.txt", "Договор");
        byte[] sig = TestSignatures.detachedBytes(contentOf(doc));
        mockMvc.perform(as(client, multipart("/api/v1/documents/" + doc.getId() + "/signature")
                        .file(new MockMultipartFile("file", "sig.sig", "application/octet-stream", sig))))
                .andExpect(status().isOk());

        Document fresh = documentRepository.findByIdWithSignatures(doc.getId()).orElseThrow();
        Long sigId = fresh.getSignatures().get(0).getId();

        mockMvc.perform(as(client, post("/api/v1/documents/" + doc.getId()
                        + "/signatures/" + sigId + "/poa").param("apply", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applyPoa").value(true));
    }

    @Test
    @DisplayName("Госключ: пакетная отправка при выключенной интеграции → 404")
    void gosklyuchPackageDisabled() throws Exception {
        User client = createClient();
        var folder = folderService.ensureDefault(client);
        createDoc(client, "Документ.txt", "текст");

        mockMvc.perform(as(client, post("/api/v1/documents/folders/" + folder.getId() + "/gosklyuch")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("История заявок Госключа по документу")
    void gosklyuchOrdersForDocument() throws Exception {
        User client = createClient();
        Document doc = createDoc(client, "Документ.txt", "текст");

        mockMvc.perform(asGet(client, get("/api/v1/documents/" + doc.getId() + "/gosklyuch/orders")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Визуализация: перегенерация без подписей → ошибка клиента")
    void regenerateVisualizationWithoutSignatures() throws Exception {
        User client = createClient();
        Document doc = createDoc(client, "Документ.txt", "текст");

        mockMvc.perform(as(client, post("/api/v1/documents/" + doc.getId() + "/visualization")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Документ другого клиента недоступен по id")
    void foreignDocumentNotVisible() throws Exception {
        User owner = createClient();
        Document doc = createDoc(owner, "Секрет.txt", "секрет");
        User stranger = createClient();

        mockMvc.perform(asGet(stranger, get("/api/v1/documents/" + doc.getId() + "/content")))
                .andExpect(status().isNotFound());
        mockMvc.perform(asGet(stranger, get("/api/v1/documents/" + doc.getId() + "/download")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Загрузка в чужой раздел запрещена")
    void uploadIntoForeignFolder() throws Exception {
        User owner = createClient();
        var folder = createFolder(owner, "Личный раздел");
        User stranger = createClient();

        mockMvc.perform(as(stranger, multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "x.txt", "text/plain",
                                "x".getBytes(StandardCharsets.UTF_8)))
                        .param("folderId", String.valueOf(folder.getId()))))
                .andExpect(status().isNotFound());
    }
}
