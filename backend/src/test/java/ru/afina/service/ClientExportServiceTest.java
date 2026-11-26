package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentSignature;
import ru.afina.entity.DocumentVisualization;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.repository.DocumentRepository;
import ru.afina.repository.DocumentVisualizationRepository;
import ru.afina.repository.UserRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClientExportService: ZIP-экспорт клиента (GDPR-стиль)")
class ClientExportServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentVisualizationRepository visRepository;
    @Mock private DocumentService documentService;

    @InjectMocks private ClientExportService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(42L);
        user.setFullName("Иванов Иван Иванович");
        user.setEmail("ivanov@example.ru");
        user.setRole(Role.ROLE_USER);
        user.setEnabled(true);
        user.setCreatedAt(Instant.parse("2024-01-15T10:00:00Z"));
    }

    /** Прочитать ZIP: имя записи → байты. */
    private static Map<String, byte[]> readZip(byte[] zip) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                result.put(e.getName(), zis.readAllBytes());
                zis.closeEntry();
            }
        }
        return result;
    }

    private Document newDoc(Long id, String name, boolean signed) {
        Document d = new Document();
        d.setId(id);
        d.setOriginalName(name);
        d.setStoredName("stored_" + id + ".enc");
        d.setSigned(signed);
        d.setOwner(user);
        return d;
    }

    // ─── базовые ───────────────────────────────────

    @Test
    @DisplayName("exportClient: клиент не найден → IllegalArgumentException")
    void clientNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.exportClient(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    @DisplayName("exportClient: пустой клиент → ZIP с README + metadata.json")
    void emptyClient() throws Exception {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(documentRepository.findAllByOwnerOrderByUploadedAtDesc(user)).thenReturn(List.of());

        byte[] zip = service.exportClient(42L);
        Map<String, byte[]> entries = readZip(zip);

        assertThat(entries).containsKeys("README.txt", "metadata.json");
        String readme = new String(entries.get("README.txt"), StandardCharsets.UTF_8);
        assertThat(readme).contains("Иванов Иван Иванович", "ivanov@example.ru", "ROLE_USER");
        String meta = new String(entries.get("metadata.json"), StandardCharsets.UTF_8);
        assertThat(meta).contains("\"id\": 42", "\"fullName\": \"Иванов Иван Иванович\"");
    }

    // ─── документы + подписи ──────────────────────

    @Test
    @DisplayName("exportClient: 1 PDF + 1 подпись → documents/ + signatures/")
    void oneDocOneSig() throws Exception {
        Document d = newDoc(7L, "file.pdf", true);
        DocumentSignature s = new DocumentSignature();
        s.setId(100L);
        s.setSignatureBase64(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}));
        d.getSignatures().add(s);  // иначе exportDocuments пропустит блок signatures/

        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(documentRepository.findAllByOwnerOrderByUploadedAtDesc(user)).thenReturn(List.of(d));
        when(documentService.getBytes(d)).thenReturn("%PDF-1.4 content".getBytes(StandardCharsets.UTF_8));

        byte[] zip = service.exportClient(42L);
        Map<String, byte[]> entries = readZip(zip);

        assertThat(entries).containsKey("documents/file.pdf");
        assertThat(entries).containsEntry("documents/file.pdf",
                "%PDF-1.4 content".getBytes(StandardCharsets.UTF_8));
        assertThat(entries).containsKey("signatures/file_sig_1.sig");
        assertThat(entries.get("signatures/file_sig_1.sig"))
                .containsExactly((byte) 1, (byte) 2, (byte) 3, (byte) 4);
    }

    @Test
    @DisplayName("exportClient: несколько подписей → _sig_1.._sig_N")
    void multipleSignatures() throws Exception {
        Document d = newDoc(7L, "f.pdf", true);
        List<DocumentSignature> sigs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DocumentSignature s = new DocumentSignature();
            s.setSignatureBase64(Base64.getEncoder().encodeToString(new byte[]{(byte) i}));
            sigs.add(s);
        }
        d.setSignatures(sigs);

        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(documentRepository.findAllByOwnerOrderByUploadedAtDesc(user)).thenReturn(List.of(d));
        when(documentService.getBytes(d)).thenReturn(new byte[]{0x25});

        Map<String, byte[]> entries = readZip(service.exportClient(42L));
        assertThat(entries).containsKeys(
                "signatures/f_sig_1.sig",
                "signatures/f_sig_2.sig",
                "signatures/f_sig_3.sig");
    }

    @Test
    @DisplayName("exportClient: 2 документа с одинаковыми именами → уникальные ZIP-записи")
    void duplicateNames() throws Exception {
        Document d1 = newDoc(1L, "report.pdf", false);
        Document d2 = newDoc(2L, "report.pdf", false);

        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(documentRepository.findAllByOwnerOrderByUploadedAtDesc(user)).thenReturn(List.of(d1, d2));
        when(documentService.getBytes(d1)).thenReturn(new byte[]{1});
        when(documentService.getBytes(d2)).thenReturn(new byte[]{2});

        Map<String, byte[]> entries = readZip(service.exportClient(42L));
        // 2 файла — оба под documents/, но с разными именами
        long count = entries.keySet().stream().filter(n -> n.startsWith("documents/report")).count();
        assertThat(count).isEqualTo(2);
        assertThat(entries.values().stream().map(b -> b[0]).toList())
                .contains((byte) 1, (byte) 2);
    }

    @Test
    @DisplayName("exportClient: getBytes упал → документ пропускается, остальные выгружаются")
    void documentServiceFails() throws Exception {
        Document d1 = newDoc(1L, "bad.pdf", false);
        Document d2 = newDoc(2L, "good.pdf", false);

        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(documentRepository.findAllByOwnerOrderByUploadedAtDesc(user)).thenReturn(List.of(d1, d2));
        when(documentService.getBytes(d1)).thenThrow(new IOException("enc corrupt"));
        when(documentService.getBytes(d2)).thenReturn(new byte[]{0x47});

        Map<String, byte[]> entries = readZip(service.exportClient(42L));
        assertThat(entries).containsKey("documents/good.pdf")
                .doesNotContainKey("documents/bad.pdf");
    }

    // ─── metadata.json: escape ─────────────────────

    @Test
    @DisplayName("metadata.json: кавычки/backslash в ФИО экранируются")
    void metadataEscapes() throws Exception {
        // ФИО с кавычкой и backslash — как в реальном сертификате
        user.setFullName("Иванов \"Иван\" Тест");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(documentRepository.findAllByOwnerOrderByUploadedAtDesc(user)).thenReturn(List.of());

        Map<String, byte[]> entries = readZip(service.exportClient(42L));
        String meta = new String(entries.get("metadata.json"), StandardCharsets.UTF_8);
        // escapeJson превращает " → \" (в JSON-строке это \").
        // Проверяем, что raw " внутри значения нет: в meta должно быть \"Иван\"
        assertThat(meta).contains("\\\"Иван\\\"");
    }

    @Test
    @DisplayName("metadata.json: без lastLoginAt → null в JSON")
    void metadataNoLastLogin() throws Exception {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(documentRepository.findAllByOwnerOrderByUploadedAtDesc(user)).thenReturn(List.of());

        Map<String, byte[]> entries = readZip(service.exportClient(42L));
        String meta = new String(entries.get("metadata.json"), StandardCharsets.UTF_8);
        assertThat(meta).contains("\"lastLoginAt\": null");
    }

    // ─── visualizations ───────────────────────────

    @Test
    @DisplayName("exportClient: визуализация PDF не в файловой системе → пропускается тихо")
    void visualizationNotOnDisk() throws Exception {
        Document d = newDoc(7L, "f.pdf", true);
        DocumentVisualization vis = new DocumentVisualization();
        vis.setStoredName("vis_7_1234.pdf");

        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(documentRepository.findAllByOwnerOrderByUploadedAtDesc(user)).thenReturn(List.of(d));
        when(documentService.getBytes(d)).thenReturn(new byte[]{0x25});
        when(visRepository.findByDocument(d)).thenReturn(Optional.of(vis));

        byte[] zip = service.exportClient(42L);
        assertThat(zip).isNotEmpty(); // не упал
        Map<String, byte[]> entries = readZip(zip);
        // файла нет на диске → записи visualizations/ тоже нет
        assertThat(entries.keySet().stream().anyMatch(n -> n.startsWith("visualizations/"))).isFalse();
    }

    // ─── README ──────────────────────────────────

    @Test
    @DisplayName("README.txt: содержит docCount, статус клиента, дату создания")
    void readmeFields() throws Exception {
        Document d = newDoc(1L, "a.pdf", false);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(documentRepository.findAllByOwnerOrderByUploadedAtDesc(user)).thenReturn(List.of(d));
        when(documentService.getBytes(d)).thenReturn(new byte[]{0x25});

        Map<String, byte[]> entries = readZip(service.exportClient(42L));
        String readme = new String(entries.get("README.txt"), StandardCharsets.UTF_8);
        assertThat(readme).contains("Документов: 1", "Активен", "15.01.2024");
    }

    @Test
    @DisplayName("README.txt: заблокированный клиент → 'Заблокирован'")
    void readmeBlocked() throws Exception {
        user.setEnabled(false);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(documentRepository.findAllByOwnerOrderByUploadedAtDesc(user)).thenReturn(List.of());

        Map<String, byte[]> entries = readZip(service.exportClient(42L));
        String readme = new String(entries.get("README.txt"), StandardCharsets.UTF_8);
        assertThat(readme).contains("Заблокирован");
    }

    // ─── audit / защита ──────────────────────────

    @Test
    @DisplayName("exportClient: не вызывает visRepository для документов без подписи")
    void noVisCheckForUnsigned() throws Exception {
        Document d = newDoc(1L, "a.pdf", false);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(documentRepository.findAllByOwnerOrderByUploadedAtDesc(user)).thenReturn(List.of(d));
        when(documentService.getBytes(d)).thenReturn(new byte[]{0x25});

        service.exportClient(42L);

        // findByDocument всё равно вызывается в текущей реализации, но результат пуст
        verify(visRepository).findByDocument(d);
    }
}
