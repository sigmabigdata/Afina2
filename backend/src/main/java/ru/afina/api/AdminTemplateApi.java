package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.api.dto.TemplateDto;
import ru.afina.entity.DocumentTemplate;
import ru.afina.entity.TemplateKind;
import ru.afina.entity.User;
import ru.afina.service.AuditService;
import ru.afina.service.DocumentTemplateService;
import ru.afina.util.FileNames;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Админский API управления шаблонами документов.
 *
 * <p>В отличие от клиентского {@link TemplateApi} администратор видит все
 * шаблоны — включая отключённые и предназначенные для согласия на обработку
 * персональных данных ({@link TemplateKind#CONSENT}).</p>
 *
 * <p>Все методы требуют роль {@code ROLE_ADMIN}; доступ дополнительно
 * ограничен IP-whitelist на уровне {@code SecurityConfig}.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/templates")
public class AdminTemplateApi {

    private static final Logger log = LoggerFactory.getLogger(AdminTemplateApi.class);

    private static final String TARGET_TEMPLATE = "TEMPLATE";
    private static final String RESULT_SUCCESS = "SUCCESS";

    private final DocumentTemplateService templateService;
    private final CurrentUser currentUser;
    private final ApiMapper mapper;
    private final AuditService audit;

    public AdminTemplateApi(DocumentTemplateService templateService, CurrentUser currentUser,
                            ApiMapper mapper, AuditService audit) {
        this.templateService = templateService;
        this.currentUser = currentUser;
        this.mapper = mapper;
        this.audit = audit;
    }

    /** Все шаблоны: активные и отключённые, включая назначение {@code CONSENT}. */
    @GetMapping
    public List<TemplateDto> list(Authentication auth) {
        currentUser.requireAdmin(auth);
        return templateService.listAll().stream()
                .map(mapper::template)
                .toList();
    }

    /** Один шаблон со всеми полями (в том числе HTML и описание полей). */
    @GetMapping("/{id}")
    public TemplateDto get(@PathVariable Long id, Authentication auth) {
        currentUser.requireAdmin(auth);
        return mapper.template(templateService.getById(id));
    }

    /**
     * Создание шаблона.
     *
     * <p>Сервис всегда создаёт шаблон активным; если администратор снял
     * флажок {@code active}, статус сразу переключается в отключённый.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateDto create(@RequestBody TemplateRequest req, Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        TemplateKind kind = parseKind(req.kind());
        int sortOrder = req.sortOrder() == null ? 0 : req.sortOrder();

        DocumentTemplate template = templateService.create(req.name(), req.description(),
                req.htmlContent(), req.fieldsJson(), sortOrder, kind, admin.getEmail());
        if (Boolean.FALSE.equals(req.active())) {
            templateService.toggleActive(template.getId());
            template = templateService.getById(template.getId());
        }

        audit.event("TEMPLATE_CREATE", RESULT_SUCCESS, admin.getEmail(), TARGET_TEMPLATE,
                String.valueOf(template.getId()), template.getName(), null);
        log.info("Шаблон создан: id={}, name='{}', kind={}",
                template.getId(), template.getName(), template.getKind());
        return mapper.template(template);
    }

    /**
     * Обновление шаблона.
     *
     * <p>Не переданные в теле поля ({@code null}) сохраняют прежние значения;
     * это позволяет менять, например, только видимость шаблона.</p>
     */
    @PutMapping("/{id}")
    public TemplateDto update(@PathVariable Long id, @RequestBody TemplateRequest req,
                              Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        DocumentTemplate existing = templateService.getById(id);
        int sortOrder = req.sortOrder() == null ? existing.getSortOrder() : req.sortOrder();
        boolean active = req.active() == null ? existing.isActive() : req.active();

        DocumentTemplate template = templateService.update(id, req.name(), req.description(),
                req.htmlContent(), req.fieldsJson(), sortOrder, active,
                parseKindOrNull(req.kind()));

        audit.event("TEMPLATE_UPDATE", RESULT_SUCCESS, admin.getEmail(), TARGET_TEMPLATE,
                String.valueOf(id), template.getName(), null);
        return mapper.template(template);
    }

    /** Удаление шаблона вместе с полями и HTML. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        DocumentTemplate template = templateService.getById(id);
        templateService.delete(id);
        audit.event("TEMPLATE_DELETE", RESULT_SUCCESS, admin.getEmail(), TARGET_TEMPLATE,
                String.valueOf(id), template.getName(), null);
    }

    /** Переключение доступности шаблона; возвращает шаблон с новым статусом. */
    @PostMapping("/{id}/toggle")
    public TemplateDto toggle(@PathVariable Long id, Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        templateService.toggleActive(id);
        DocumentTemplate template = templateService.getById(id);
        audit.event("TEMPLATE_TOGGLE", RESULT_SUCCESS, admin.getEmail(), TARGET_TEMPLATE,
                String.valueOf(id), template.getName(),
                template.isActive() ? "Шаблон включён" : "Шаблон отключён");
        return mapper.template(template);
    }

    /**
     * Превью шаблона в PDF по переданным значениям полей.
     *
     * <p>Тело запроса: {@code {"values": {"fio": "Иванов И. И."}}}. Поля, для
     * которых значение не передано, заполняются демонстрационными данными,
     * чтобы превью было наглядным. Ответ отдаётся как {@code application/pdf}
     * с {@code Content-Disposition: inline} — для показа в iframe.</p>
     */
    @PostMapping("/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long id,
                                          @RequestBody(required = false) PreviewRequest req,
                                          Authentication auth) {
        currentUser.requireAdmin(auth);
        DocumentTemplate template = templateService.getById(id);

        Map<String, String> values = demoValues(template);
        if (req != null && req.values() != null) {
            values.putAll(req.values());
        }

        byte[] pdf;
        try {
            pdf = templateService.renderPdf(template, values);
        } catch (IOException e) {
            log.error("Не удалось сформировать превью шаблона {}", id, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось сформировать PDF по шаблону");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.inline("preview-" + id + ".pdf"))
                .body(pdf);
    }

    /**
     * Проверка корректности JSON описания полей шаблона.
     *
     * <p>Ошибка в JSON — это некорректный ввод ({@code 400}); при успехе
     * возвращается разобранный список полей. Пустой {@code fieldsJson}
     * считается корректным (шаблон без полей).</p>
     */
    @PostMapping("/validate-fields")
    public Map<String, Object> validateFields(
            @RequestBody(required = false) ValidateFieldsRequest req, Authentication auth) {
        currentUser.requireAdmin(auth);
        String fieldsJson = req == null ? null : req.fieldsJson();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", true);
        if (fieldsJson == null || fieldsJson.isBlank()) {
            body.put("fields", List.of());
            return body;
        }

        templateService.validateFieldsJson(fieldsJson);
        DocumentTemplate probe = new DocumentTemplate();
        probe.setFieldsJson(fieldsJson);
        body.put("fields", templateService.parseFields(probe));
        return body;
    }

    // ─────────────────────────── вспомогательное ───────────────────────────

    /** Демонстрационные значения для плейсхолдеров — чтобы превью было информативным. */
    private Map<String, String> demoValues(DocumentTemplate template) {
        Map<String, String> values = new HashMap<>();
        for (DocumentTemplateService.TemplateField field : templateService.parseFields(template)) {
            String key = field.key();
            if (key == null || key.isBlank()) {
                continue;
            }
            String label = field.label() == null ? key : field.label();
            if (field.isDate()) {
                values.put(key, LocalDate.now().toString());
            } else if (key.contains("email")) {
                values.put(key, "client@example.ru");
            } else if (key.contains("phone")) {
                values.put(key, "+7 (999) 123-45-67");
            } else if (key.contains("full_name") || key.contains("fio")) {
                values.put(key, "Иванов Иван Иванович");
            } else if (!field.defaultOr().isBlank()) {
                values.put(key, field.defaultOr());
            } else {
                values.put(key, "[" + label + "]");
            }
        }
        // Плейсхолдеры, которых нет в fields_json, тоже заполняем демо-значением
        for (String key : templateService.extractPlaceholders(template.getHtmlContent())) {
            values.putIfAbsent(key, "[" + key + "]");
        }
        return values;
    }

    /** Назначение шаблона из запроса; пустое значение — обычный документ. */
    private static TemplateKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return TemplateKind.DOCUMENT;
        }
        try {
            return TemplateKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Неизвестное назначение шаблона: " + kind
                    + ". Допустимые значения: DOCUMENT, CONSENT");
        }
    }

    /** Как {@link #parseKind(String)}, но пустое значение оставляет назначение без изменений. */
    private static TemplateKind parseKindOrNull(String kind) {
        return kind == null || kind.isBlank() ? null : parseKind(kind);
    }

    // ─────────────────────────── тела запросов ───────────────────────────

    /**
     * Тело создания и обновления шаблона.
     *
     * <p>{@code sortOrder} и {@code active} необязательны: при создании
     * принимают значения {@code 0} и {@code true}, при обновлении — текущие
     * значения шаблона.</p>
     */
    public record TemplateRequest(String name, String description, String htmlContent,
                                  String fieldsJson, String kind, Integer sortOrder,
                                  Boolean active) {
    }

    /** Тело запроса превью: значения полей по ключам {@code {{key}}}. */
    public record PreviewRequest(Map<String, String> values) {
    }

    /** Тело запроса проверки JSON полей шаблона. */
    public record ValidateFieldsRequest(String fieldsJson) {
    }
}
