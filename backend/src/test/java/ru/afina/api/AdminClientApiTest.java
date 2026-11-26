package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.Role;
import ru.afina.entity.User;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Админский API клиентов: список с фильтрами, карточка, CRUD, блокировка,
 * ссылка входа, выгрузка ZIP, документы клиента и проверка прав доступа.
 */
class AdminClientApiTest extends ApiTestBase {

    private static final String BASE = "/api/v1/admin/clients";

    private User admin;

    @BeforeEach
    void initAdmin() {
        admin = createAdmin();
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@test.local";
    }

    // ════════════════════════════ список и карточка ════════════════════════════

    @Test
    @DisplayName("Список клиентов: фильтр по статусу и поиск по email")
    void listFiltersByStatusAndQuery() throws Exception {
        User active = createClient();
        User blocked = createUser(uniqueEmail("blocked"), "Заблокированный Клиент", Role.ROLE_USER);
        blocked.setEnabled(false);
        userRepository.save(blocked);

        mockMvc.perform(asGet(admin, get(BASE)
                        .param("q", blocked.getEmail())
                        .param("status", "blocked")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(blocked.getId()))
                .andExpect(jsonPath("$.items[0].enabled").value(false));

        // у активного клиента статус blocked не совпадает
        mockMvc.perform(asGet(admin, get(BASE)
                        .param("q", active.getEmail())
                        .param("status", "blocked")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(asGet(admin, get(BASE)
                        .param("q", active.getEmail())
                        .param("status", "active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].email").value(active.getEmail()));
    }

    @Test
    @DisplayName("Список клиентов: постраничная выдача PageDto")
    void listPagination() throws Exception {
        User client = createClient();

        MvcResult result = mockMvc.perform(asGet(admin, get(BASE)
                        .param("q", client.getEmail())
                        .param("page", "0")
                        .param("size", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.pages").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn();

        assertThat(json(result).get("page").asInt()).isZero();
    }

    @Test
    @DisplayName("Карточка клиента: профиль, разделы и документы")
    void card() throws Exception {
        User client = createClient();
        DocumentFolder folder = createFolder(client, "Договоры");
        Document doc = createDocument(client, folder, "Договор.pdf");

        mockMvc.perform(asGet(admin, get(BASE + "/" + client.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client.id").value(client.getId()))
                .andExpect(jsonPath("$.client.email").value(client.getEmail()))
                .andExpect(jsonPath("$.folders[0].name").value("Договоры"))
                .andExpect(jsonPath("$.documents[0].id").value(doc.getId()))
                .andExpect(jsonPath("$.documents[0].name").value("Договор.pdf"))
                .andExpect(jsonPath("$.gosklyuchOrders").isArray())
                .andExpect(jsonPath("$.consentConfigured").isBoolean());
    }

    @Test
    @DisplayName("Карточка несуществующего клиента → 404")
    void cardMissingClient() throws Exception {
        mockMvc.perform(asGet(admin, get(BASE + "/99999999")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    // ════════════════════════════ создание / изменение ════════════════════════════

    @Test
    @DisplayName("Создание клиента → 201 и автоматический раздел «Без раздела»")
    void createClientReturns201() throws Exception {
        String email = uniqueEmail("new");

        MvcResult result = mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of(
                                "email", email,
                                "fullName", "Новый Клиент",
                                "phone", "+79991234567",
                                "snils", "12345678901"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.fullName").value("Новый Клиент"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn();

        long id = json(result).get("id").asLong();
        assertThat(userRepository.findById(id)).isPresent();

        mockMvc.perform(asGet(admin, get(BASE + "/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folders[0].name").value("Без раздела"));
    }

    @Test
    @DisplayName("Создание клиента с занятым email → 400")
    void createClientDuplicateEmail() throws Exception {
        String email = uniqueEmail("dup");
        Map<String, String> request = Map.of("email", email, "fullName", "Дубликат");

        mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json").content(body(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json").content(body(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    @DisplayName("Создание клиента без email → 400")
    void createClientWithoutEmail() throws Exception {
        mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of("fullName", "Без Email"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Обновление клиента через PATCH")
    void updateClient() throws Exception {
        User client = createClient();
        String newEmail = uniqueEmail("patched");

        mockMvc.perform(as(admin, patch(BASE + "/" + client.getId()))
                        .contentType("application/json")
                        .content(body(Map.of(
                                "email", newEmail,
                                "fullName", "Обновлённый Клиент",
                                "role", "ROLE_USER",
                                "enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Обновлённый Клиент"))
                .andExpect(jsonPath("$.email").value(newEmail));

        User reloaded = userRepository.findById(client.getId()).orElseThrow();
        assertThat(reloaded.getFullName()).isEqualTo("Обновлённый Клиент");
        assertThat(reloaded.getEmail()).isEqualTo(newEmail);
    }

    @Test
    @DisplayName("PATCH с занятым email → 400")
    void updateClientDuplicateEmail() throws Exception {
        User first = createClient();
        User second = createClient();

        mockMvc.perform(as(admin, patch(BASE + "/" + second.getId()))
                        .contentType("application/json")
                        .content(body(Map.of("email", first.getEmail()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH без поля enabled сохраняет блокировку (ожидаемое поведение)")
    void updateClientKeepsBlockedStatusWhenEnabledOmitted() throws Exception {
        User client = createClient();
        client.setEnabled(false);
        userRepository.save(client);

        mockMvc.perform(as(admin, patch(BASE + "/" + client.getId()))
                        .contentType("application/json")
                        .content(body(Map.of("fullName", "Только имя"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(userRepository.findById(client.getId()).orElseThrow().isEnabled()).isFalse();
    }

    // ════════════════════════════ блокировка / удаление ════════════════════════════

    @Test
    @DisplayName("Переключение блокировки клиента")
    void toggleClient() throws Exception {
        User client = createClient();

        mockMvc.perform(as(admin, post(BASE + "/" + client.getId() + "/toggle")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(as(admin, post(BASE + "/" + client.getId() + "/toggle")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        // администратора блокировать нельзя
        mockMvc.perform(as(admin, post(BASE + "/" + admin.getId() + "/toggle")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Удаление клиента без данных → 204, затем карточка 404")
    void deleteClient() throws Exception {
        User client = createClient();

        mockMvc.perform(as(admin, delete(BASE + "/" + client.getId())))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(client.getId())).isEmpty();
        mockMvc.perform(asGet(admin, get(BASE + "/" + client.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Удаление несуществующего клиента → 404 (ожидаемое поведение)")
    void deleteMissingClientReturns404() throws Exception {
        mockMvc.perform(as(admin, delete(BASE + "/99999999")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Удаление клиента, созданного через админский API, → 204 (ожидаемое поведение)")
    void deleteClientCreatedViaAdminApi() throws Exception {
        String email = uniqueEmail("created-via-api");
        MvcResult created = mockMvc.perform(as(admin, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of("email", email, "fullName", "Клиент из админки"))))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json(created).get("id").asLong();

        mockMvc.perform(as(admin, delete(BASE + "/" + id)))
                .andExpect(status().isNoContent());
    }

    // ════════════════════════════ ссылка входа ════════════════════════════

    @Test
    @DisplayName("Ссылка входа: успех с маскированием email")
    void sendLoginLink() throws Exception {
        User client = createClient();

        MvcResult result = mockMvc.perform(as(admin,
                        post(BASE + "/" + client.getId() + "/login-link")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andReturn();

        String masked = json(result).get("email").asText();
        assertThat(masked).contains("***").endsWith("@test.local");
    }

    @Test
    @DisplayName("Ссылка входа заблокированному клиенту → 409")
    void sendLoginLinkToBlockedClient() throws Exception {
        User client = createClient();
        client.setEnabled(false);
        userRepository.save(client);

        mockMvc.perform(as(admin, post(BASE + "/" + client.getId() + "/login-link")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Ссылка входа несуществующему клиенту → 404")
    void sendLoginLinkMissingClient() throws Exception {
        mockMvc.perform(as(admin, post(BASE + "/99999999/login-link")))
                .andExpect(status().isNotFound());
    }

    // ════════════════════════════ выгрузка ZIP ════════════════════════════

    @Test
    @DisplayName("Выгрузка карточки клиента в ZIP")
    void exportClientZip() throws Exception {
        User client = createClient();
        createDocument(client, "Договор.pdf");

        MvcResult result = mockMvc.perform(asGet(admin,
                        get(BASE + "/" + client.getId() + "/export")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", allOf(
                        containsString("attachment"),
                        containsString("client_" + client.getId() + ".zip"))))
                .andReturn();

        byte[] archive = result.getResponse().getContentAsByteArray();
        assertThat(archive[0]).isEqualTo((byte) 'P');
        assertThat(archive[1]).isEqualTo((byte) 'K');

        Set<String> entries = zipEntries(archive);
        assertThat(entries).contains("README.txt", "metadata.json");
        assertThat(entries).anyMatch(n -> n.startsWith("documents/") && n.endsWith(".pdf"));
    }

    private static Set<String> zipEntries(byte[] archive) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    // ════════════════════════════ документы и разделы клиента ════════════════════════════

    @Test
    @DisplayName("Загрузка документа за клиента в раздел по умолчанию")
    void uploadDocumentForClient() throws Exception {
        User client = createClient();
        MockMultipartFile file = new MockMultipartFile("file", "Акт.pdf", "application/pdf",
                "данные документа".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(as(admin, multipart(BASE + "/" + client.getId() + "/documents")
                        .file(file)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Акт.pdf"))
                .andExpect(jsonPath("$.folderName").value("Без раздела"))
                .andExpect(jsonPath("$.ownerId").value(client.getId()));
    }

    @Test
    @DisplayName("Загрузка документа за клиента в выбранный раздел")
    void uploadDocumentForClientIntoFolder() throws Exception {
        User client = createClient();
        DocumentFolder folder = createFolder(client, "Договоры");
        MockMultipartFile file = new MockMultipartFile("file", "Договор.pdf", "application/pdf",
                "данные договора".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(as(admin, multipart(BASE + "/" + client.getId() + "/documents")
                        .file(file)
                        .param("folderId", String.valueOf(folder.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folderId").value(folder.getId()))
                .andExpect(jsonPath("$.folderName").value("Договоры"));
    }

    @Test
    @DisplayName("Загрузка в раздел чужого клиента → 404")
    void uploadDocumentIntoForeignFolder() throws Exception {
        User client = createClient();
        User stranger = createClient();
        DocumentFolder foreign = createFolder(stranger, "Чужой раздел");
        MockMultipartFile file = new MockMultipartFile("file", "Файл.pdf", "application/pdf",
                "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(as(admin, multipart(BASE + "/" + client.getId() + "/documents")
                        .file(file)
                        .param("folderId", String.valueOf(foreign.getId()))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Загрузка документа несуществующему клиенту → 404")
    void uploadDocumentForMissingClient() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "Файл.pdf", "application/pdf",
                "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(as(admin, multipart(BASE + "/99999999/documents").file(file)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Создание раздела у клиента и проверка дубликата")
    void createFolderForClient() throws Exception {
        User client = createClient();

        mockMvc.perform(as(admin, post(BASE + "/" + client.getId() + "/folders"))
                        .contentType("application/json")
                        .content(body(Map.of("name", "Договоры"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Договоры"))
                .andExpect(jsonPath("$.documentsCount").value(0));

        mockMvc.perform(as(admin, post(BASE + "/" + client.getId() + "/folders"))
                        .contentType("application/json")
                        .content(body(Map.of("name", "Договоры"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(as(admin, post(BASE + "/" + client.getId() + "/folders"))
                        .contentType("application/json")
                        .content(body(Map.of("name", " "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Создание раздела у несуществующего клиента → 404 (ожидаемое поведение)")
    void createFolderForMissingClientReturns404() throws Exception {
        mockMvc.perform(as(admin, post(BASE + "/99999999/folders"))
                        .contentType("application/json")
                        .content(body(Map.of("name", "Договоры"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Админ видит и скачивает документ клиента и удаляет его")
    void adminDocumentViewDownloadDelete() throws Exception {
        User client = createClient();
        Document doc = createDocument(client, "Акт сверки.pdf");
        String expected = "Содержимое документа Акт сверки.pdf";

        mockMvc.perform(asGet(admin, get("/api/v1/admin/documents/" + doc.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(doc.getId()))
                .andExpect(jsonPath("$.name").value("Акт сверки.pdf"))
                .andExpect(jsonPath("$.ownerId").value(client.getId()));

        mockMvc.perform(asGet(admin, get("/api/v1/admin/documents/" + doc.getId() + "/content")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(expected.getBytes(StandardCharsets.UTF_8)))
                .andExpect(header().string("Content-Disposition", containsString("inline")));

        mockMvc.perform(asGet(admin, get("/api/v1/admin/documents/" + doc.getId() + "/download")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")));

        mockMvc.perform(as(admin, delete("/api/v1/admin/documents/" + doc.getId())))
                .andExpect(status().isNoContent());

        assertThat(documentRepository.findById(doc.getId())).isEmpty();
        mockMvc.perform(asGet(admin, get("/api/v1/admin/documents/" + doc.getId())))
                .andExpect(status().isNotFound());
    }

    // ════════════════════════════ права доступа ════════════════════════════

    @Test
    @DisplayName("Не-администратор получает 403 на всех админских операциях")
    void nonAdminForbidden() throws Exception {
        User client = createClient();
        User other = createClient();
        Document doc = createDocument(other, "Чужой.pdf");

        mockMvc.perform(asGet(client, get(BASE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/" + other.getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, post(BASE))
                        .contentType("application/json")
                        .content(body(Map.of("email", uniqueEmail("forbidden"),
                                "fullName", "Нельзя"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, patch(BASE + "/" + other.getId()))
                        .contentType("application/json")
                        .content(body(Map.of("fullName", "Нельзя"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, post(BASE + "/" + other.getId() + "/toggle")))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, delete(BASE + "/" + other.getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, post(BASE + "/" + other.getId() + "/login-link")))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get(BASE + "/" + other.getId() + "/export")))
                .andExpect(status().isForbidden());
        mockMvc.perform(asGet(client, get("/api/v1/admin/documents/" + doc.getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(client, delete("/api/v1/admin/documents/" + doc.getId())))
                .andExpect(status().isForbidden());
    }
}
