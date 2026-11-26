package ru.afina.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.entity.DocumentTemplate;
import ru.afina.repository.DocumentTemplateRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentTemplateService: парсинг полей и плейсхолдеров")
class DocumentTemplateServiceTest {

    @Mock
    private DocumentTemplateRepository repository;

    @InjectMocks
    private DocumentTemplateService service;

    // ─── extractPlaceholders ───────────────────────────

    @Test
    @DisplayName("extractPlaceholders: одиночный {{key}}")
    void placeholderSingle() {
        assertThat(service.extractPlaceholders("Hello {{name}}"))
                .containsExactly("name");
    }

    @Test
    @DisplayName("extractPlaceholders: несколько разных, порядок сохранён")
    void placeholderMultiple() {
        assertThat(service.extractPlaceholders("{{a}} text {{b}} {{c}}"))
                .containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("extractPlaceholders: дубликаты удалены")
    void placeholderDuplicates() {
        assertThat(service.extractPlaceholders("{{a}}{{a}}{{b}}{{a}}"))
                .containsExactly("a", "b");
    }

    @Test
    @DisplayName("extractPlaceholders: пробелы внутри {{ key }} допустимы")
    void placeholderWhitespace() {
        assertThat(service.extractPlaceholders("{{  x  }} {{y }}"))
                .containsExactly("x", "y");
    }

    @Test
    @DisplayName("extractPlaceholders: пробел в имени не матчится")
    void placeholderSpaceInName() {
        assertThat(service.extractPlaceholders("{{a b}} {{a-b}}"))
                .isEmpty();
    }

    @Test
    @DisplayName("extractPlaceholders: null/plain → пусто")
    void placeholderEmpty() {
        assertThat(service.extractPlaceholders(null)).isEmpty();
        assertThat(service.extractPlaceholders("no placeholders")).isEmpty();
    }

    // ─── validateFieldsJson ────────────────────────────

    @Test
    @DisplayName("validateFieldsJson: валидный JSON → OK")
    void validateValid() {
        String json = "[{\"key\":\"date\",\"label\":\"Дата\",\"type\":\"date\",\"required\":true}]";
        service.validateFieldsJson(json); // no throw
    }

    @Test
    @DisplayName("validateFieldsJson: пустой key → IAE")
    void validateBlankKey() {
        String json = "[{\"key\":\"\",\"label\":\"X\",\"type\":\"text\",\"required\":false}]";
        assertThatThrownBy(() -> service.validateFieldsJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("без key");
    }

    @Test
    @DisplayName("validateFieldsJson: key с дефисом → IAE")
    void validateBadKeyChars() {
        String json = "[{\"key\":\"my-field\",\"label\":\"X\",\"type\":\"text\",\"required\":false}]";
        assertThatThrownBy(() -> service.validateFieldsJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a-z, 0-9, _");
    }

    @Test
    @DisplayName("validateFieldsJson: битый JSON → IAE")
    void validateMalformed() {
        assertThatThrownBy(() -> service.validateFieldsJson("not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Некорректный JSON");
    }

    // ─── parseFields ───────────────────────────────────

    @Test
    @DisplayName("parseFields: null/blank → пусто")
    void parseFieldsEmpty() {
        DocumentTemplate t = new DocumentTemplate();
        assertThat(service.parseFields(t)).isEmpty();

        t.setFieldsJson("");
        assertThat(service.parseFields(t)).isEmpty();
    }

    @Test
    @DisplayName("parseFields: валидный JSON → 2 поля")
    void parseFieldsValid() {
        DocumentTemplate t = new DocumentTemplate();
        t.setFieldsJson("[{\"key\":\"a\",\"label\":\"A\",\"type\":\"text\",\"required\":true}," +
                         "{\"key\":\"b\",\"label\":\"B\",\"type\":\"date\",\"required\":false}]");
        var fields = service.parseFields(t);
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).key()).isEqualTo("a");
        assertThat(fields.get(0).required()).isTrue();
        assertThat(fields.get(1).isDate()).isTrue();
    }

    @Test
    @DisplayName("parseFields: битый JSON → пусто (не падает)")
    void parseFieldsBroken() {
        DocumentTemplate t = new DocumentTemplate();
        t.setFieldsJson("garbage");
        assertThat(service.parseFields(t)).isEmpty();
    }

    // ─── create ────────────────────────────────────────

    @Test
    @DisplayName("create: пустое имя → IAE")
    void createBlankName() {
        assertThatThrownBy(() -> service.create("", "d", "<p>x</p>", null, 0, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Название");
    }

    @Test
    @DisplayName("create: пустой HTML → IAE")
    void createBlankHtml() {
        assertThatThrownBy(() -> service.create("T", "d", "", null, 0, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTML");
    }

    @Test
    @DisplayName("create: валидные данные → save")
    void createValid() {
        when(repository.save(any(DocumentTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var t = service.create("T", "desc", "<p>{{x}}</p>", null, 5, "admin");

        assertThat(t.getName()).isEqualTo("T");
        assertThat(t.getSortOrder()).isEqualTo(5);
        assertThat(t.isActive()).isTrue();
        assertThat(t.getCreatedBy()).isEqualTo("admin");
        verify(repository).save(any(DocumentTemplate.class));
    }

    // ─── update / delete / toggle ──────────────────────

    @Test
    @DisplayName("update: шаблон не найден → IAE")
    void updateNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(999L, "N", "d", "<p/>", null, 0, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    @DisplayName("update: поля обновляются, save вызван")
    void updateSuccess() {
        DocumentTemplate existing = new DocumentTemplate();
        existing.setId(1L);
        existing.setName("Old");
        existing.setActive(true);

        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(DocumentTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var t = service.update(1L, "New", "desc", "<p/>", null, 7, false);

        assertThat(t.getName()).isEqualTo("New");
        assertThat(t.getSortOrder()).isEqualTo(7);
        assertThat(t.isActive()).isFalse();
    }

    @Test
    @DisplayName("toggleActive: инвертирует флаг")
    void toggleActive() {
        DocumentTemplate t = new DocumentTemplate();
        t.setId(1L);
        t.setActive(true);

        when(repository.findById(1L)).thenReturn(Optional.of(t));
        when(repository.save(any(DocumentTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.toggleActive(1L);

        assertThat(t.isActive()).isFalse();
    }

    @Test
    @DisplayName("delete: вызывает deleteById")
    void deleteCallsRepo() {
        service.delete(42L);
        verify(repository).deleteById(42L);
    }

    @Test
    @DisplayName("listActive: возвращает результат репозитория")
    void listActive() {
        List<DocumentTemplate> expected = List.of(new DocumentTemplate(), new DocumentTemplate());
        when(repository.findAllByActiveTrueOrderBySortOrderAscNameAsc()).thenReturn(expected);
        assertThat(service.listActive()).isSameAs(expected);
        verify(repository, never()).findAllByOrderBySortOrderAscNameAsc();
    }
}
