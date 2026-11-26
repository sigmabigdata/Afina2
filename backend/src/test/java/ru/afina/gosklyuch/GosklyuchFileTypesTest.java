package ru.afina.gosklyuch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.afina.entity.Document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Госключ: допустимые форматы документов и имена в архиве")
class GosklyuchFileTypesTest {

    @Test
    @DisplayName("MIME-тип берётся из content-type документа")
    void mapsContentType() {
        assertThat(GosklyuchFileTypes.mimeType(doc(1L, "scan.pdf", "application/pdf")))
                .isEqualTo("application/pdf");
        assertThat(GosklyuchFileTypes.mimeType(doc(1L, "scan.tif", "image/tiff")))
                .isEqualTo("image/tiff");
        assertThat(GosklyuchFileTypes.mimeType(doc(1L, "data.xml", "text/xml")))
                .isEqualTo("application/xml");
        assertThat(GosklyuchFileTypes.mimeType(doc(1L, "note.txt", "text/plain")))
                .isEqualTo("text/plain");
    }

    @Test
    @DisplayName("При неизвестном content-type формат определяется по расширению")
    void fallsBackToExtension() {
        assertThat(GosklyuchFileTypes.mimeType(doc(7L, "scan.PDF", null)))
                .isEqualTo("application/pdf");
        assertThat(GosklyuchFileTypes.mimeType(doc(7L, "scan.tiff", "application/octet-stream")))
                .isEqualTo("image/tiff");
    }

    @Test
    @DisplayName("Неподдерживаемый формат отклоняется с понятным сообщением")
    void rejectsUnsupported() {
        assertThatThrownBy(() -> GosklyuchFileTypes.mimeType(doc(1L, "photo.jpg", "image/jpeg")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не поддерживается");
        assertThatThrownBy(() -> GosklyuchFileTypes.archiveFileName(
                doc(1L, "photo.jpg", "image/jpeg")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Имя файла в архиве: latin + id документа + расширение")
    void buildsArchiveName() {
        assertThat(GosklyuchFileTypes.archiveFileName(doc(42L, "Согласие.pdf", "application/pdf")))
                .isEqualTo("document_42.pdf");
        assertThat(GosklyuchFileTypes.archiveFileName(doc(42L, "scan.TIFF", "image/tiff")))
                .isEqualTo("document_42.tiff");
    }

    private static Document doc(Long id, String name, String contentType) {
        Document doc = new Document();
        doc.setId(id);
        doc.setOriginalName(name);
        doc.setContentType(contentType);
        return doc;
    }
}
