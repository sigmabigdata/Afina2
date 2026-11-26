package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.repository.DocumentFolderRepository;
import ru.afina.repository.DocumentRepository;
import ru.afina.security.PiiEncryptor;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FolderService: разделы (чтение, создание, rename, delete)")
class FolderServiceTest {

    @Mock private DocumentFolderRepository folderRepo;
    @Mock private DocumentRepository documentRepo;
    @Mock private DocumentService documentService;
    @Mock private PiiEncryptor pii;

    @InjectMocks private FolderService service;

    private User owner;
    private User admin;
    private DocumentFolder folder;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(1L);
        owner.setEmail("u@b.ru");
        owner.setRole(Role.ROLE_USER);

        admin = new User();
        admin.setId(99L);
        admin.setRole(Role.ROLE_ADMIN);

        folder = new DocumentFolder();
        folder.setId(5L);
        folder.setOwner(owner);
        folder.setName("Раздел");
        folder.setNameHash("h");
    }

    // ─── чтение ─────────────────────────────

    @Test
    @DisplayName("listForOwner: возвращает все разделы клиента")
    void listForOwner() {
        when(folderRepo.findAllByOwnerOrderByCreatedAtDesc(owner))
                .thenReturn(List.of(folder));
        assertThat(service.listForOwner(owner)).hasSize(1);
    }

    @Test
    @DisplayName("getOwned: не найден → IAE")
    void getOwnedNotFound() {
        when(folderRepo.findByIdAndOwner(5L, owner)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOwned(5L, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    @DisplayName("getOwned: найден → возвращает")
    void getOwnedOk() {
        when(folderRepo.findByIdAndOwner(5L, owner)).thenReturn(Optional.of(folder));
        assertThat(service.getOwned(5L, owner)).isSameAs(folder);
    }

    @Test
    @DisplayName("getById (admin): не найден → IAE")
    void getByIdNotFound() {
        when(folderRepo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getById: найден → возвращает")
    void getByIdOk() {
        when(folderRepo.findById(5L)).thenReturn(Optional.of(folder));
        assertThat(service.getById(5L)).isSameAs(folder);
    }

    @Test
    @DisplayName("countSignedInFolder: делегирует, проверяя owner")
    void countSigned() {
        when(folderRepo.findByIdAndOwner(5L, owner)).thenReturn(Optional.of(folder));
        when(documentRepo.countByFolderAndSignedTrue(folder)).thenReturn(7L);
        assertThat(service.countSignedInFolder(5L, owner)).isEqualTo(7L);
    }

    // ─── create ────────────────────────────

    @Test
    @DisplayName("create: пустое имя → IAE")
    void createBlank() {
        assertThatThrownBy(() -> service.create(owner, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(owner, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create: дубликат имени → IAE")
    void createDuplicate() {
        when(pii.hash("Раздел")).thenReturn("h");
        when(folderRepo.existsByOwnerAndNameHash(owner, "h")).thenReturn(true);

        assertThatThrownBy(() -> service.create(owner, "Раздел"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже существует");
    }

    @Test
    @DisplayName("create: ok → save, name + nameHash")
    void createOk() {
        when(pii.hash("Новый")).thenReturn("hn");
        when(folderRepo.existsByOwnerAndNameHash(owner, "hn")).thenReturn(false);
        when(folderRepo.save(any(DocumentFolder.class))).thenAnswer(i -> i.getArgument(0));

        var f = service.create(owner, "  Новый  ");
        assertThat(f.getName()).isEqualTo("Новый");
        assertThat(f.getNameHash()).isEqualTo("hn");
        assertThat(f.getOwner()).isSameAs(owner);
    }

    // ─── rename ────────────────────────────

    @Test
    @DisplayName("rename: пустое новое имя → IAE")
    void renameBlank() {
        when(folderRepo.findByIdAndOwner(5L, owner)).thenReturn(Optional.of(folder));
        assertThatThrownBy(() -> service.rename(5L, owner, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rename: дубликат (другой раздел с тем же именем) → IAE")
    void renameDuplicate() {
        when(folderRepo.findByIdAndOwner(5L, owner)).thenReturn(Optional.of(folder));
        when(pii.hash("Другой")).thenReturn("h2");
        when(folderRepo.existsByOwnerAndNameHash(owner, "h2")).thenReturn(true);

        assertThatThrownBy(() -> service.rename(5L, owner, "Другой"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже существует");
    }

    @Test
    @DisplayName("rename: новое имя = текущее → сохраняем без проверки дубликата")
    void renameSameName() {
        when(folderRepo.findByIdAndOwner(5L, owner)).thenReturn(Optional.of(folder));
        when(pii.hash("Раздел")).thenReturn("h"); // тот же hash

        service.rename(5L, owner, "Раздел");

        verify(folderRepo).save(folder);
        verify(folderRepo, never()).existsByOwnerAndNameHash(any(), anyString());
    }

    @Test
    @DisplayName("rename: ok → обновляет name и nameHash")
    void renameOk() {
        when(folderRepo.findByIdAndOwner(5L, owner)).thenReturn(Optional.of(folder));
        when(pii.hash("Новое")).thenReturn("hn");
        when(folderRepo.existsByOwnerAndNameHash(owner, "hn")).thenReturn(false);

        service.rename(5L, owner, "Новое");
        assertThat(folder.getName()).isEqualTo("Новое");
        assertThat(folder.getNameHash()).isEqualTo("hn");
    }

    // ─── delete ────────────────────────────

    @Test
    @DisplayName("delete: раздел не найден → IAE")
    void deleteNotFound() {
        when(folderRepo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(99L, owner))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("delete: чужой раздел, не админ → IAE 'Нет доступа'")
    void deleteNoAccess() {
        User other = new User(); other.setId(2L); other.setRole(Role.ROLE_USER);
        when(folderRepo.findById(5L)).thenReturn(Optional.of(folder));

        assertThatThrownBy(() -> service.delete(5L, other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Нет доступа");
    }

    @Test
    @DisplayName("delete: есть подписанные, не админ → IAE")
    void deleteSignedNotAdmin() {
        when(folderRepo.findById(5L)).thenReturn(Optional.of(folder));
        when(documentRepo.countByFolderAndSignedTrue(folder)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(5L, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("подписанных");
    }

    @Test
    @DisplayName("delete: owner, подписанных нет → удаляет документы + folder")
    void deleteOwnerOk() throws Exception {
        var d1 = new Document(); d1.setId(10L);
        var d2 = new Document(); d2.setId(11L);
        when(folderRepo.findById(5L)).thenReturn(Optional.of(folder));
        when(documentRepo.countByFolderAndSignedTrue(folder)).thenReturn(0L);
        when(documentRepo.findAllByFolderOrderByUploadedAtDesc(folder))
                .thenReturn(List.of(d1, d2));

        service.delete(5L, owner);

        verify(documentService).delete(eq(10L), eq(owner));
        verify(documentService).delete(eq(11L), eq(owner));
        verify(folderRepo).delete(folder);
    }

    @Test
    @DisplayName("delete: админ + подписанные → deleteAsAdmin для каждого")
    void deleteAdminSignedOk() throws Exception {
        var d = new Document(); d.setId(10L);
        when(folderRepo.findById(5L)).thenReturn(Optional.of(folder));
        when(documentRepo.countByFolderAndSignedTrue(folder)).thenReturn(3L);
        when(documentRepo.findAllByFolderOrderByUploadedAtDesc(folder))
                .thenReturn(List.of(d));

        service.delete(5L, admin);

        verify(documentService).deleteAsAdmin(10L);
        verify(folderRepo).delete(folder);
    }

    @Test
    @DisplayName("delete: documentService.delete упал IOException → IllegalState")
    void deleteIOException() throws Exception {
        var d = new Document(); d.setId(10L);
        when(folderRepo.findById(5L)).thenReturn(Optional.of(folder));
        when(documentRepo.countByFolderAndSignedTrue(folder)).thenReturn(0L);
        when(documentRepo.findAllByFolderOrderByUploadedAtDesc(folder)).thenReturn(List.of(d));
        org.mockito.Mockito.doThrow(new IOException("disk full"))
                .when(documentService).delete(eq(10L), eq(owner));

        assertThatThrownBy(() -> service.delete(5L, owner))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disk full");
    }

    // ─── ensureDefault / migrateUnfiled ──────

    @Test
    @DisplayName("ensureDefault: раздел уже есть → возвращает его")
    void ensureDefaultExisting() {
        when(pii.hash("Без раздела")).thenReturn("hd");
        when(folderRepo.findByOwnerAndNameHash(owner, "hd")).thenReturn(Optional.of(folder));
        assertThat(service.ensureDefault(owner)).isSameAs(folder);
    }

    @Test
    @DisplayName("ensureDefault: нет → создаёт и сохраняет")
    void ensureDefaultCreates() {
        when(pii.hash("Без раздела")).thenReturn("hd");
        when(folderRepo.findByOwnerAndNameHash(owner, "hd")).thenReturn(Optional.empty());
        when(folderRepo.save(any(DocumentFolder.class))).thenAnswer(i -> i.getArgument(0));

        var f = service.ensureDefault(owner);
        assertThat(f.getName()).isEqualTo("Без раздела");
        assertThat(f.getNameHash()).isEqualTo("hd");
    }

    @Test
    @DisplayName("migrateUnfiled: пусто → 0, saveAll НЕ вызывается")
    void migrateEmpty() {
        when(documentRepo.findUnfiledByOwner(owner)).thenReturn(List.of());
        assertThat(service.migrateUnfiled(owner)).isZero();
        verify(documentRepo, never()).saveAll(any());
    }

    @Test
    @DisplayName("migrateUnfiled: 2 документа → оба привязаны к default, n=2")
    void migrateTwo() {
        var d1 = new Document(); d1.setId(1L);
        var d2 = new Document(); d2.setId(2L);
        when(documentRepo.findUnfiledByOwner(owner)).thenReturn(List.of(d1, d2));
        when(pii.hash("Без раздела")).thenReturn("hd");
        when(folderRepo.findByOwnerAndNameHash(owner, "hd")).thenReturn(Optional.of(folder));

        assertThat(service.migrateUnfiled(owner)).isEqualTo(2);
        assertThat(d1.getFolder()).isSameAs(folder);
        assertThat(d2.getFolder()).isSameAs(folder);
        verify(documentRepo).saveAll(List.of(d1, d2));
    }
}
