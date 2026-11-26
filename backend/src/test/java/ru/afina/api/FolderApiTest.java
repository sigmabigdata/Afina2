package ru.afina.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.User;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Разделы (папки) документов: список со счётчиками, создание, переименование,
 * удаление и защита от удаления раздела с подписанными документами.
 */
@DisplayName("FolderApi: разделы документов клиента")
class FolderApiTest extends ApiTestBase {

    @Test
    @DisplayName("Список разделов: только свои, с числом документов и подписанных")
    void listWithCounts() throws Exception {
        User client = createClient();
        DocumentFolder withDocs = createFolder(client, "Договоры");
        createDocument(client, withDocs, "Договор.pdf");
        markSigned(createDocument(client, withDocs, "Подписанный.pdf"));
        DocumentFolder empty = createFolder(client, "Пустой раздел");

        User stranger = createClient();
        createFolder(stranger, "Чужой раздел");

        JsonNode list = json(mockMvc.perform(asGet(client, get("/api/v1/folders")))
                .andExpect(status().isOk())
                .andReturn());

        JsonNode withDocsNode = findById(list, withDocs.getId());
        assertThat(withDocsNode).as("раздел «Договоры» в списке").isNotNull();
        assertThat(withDocsNode.get("documentsCount").asLong()).isEqualTo(2);
        assertThat(withDocsNode.get("signedCount").asLong()).isEqualTo(1);

        JsonNode emptyNode = findById(list, empty.getId());
        assertThat(emptyNode).as("пустой раздел в списке").isNotNull();
        assertThat(emptyNode.get("documentsCount").asLong()).isZero();
        assertThat(emptyNode.get("signedCount").asLong()).isZero();

        assertThat(findByName(list, "Чужой раздел"))
                .as("чужой раздел не должен попадать в список").isNull();
    }

    @Test
    @DisplayName("Создание раздела → 201")
    void create() throws Exception {
        User client = createClient();
        String name = "Раздел " + UUID.randomUUID();

        MvcResult result = mockMvc.perform(as(client, post("/api/v1/folders"))
                        .contentType("application/json")
                        .content(body(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.documentsCount").value(0))
                .andReturn();

        long id = json(result).get("id").asLong();
        assertThat(folderRepository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("Пустое имя раздела → 400")
    void createBlankName() throws Exception {
        User client = createClient();

        mockMvc.perform(as(client, post("/api/v1/folders"))
                        .contentType("application/json")
                        .content(body(Map.of("name", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("Укажите название раздела"));
    }

    @Test
    @DisplayName("Переименование раздела")
    void rename() throws Exception {
        User client = createClient();
        DocumentFolder folder = createFolder(client, "Старое имя");
        String newName = "Новое имя " + UUID.randomUUID();

        mockMvc.perform(as(client, patch("/api/v1/folders/" + folder.getId()))
                        .contentType("application/json")
                        .content(body(Map.of("name", newName))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(folder.getId()))
                .andExpect(jsonPath("$.name").value(newName));

        assertThat(folderRepository.findById(folder.getId()).orElseThrow().getName())
                .isEqualTo(newName);
    }

    @Test
    @DisplayName("Удаление пустого раздела → 204")
    void deleteEmpty() throws Exception {
        User client = createClient();
        DocumentFolder folder = createFolder(client, "Удаляемый " + UUID.randomUUID());

        mockMvc.perform(as(client, delete("/api/v1/folders/" + folder.getId())))
                .andExpect(status().isNoContent());

        assertThat(folderRepository.findById(folder.getId())).isEmpty();
    }

    @Test
    @DisplayName("Удаление раздела с подписанным документом → 409")
    void deleteWithSignedDocument() throws Exception {
        User client = createClient();
        DocumentFolder folder = createFolder(client, "С подписью " + UUID.randomUUID());
        Document signed = createDocument(client, folder, "Подписанный.pdf");
        markSigned(signed);

        mockMvc.perform(as(client, delete("/api/v1/folders/" + folder.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("подписанные документы")));

        assertThat(folderRepository.findById(folder.getId()))
                .as("раздел с подписанным документом не должен удаляться").isPresent();
    }

    @Test
    @DisplayName("Чужой раздел → 404")
    void foreignFolder() throws Exception {
        User owner = createClient();
        DocumentFolder foreign = createFolder(owner, "Чужой " + UUID.randomUUID());
        User stranger = createClient();

        mockMvc.perform(as(stranger, patch("/api/v1/folders/" + foreign.getId()))
                        .contentType("application/json")
                        .content(body(Map.of("name", "Взлом"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        assertThat(folderRepository.findById(foreign.getId()).orElseThrow().getName())
                .as("чужой раздел не переименовывается").isEqualTo(foreign.getName());
    }

    // ──────────────────────────── helpers ────────────────────────────

    /** Помечает документ подписанным через репозиторий (подпись не нужна для счётчика). */
    private void markSigned(Document doc) {
        doc.setSigned(true);
        doc.setSignedAt(Instant.now());
        documentRepository.save(doc);
    }

    private static JsonNode findById(JsonNode list, Long id) {
        for (JsonNode node : list) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }

    private static JsonNode findByName(JsonNode list, String name) {
        for (JsonNode node : list) {
            if (name.equals(node.get("name").asText())) {
                return node;
            }
        }
        return null;
    }
}
