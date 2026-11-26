package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import ru.afina.entity.Role;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCredentialsFileServiceTest {

    @TempDir Path tempDir;
    AdminCredentialsFileService service;
    Path adminsFile;

    @BeforeEach
    void setUp() {
        adminsFile = tempDir.resolve("admins.env");
        service = new AdminCredentialsFileService();
        ReflectionTestUtils.setField(service, "adminsFilePath", adminsFile.toString());
    }

    @Test
    @DisplayName("readAll: файла нет → пустой список")
    void readAll_missing() {
        assertThat(service.readAll()).isEmpty();
    }

    @Test
    @DisplayName("readAll: пустой файл → пустой список")
    void readAll_empty() throws Exception {
        Files.writeString(adminsFile, "");
        assertThat(service.readAll()).isEmpty();
    }

    @Test
    @DisplayName("readAll: одна запись CN|SNILS")
    void readAll_one() throws Exception {
        Files.writeString(adminsFile, "Иванов Иван Иванович|12345678901\n");
        List<AdminCredentialsFileService.AdminRecord> list = service.readAll();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).cn()).isEqualTo("Иванов Иван Иванович");
        assertThat(list.get(0).snils()).isEqualTo("12345678901");
        assertThat(list.get(0).role()).isEqualTo(Role.ROLE_ADMIN);
    }

    @Test
    @DisplayName("readAll: комментарии и пустые строки пропускаются")
    void readAll_commentsSkipped() throws Exception {
        Files.writeString(adminsFile,
                "# comment\n" +
                "\n" +
                "Иванов И.И.|12345678901\n" +
                "  # indented comment\n" +
                "Петров П.П.|98765432109\n");
        List<AdminCredentialsFileService.AdminRecord> list = service.readAll();
        assertThat(list).hasSize(2);
    }

    @Test
    @DisplayName("readAll: роль employee (3-й столбец)")
    void readAll_employee() throws Exception {
        Files.writeString(adminsFile, "Иванов И.И.|12345678901|employee\n");
        List<AdminCredentialsFileService.AdminRecord> list = service.readAll();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).role()).isEqualTo(Role.ROLE_EMPLOYEE);
    }

    @Test
    @DisplayName("readAll: невалидные строки пропускаются")
    void readAll_invalidSkipped() throws Exception {
        Files.writeString(adminsFile, "no separator\n" +
                "|empty cn\n" +
                "empty snils|\n" +
                "Иванов И.И.|12345678901\n");
        List<AdminCredentialsFileService.AdminRecord> list = service.readAll();
        assertThat(list).hasSize(1);
    }

    @Test
    @DisplayName("find: точное совпадение CN + SNILS")
    void find_match() throws Exception {
        Files.writeString(adminsFile, "Иванов И.И.|12345678901\n");
        Optional<AdminCredentialsFileService.AdminRecord> r =
                service.find("Иванов И.И.", "12345678901");
        assertThat(r).isPresent();
    }

    @Test
    @DisplayName("find: нормализация пробелов в CN")
    void find_normalizesCnSpaces() throws Exception {
        Files.writeString(adminsFile, "Иванов  Иван  Иванович|12345678901\n");
        assertThat(service.find("Иванов Иван Иванович", "12345678901")).isPresent();
    }

    @Test
    @DisplayName("find: нормализация СНИЛС (дефисы/пробелы)")
    void find_normalizesSnils() throws Exception {
        Files.writeString(adminsFile, "Иванов И.И.|123-456-789 01\n");
        assertThat(service.find("Иванов И.И.", "12345678901")).isPresent();
    }

    @Test
    @DisplayName("find: case-insensitive CN")
    void find_caseInsensitive() throws Exception {
        Files.writeString(adminsFile, "ИВАНОВ И.И.|12345678901\n");
        assertThat(service.find("иванов и.и.", "12345678901")).isPresent();
    }

    @Test
    @DisplayName("find: null параметры → empty")
    void find_null() {
        assertThat(service.find(null, "12345678901")).isEmpty();
        assertThat(service.find("Иванов", null)).isEmpty();
    }

    @Test
    @DisplayName("find: нет совпадения → empty")
    void find_noMatch() throws Exception {
        Files.writeString(adminsFile, "Иванов И.И.|12345678901\n");
        assertThat(service.find("Петров П.П.", "12345678901")).isEmpty();
        assertThat(service.find("Иванов И.И.", "99999999999")).isEmpty();
    }
}
