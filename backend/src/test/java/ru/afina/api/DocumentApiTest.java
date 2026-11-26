package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.User;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Документы клиента: список, карточка, загрузка, скачивание, удаление. */
class DocumentApiTest extends ApiTestBase {

    @Test
    @DisplayName("Список документов: только свои, с разделом и статусом")
    void listReturnsOwnDocuments() throws Exception {
        User client = createClient();
        DocumentFolder folder = createFolder(client, "Договоры");
        createDocument(client, folder, "Договор.pdf");
        createDocument(client, folder, "Акт.pdf");
        createDocument(createClient(), "Чужой документ.pdf");

        mockMvc.perform(asGet(client, get("/api/v1/documents")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].name").isNotEmpty())
                .andExpect(jsonPath("$.items[0].folderName").value("Договоры"))
                .andExpect(jsonPath("$.items[0].sizePretty").isNotEmpty());
    }

    @Test
    @DisplayName("Фильтр по разделу и поиск по названию")
    void listFilters() throws Exception {
        User client = createClient();
        DocumentFolder contracts = createFolder(client, "Договоры");
        DocumentFolder acts = createFolder(client, "Акты");
        createDocument(client, contracts, "Договор поставки.pdf");
        createDocument(client, acts, "Акт сверки.pdf");

        mockMvc.perform(asGet(client, get("/api/v1/documents")
                        .param("folderId", String.valueOf(contracts.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Договор поставки.pdf"));

        mockMvc.perform(asGet(client, get("/api/v1/documents").param("search", "акт")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Акт сверки.pdf"));
    }

    @Test
    @DisplayName("Фильтр по статусу: без подписи")
    void listFilterUnsigned() throws Exception {
        User client = createClient();
        createDocument(client, "Документ.pdf");

        mockMvc.perform(asGet(client, get("/api/v1/documents").param("status", "unsigned")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(asGet(client, get("/api/v1/documents").param("status", "signed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("Карточка документа и 404 для чужого")
    void detail() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Договор.pdf");

        mockMvc.perform(asGet(client, get("/api/v1/documents/" + doc.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(doc.getId()))
                .andExpect(jsonPath("$.publicUuid").isNotEmpty())
                .andExpect(jsonPath("$.signaturesCount").value(0));

        User stranger = createClient();
        mockMvc.perform(asGet(stranger, get("/api/v1/documents/" + doc.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Загрузка файла создаёт документ в разделе по умолчанию")
    void upload() throws Exception {
        User client = createClient();
        MockMultipartFile file = new MockMultipartFile(
                "file", "Договор.pdf", "application/pdf",
                "PDF-содержимое".getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(as(client, multipart("/api/v1/documents").file(file)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Договор.pdf"))
                .andExpect(jsonPath("$.folderName").value("Без раздела"))
                .andReturn();

        long id = json(result).get("id").asLong();
        assertThat(documentRepository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("Загрузка пустого файла отклоняется")
    void uploadEmptyRejected() throws Exception {
        User client = createClient();
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(as(client, multipart("/api/v1/documents").file(file)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Просмотр и скачивание содержимого")
    void contentAndDownload() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Договор.pdf");

        mockMvc.perform(asGet(client, get("/api/v1/documents/" + doc.getId() + "/content")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("inline")))
                .andExpect(content().bytes("Содержимое документа Договор.pdf"
                        .getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(asGet(client, get("/api/v1/documents/" + doc.getId() + "/download")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    @DisplayName("Удаление документа и повторное удаление → 404")
    void delete() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Договор.pdf");

        mockMvc.perform(as(client, delete("/api/v1/documents/" + doc.getId())))
                .andExpect(status().isNoContent());

        mockMvc.perform(asGet(client, get("/api/v1/documents/" + doc.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Публичная ссылка на документ")
    void publicLink() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Договор.pdf");

        MvcResult result = mockMvc.perform(asGet(client,
                        get("/api/v1/documents/" + doc.getId() + "/public-link")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uuid").value(doc.getPublicUuid().toString()))
                .andReturn();
        assertThat(json(result).get("url").asText()).contains("/verify/");
    }

    @Test
    @DisplayName("Подписание пустой подписью → 400")
    void signWithEmptySignature() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Договор.pdf");

        mockMvc.perform(as(client, post("/api/v1/documents/" + doc.getId() + "/sign"))
                        .contentType("application/json")
                        .content(body(java.util.Map.of("documentId", doc.getId(),
                                "signatureBase64", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Пакетное подписание с пустым списком → 400")
    void signBatchEmpty() throws Exception {
        User client = createClient();
        mockMvc.perform(as(client, post("/api/v1/documents/sign-batch"))
                        .contentType("application/json")
                        .content("[]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Скачивание подписи без подписи → 404")
    void signatureAbsent() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Договор.pdf");

        mockMvc.perform(asGet(client, get("/api/v1/documents/" + doc.getId() + "/signature")))
                .andExpect(status().isNotFound());
        mockMvc.perform(asGet(client, get("/api/v1/documents/" + doc.getId() + "/download-signed")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Госключ выключен → 404")
    void gosklyuchDisabled() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Договор.pdf");

        mockMvc.perform(as(client, post("/api/v1/documents/" + doc.getId() + "/gosklyuch")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Серверное время и список заявок Госключа")
    void utilityEndpoints() throws Exception {
        User client = createClient();
        mockMvc.perform(asGet(client, get("/api/v1/documents/server-time")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.now").isNotEmpty());
        mockMvc.perform(asGet(client, get("/api/v1/documents/gosklyuch/orders")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Документ без согласия: загрузка запрещена")
    void uploadRequiresConsent() throws Exception {
        User client = createClient();
        client.setConsentSignedAt(null);
        client.setFullNameConfirmedAt(java.time.Instant.now());
        userRepository.save(client);

        MockMultipartFile file = new MockMultipartFile(
                "file", "Договор.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(as(client, multipart("/api/v1/documents").file(file)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Визуализация документа без подписей — ошибка 4xx/5xx, но не падение")
    void visualizationWithoutSignatures() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Договор.pdf");
        mockMvc.perform(asGet(client, get("/api/v1/documents/" + doc.getId() + "/visualization")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Справка о подписании генерируется в PDF")
    void signingCertificate() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Договор.txt");
        MvcResult result = mockMvc.perform(asGet(client,
                        get("/api/v1/documents/" + doc.getId() + "/signing-certificate")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentType()).contains("pdf");
        assertThat(result.getResponse().getContentAsByteArray().length).isGreaterThan(500);
    }

    @Test
    @DisplayName("Загрузка .sig пустым файлом → 400")
    void uploadSigEmpty() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Договор.pdf");
        MockMultipartFile file = new MockMultipartFile("file", "sig.sig", "application/octet-stream", new byte[0]);

        mockMvc.perform(as(client, multipart("/api/v1/documents/" + doc.getId() + "/signature").file(file)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Неверная подпись отклоняется")
    void uploadSigInvalid() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Договор.pdf");
        MockMultipartFile file = new MockMultipartFile("file", "sig.sig",
                "application/octet-stream", "не-подпись".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(as(client, multipart("/api/v1/documents/" + doc.getId() + "/signature").file(file)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Применение доверенности к несуществующей подписи → 404")
    void applyPoaUnknownSignature() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Договор.pdf");

        mockMvc.perform(as(client, post("/api/v1/documents/" + doc.getId()
                        + "/signatures/999999/poa").param("apply", "true")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Ответ списка содержит пагинацию")
    void listPagination() throws Exception {
        User client = createClient();
        for (int i = 0; i < 5; i++) createDocument(client, "Документ " + i + ".pdf");

        JsonNode page = json(mockMvc.perform(asGet(client, get("/api/v1/documents")
                        .param("page", "1").param("size", "2")))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(page.get("total").asInt()).isEqualTo(5);
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("pages").asInt()).isEqualTo(3);
    }
}
