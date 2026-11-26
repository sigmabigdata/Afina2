package ru.afina.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.PowerOfAttorney;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.repository.DocumentTemplateRepository;
import ru.afina.repository.PowerOfAttorneyRepository;
import ru.afina.security.PiiEncryptor;
import ru.afina.service.DocumentService;
import ru.afina.service.FolderService;
import ru.afina.service.SettingsService;
import ru.afina.service.UserService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Полный прогон наполнения демо-тенанта на моках: пользователи, разделы,
 * документы, подпись, МЧД, шаблон и служебные файлы.
 */
@DisplayName("DemoDataInitializer: полный сид демо-тенанта")
class DemoSeedFlowTest {

    @TempDir Path tempDir;

    private final UserService userService = mock(UserService.class);
    private final FolderService folderService = mock(FolderService.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final PiiEncryptor pii = mock(PiiEncryptor.class);
    private final PowerOfAttorneyRepository poaRepository = mock(PowerOfAttorneyRepository.class);
    private final DocumentTemplateRepository templateRepository = mock(DocumentTemplateRepository.class);

    private DemoDataInitializer initializer;

    @BeforeEach
    void setUp() throws Exception {
        initializer = new DemoDataInitializer(userService, folderService, documentService,
                settings, pii, poaRepository, templateRepository);
        Path keys = tempDir.resolve("keys");
        Files.createDirectories(keys);
        Files.writeString(keys.resolve("pii.key.v1"), "key");
        Files.writeString(keys.resolve("file.key.v1"), "key");
        ReflectionTestUtils.setField(initializer, "certsPath", tempDir.resolve("certs").toString());
        ReflectionTestUtils.setField(initializer, "fileKeyPath", keys.resolve("file.key").toString());
        ReflectionTestUtils.setField(initializer, "piiKeyPath", keys.resolve("pii.key").toString());
        ReflectionTestUtils.setField(initializer, "adminsFile", tempDir.resolve("admins.env").toString());

        User admin = new User();
        admin.setId(1L);
        admin.setEmail("demo-admin@afina.local");
        admin.setRole(Role.ROLE_ADMIN);
        User client = new User();
        client.setId(2L);
        client.setEmail("demo-client@afina.local");
        client.setRole(Role.ROLE_USER);

        when(userService.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userService.adminCreate(anyString(), anyString(), any(), any(), any(), eq(true)))
                .thenReturn(admin, client);
        DocumentFolder folder = new DocumentFolder();
        folder.setId(1L);
        folder.setOwner(client);
        when(folderService.create(any(), anyString())).thenReturn(folder);
        Document doc = new Document();
        doc.setId(10L);
        when(documentService.createDocument(any(), anyString(), anyString(), any(), any()))
                .thenReturn(doc);
        when(poaRepository.findFirstByFioHashAndActiveTrueAndValidUntilGreaterThanEqualOrderByValidUntilDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(templateRepository.findAll()).thenReturn(List.of());
        when(pii.hash(anyString())).thenReturn("hash");
    }

    @Test
    @DisplayName("создаются: 2 пользователя, 2 раздела, 2 документа, подпись, МЧД, шаблон, файлы")
    void seedsEverything() throws Exception {
        initializer.run(null);          // служебные файлы (admins.env, legacy-ключи)
        initializer.seedDemoData();     // данные тенанта

        verify(userService, atLeastOnce()).adminCreate(anyString(), anyString(), any(), any(), any(), eq(true));
        verify(folderService, atLeastOnce()).create(any(), anyString());
        verify(documentService, atLeastOnce()).createDocument(any(), anyString(), eq("application/pdf"), any(), any());
        verify(documentService).addSignature(anyLong(), any(), anyString(), anyString(), anyString());
        verify(poaRepository).save(any(PowerOfAttorney.class));
        // два шаблона: обычный документ + согласие на обработку ПД
        verify(templateRepository, org.mockito.Mockito.atLeast(2)).save(any());
        verify(settings, atLeastOnce()).set(anyString(), anyString(), eq("demo"));

        assertThat(Files.exists(tempDir.resolve("admins.env"))).isTrue();
        assertThat(Files.readString(tempDir.resolve("admins.env"))).contains("Демо Администратор");
        assertThat(Files.exists(tempDir.resolve("keys/pii.key"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("keys/file.key"))).isTrue();
        assertThat(Files.list(tempDir.resolve("certs")).count()).isEqualTo(1);
    }
}
