package ru.afina.gosklyuch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Собирает ZIP-архив заявки для {@code POST /api/gusmev/push}:
 * файл заявления {@code req.xml} и документы, каждый — с отсоединённой
 * подписью УКЭП организации рядом ({@code <имя>.sig}).
 *
 * <p>Ограничения спецификации Госключа (раздел 1.1) проверяются здесь:</p>
 * <ul>
 *   <li>только {@code .pdf}, {@code .tif}, {@code .tiff}, {@code .xml}, {@code .txt};</li>
 *   <li>не более 15 документов в заявке (без учёта {@code req.xml});</li>
 *   <li>общий объём не более 100 МБ;</li>
 *   <li>в имени файла — латиница, цифры, подчёркивание, пробел, точка;
 *       длина вместе с расширением не более 50 символов;</li>
 *   <li>никаких вложенных папок и архивов.</li>
 * </ul>
 */
public class GosklyuchPackageBuilder {

    /** Имя файла заявления — по спецификации только {@code req.xml}. */
    public static final String REQUEST_FILE = "req.xml";

    static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "tif", "tiff", "xml", "txt");

    static final int MAX_FILE_NAME = 50;

    private static final String NAME_PATTERN = "[A-Za-z0-9_ .-]+";

    private final GosklyuchSigner signer;
    private final int maxDocuments;
    private final long maxBytes;

    public GosklyuchPackageBuilder(GosklyuchSigner signer, int maxDocuments, long maxBytes) {
        this.signer = signer;
        this.maxDocuments = Math.min(maxDocuments, GosklyuchRequestXmlBuilder.MAX_DOCUMENTS);
        this.maxBytes = maxBytes;
    }

    public static GosklyuchPackageBuilder from(GosklyuchSigner signer, GosklyuchProperties props) {
        return new GosklyuchPackageBuilder(signer, props.getMaxDocuments(),
                props.getMaxPackageBytes());
    }

    /**
     * Собрать архив заявки.
     *
     * @param requestXml содержимое {@code req.xml}
     * @param documents  документы: имя файла в архиве → содержимое
     *                   (порядок сохраняется)
     * @return ZIP-архив
     */
    public byte[] build(byte[] requestXml, Map<String, byte[]> documents) {
        if (requestXml == null || requestXml.length == 0) {
            throw new IllegalArgumentException("Файл заявления req.xml пуст");
        }
        Map<String, byte[]> files = documents == null ? Map.of() : documents;
        validate(files);

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                putSigned(zip, REQUEST_FILE, requestXml);
                for (Map.Entry<String, byte[]> e : new LinkedHashMap<>(files).entrySet()) {
                    putSigned(zip, e.getKey(), e.getValue());
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось собрать архив заявки: " + e.getMessage(), e);
        }
    }

    private void putSigned(ZipOutputStream zip, String name, byte[] content) throws IOException {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Файл " + name + " пуст");
        }
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();

        byte[] sig = signer.signDetached(name, content);
        if (sig == null || sig.length == 0) {
            throw new GosklyuchUnavailableException(
                    "Пустая подпись для файла " + name);
        }
        zip.putNextEntry(new ZipEntry(name + ".sig"));
        zip.write(sig);
        zip.closeEntry();
    }

    private void validate(Map<String, byte[]> files) {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("В заявке нет документов");
        }
        if (files.size() > maxDocuments) {
            throw new IllegalArgumentException(
                    "В одной заявке допускается не более " + maxDocuments + " документов");
        }
        long total = 0;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            validateName(e.getKey());
            if (e.getValue() == null || e.getValue().length == 0) {
                throw new IllegalArgumentException("Документ " + e.getKey() + " пуст");
            }
            total += e.getValue().length;
        }
        if (total > maxBytes) {
            throw new IllegalArgumentException(
                    "Общий объём документов превышает "
                            + (maxBytes / (1024 * 1024)) + " МБ");
        }
    }

    static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Не задано имя файла документа");
        }
        if (name.length() > MAX_FILE_NAME) {
            throw new IllegalArgumentException(
                    "Имя файла длиннее " + MAX_FILE_NAME + " символов: " + name);
        }
        if (!name.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException(
                    "Недопустимые символы в имени файла (латиница, цифры, "
                            + "подчёркивание, пробел, точка): " + name);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 1 || dot == name.length() - 1) {
            throw new IllegalArgumentException("У файла должно быть расширение: " + name);
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                    "Недопустимый формат файла " + name
                            + " (допустимы: pdf, tif, tiff, xml, txt)");
        }
    }

    /** Проверить список имён (для диагностики до сборки архива). */
    public static void validateNames(List<String> names) {
        for (String n : names) {
            validateName(n);
        }
    }
}
