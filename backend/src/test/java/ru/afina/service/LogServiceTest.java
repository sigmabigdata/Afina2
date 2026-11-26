package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogServiceTest {

    @TempDir Path tempDir;
    LogService service;

    @BeforeEach
    void setUp() {
        service = new LogService();
        ReflectionTestUtils.setField(service, "logsPath", tempDir.toString());
    }

    @Test
    @DisplayName("tail: файла нет → сообщение")
    void tailMissing() {
        List<String> lines = service.tail(10);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("лог не найден");
    }

    @Test
    @DisplayName("tail: возвращает последние N строк")
    void tail() throws Exception {
        Path log = tempDir.resolve("app.log");
        Files.writeString(log, "line1\nline2\nline3\nline4\nline5\n");
        List<String> lines = service.tail(3);
        assertThat(lines).containsExactly("line3", "line4", "line5");
    }

    @Test
    @DisplayName("tail: N больше строк → все")
    void tailMoreThanExists() throws Exception {
        Path log = tempDir.resolve("app.log");
        Files.writeString(log, "a\nb\n");
        assertThat(service.tail(10)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("tail: N=0 → пусто")
    void tailZero() throws Exception {
        Path log = tempDir.resolve("app.log");
        Files.writeString(log, "a\nb\nc\n");
        List<String> lines = service.tail(0);
        assertThat(lines).isEmpty();
    }

    @Test
    @DisplayName("getLogFile: возвращает путь к app.log")
    void getLogFile() {
        assertThat(service.getLogFile().toString()).endsWith("app.log");
    }
}
