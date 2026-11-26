package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.DocumentSignature;
import ru.afina.entity.User;
import ru.afina.repository.DocumentRepository;
import ru.afina.repository.DocumentSignatureRepository;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentService: hasSignatureByUser, getSignature, deleteSignature (throws)")
class DocumentServiceFullTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentSignatureRepository signatureRepository;
    @Mock private FileEncryptor encryptor;

    @TempDir
    Path tmpDir;

    private DocumentService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DocumentService(documentRepository, signatureRepository,
                encryptor, tmpDir.toString());
    }

    @Test
    @DisplayName("hasSignatureByUser: null documentId → false без вызова репо")
    void hasSignatureNullDoc() {
        assertThat(service.hasSignatureByUser(null, 1L)).isFalse();
        verify(signatureRepository, never()).existsByDocumentIdAndSignerUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("hasSignatureByUser: null userId → false")
    void hasSignatureNullUser() {
        assertThat(service.hasSignatureByUser(1L, null)).isFalse();
    }

    @Test
    @DisplayName("hasSignatureByUser: делегирует в репо")
    void hasSignatureDelegates() {
        when(signatureRepository.existsByDocumentIdAndSignerUserId(10L, 5L)).thenReturn(true);
        assertThat(service.hasSignatureByUser(10L, 5L)).isTrue();
    }

    @Test
    @DisplayName("getSignature: не найдено → IAE")
    void getSignatureNotFound() {
        when(signatureRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSignature(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найдена");
    }

    @Test
    @DisplayName("getSignature: найдено → возвращает сущность")
    void getSignatureFound() {
        DocumentSignature s = new DocumentSignature();
        s.setId(7L);
        when(signatureRepository.findById(7L)).thenReturn(Optional.of(s));
        assertThat(service.getSignature(7L).getId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("deleteSignature: ВСЕГДА бросает IAE (юридически значимая)")
    void deleteSignatureAlwaysThrows() {
        assertThatThrownBy(() -> service.deleteSignature(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Удаление подписей невозможно");
        verify(signatureRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("listSignatures: возвращает все подписи документа")
    void listSignatures() {
        var sigs = java.util.List.of(new DocumentSignature(), new DocumentSignature());
        when(signatureRepository.findAllByDocumentIdOrderBySignedAtAsc(5L)).thenReturn(sigs);
        assertThat(service.listSignatures(5L)).hasSize(2);
    }

    @Test
    @DisplayName("createDocument: folder null → IAE")
    void createDocumentNullFolder() {
        assertThatThrownBy(() -> service.createDocument(
                new byte[]{1}, "f.pdf", "application/pdf", new User(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Не выбран раздел");
    }

    @Test
    @DisplayName("createDocument: folder чужой → IAE")
    void createDocumentForeignFolder() {
        User owner = new User(); owner.setId(1L);
        User other = new User(); other.setId(2L);
        DocumentFolder folder = new DocumentFolder();
        folder.setOwner(other);

        assertThatThrownBy(() -> service.createDocument(
                new byte[]{1}, "f.pdf", "application/pdf", owner, folder))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("принадлежит другому");
    }

    @Test
    @DisplayName("createDocument: пустой массив → IAE")
    void createDocumentEmptyBytes() {
        User owner = new User(); owner.setId(1L);
        DocumentFolder folder = new DocumentFolder(); folder.setOwner(owner);

        assertThatThrownBy(() -> service.createDocument(
                new byte[0], "f.pdf", "application/pdf", owner, folder))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустой");
    }
}
