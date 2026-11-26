package ru.afina.gosklyuch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Госключ: сборка ZIP-архива заявки")
class GosklyuchPackageBuilderTest {

    private static final byte[] REQ_XML = "<ns:SignRequest/>".getBytes(StandardCharsets.UTF_8);

    private final List<String> signedFiles = new ArrayList<>();
    private final GosklyuchSigner signer = (name, content) -> {
        signedFiles.add(name);
        return ("SIG(" + name + ")").getBytes(StandardCharsets.UTF_8);
    };

    @Test
    @DisplayName("Архив содержит req.xml и документы, каждый — с .sig рядом")
    void buildsSignedArchive() throws Exception {
        GosklyuchPackageBuilder builder = new GosklyuchPackageBuilder(signer, 15, 10_000_000);
        Map<String, byte[]> docs = new LinkedHashMap<>();
        docs.put("document_1.pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        docs.put("document_2.txt", "text".getBytes(StandardCharsets.UTF_8));

        byte[] zip = builder.build(REQ_XML, docs);

        assertThat(entries(zip)).containsExactly(
                "req.xml", "req.xml.sig",
                "document_1.pdf", "document_1.pdf.sig",
                "document_2.txt", "document_2.txt.sig");
        assertThat(signedFiles).containsExactly(
                "req.xml", "document_1.pdf", "document_2.txt");
        assertThat(readEntry(zip, "document_1.pdf.sig")).isEqualTo("SIG(document_1.pdf)");
        assertThat(readEntry(zip, "req.xml.sig")).isEqualTo("SIG(req.xml)");
    }

    @Test
    @DisplayName("Пустой архив и пустые файлы отклоняются")
    void rejectsEmptyContent() {
        GosklyuchPackageBuilder builder = new GosklyuchPackageBuilder(signer, 15, 1000);
        assertThatThrownBy(() -> builder.build(new byte[0], Map.of("document_1.pdf", new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("req.xml");
        assertThatThrownBy(() -> builder.build(REQ_XML, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("нет документов");
        assertThatThrownBy(() -> builder.build(REQ_XML, Map.of("document_1.pdf", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пуст");
    }

    @Test
    @DisplayName("Недопустимые имена и форматы файлов отклоняются")
    void rejectsBadFileNames() {
        GosklyuchPackageBuilder builder = new GosklyuchPackageBuilder(signer, 15, 1000);
        for (String bad : List.of("документ.pdf", "doc.docx", "document", "a".repeat(51) + ".pdf")) {
            assertThatThrownBy(() -> builder.build(REQ_XML, Map.of(bad, new byte[]{1})))
                    .as("файл %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Ограничения: не более 15 документов и объём не больше лимита")
    void enforcesLimits() {
        GosklyuchPackageBuilder small = new GosklyuchPackageBuilder(signer, 2, 10);
        Map<String, byte[]> docs = new LinkedHashMap<>();
        docs.put("document_1.pdf", new byte[4]);
        docs.put("document_2.pdf", new byte[4]);
        docs.put("document_3.pdf", new byte[4]);
        assertThatThrownBy(() -> small.build(REQ_XML, docs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2");

        GosklyuchPackageBuilder sizeLimited = new GosklyuchPackageBuilder(signer, 15, 4);
        Map<String, byte[]> big = new LinkedHashMap<>();
        big.put("document_1.pdf", new byte[10]);
        assertThatThrownBy(() -> sizeLimited.build(REQ_XML, big))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("МБ");
    }

    @Test
    @DisplayName("Ошибка подписанта не даёт собрать архив")
    void propagatesSignerFailure() {
        GosklyuchSigner failing = (name, content) -> {
            throw new GosklyuchUnavailableException("нет ключа");
        };
        GosklyuchPackageBuilder builder = new GosklyuchPackageBuilder(failing, 15, 1000);
        assertThatThrownBy(() -> builder.build(REQ_XML, Map.of("document_1.pdf", new byte[]{1})))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("нет ключа");
    }

    @Test
    @DisplayName("Имя файла с цифрами, пробелом и подчёркиванием допустимо")
    void acceptsAllowedCharacters() throws Exception {
        GosklyuchPackageBuilder builder = new GosklyuchPackageBuilder(signer, 15, 1000);
        byte[] zip = builder.build(REQ_XML, Map.of("document 1_2.pdf", new byte[]{1}));
        assertThat(entries(zip)).contains("document 1_2.pdf");
    }

    private static List<String> entries(byte[] zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) names.add(e.getName());
        }
        return names;
    }

    private static String readEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (name.equals(e.getName())) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
