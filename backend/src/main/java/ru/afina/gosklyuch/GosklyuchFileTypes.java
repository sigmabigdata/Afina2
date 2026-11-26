package ru.afina.gosklyuch;

import ru.afina.entity.Document;

import java.util.Locale;
import java.util.Set;

/**
 * Соответствие типа документа форматам, которые принимает Госключ
 * (только pdf, tif, tiff, xml, txt), и формирование имени файла архива.
 *
 * <p>Имя файла в архиве — латиница, цифры и подчёркивание, не длиннее
 * 50 символов; оно же записывается в {@code DocumentId} файла {@code req.xml}.</p>
 */
public final class GosklyuchFileTypes {

    static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "tif", "tiff", "xml", "txt");

    private GosklyuchFileTypes() {
    }

    /**
     * MIME-тип, допустимый для Госключа.
     *
     * @throws IllegalArgumentException если формат документа не поддерживается
     */
    public static String mimeType(Document doc) {
        String contentType = normalize(doc.getContentType());
        if ("application/pdf".equals(contentType)) return "application/pdf";
        if ("image/tiff".equals(contentType) || "image/tif".equals(contentType)) {
            return "image/tiff";
        }
        if ("text/plain".equals(contentType)) return "text/plain";
        if ("application/xml".equals(contentType) || "text/xml".equals(contentType)) {
            return "application/xml";
        }
        String ext = extensionOf(doc.getOriginalName());
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "tif", "tiff" -> "image/tiff";
            case "xml" -> "application/xml";
            case "txt" -> "text/plain";
            default -> throw new IllegalArgumentException(
                    "Формат документа «" + doc.getOriginalName()
                            + "» не поддерживается Госключом "
                            + "(допустимы PDF, TIF/TIFF, XML, TXT)");
        };
    }

    /**
     * Имя файла документа в архиве заявки и в {@code DocumentId}.
     * Например: {@code document_42.pdf}.
     */
    public static String archiveFileName(Document doc) {
        String ext = extensionOf(doc.getOriginalName());
        if (!GosklyuchPackageBuilder.ALLOWED_EXTENSIONS.contains(ext)) {
            // Тип мог быть задан только content-type (например, для файла без расширения)
            ext = extensionOfMime(mimeType(doc));
        }
        return "document_" + doc.getId() + "." + ext;
    }

    static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String extensionOfMime(String mime) {
        return switch (mime) {
            case "application/pdf" -> "pdf";
            case "image/tiff" -> "tif";
            case "application/xml" -> "xml";
            case "text/plain" -> "txt";
            default -> throw new IllegalArgumentException(
                    "Не удалось определить расширение файла для " + mime);
        };
    }

    private static String normalize(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }
}
