package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.afina.api.dto.DocumentDto;
import ru.afina.api.dto.TemplateDto;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.DocumentTemplate;
import ru.afina.entity.TemplateKind;
import ru.afina.entity.User;
import ru.afina.service.AuditService;
import ru.afina.service.DocumentService;
import ru.afina.service.DocumentTemplateService;
import ru.afina.service.FolderService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Шаблоны документов для клиента: список доступных форм и генерация PDF.
 * Шаблоны назначения {@code CONSENT} клиентам не показываются.
 */
@RestController
@RequestMapping("/api/v1/templates")
public class TemplateApi {

    private static final Logger log = LoggerFactory.getLogger(TemplateApi.class);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd.MM.yyyy_HHmm");

    private final DocumentTemplateService templateService;
    private final DocumentService documentService;
    private final FolderService folderService;
    private final CurrentUser currentUser;
    private final ApiMapper mapper;
    private final AuditService audit;

    public TemplateApi(DocumentTemplateService templateService, DocumentService documentService,
                       FolderService folderService, CurrentUser currentUser,
                       ApiMapper mapper, AuditService audit) {
        this.templateService = templateService;
        this.documentService = documentService;
        this.folderService = folderService;
        this.currentUser = currentUser;
        this.mapper = mapper;
        this.audit = audit;
    }

    /** Активные шаблоны с описанием полей. */
    @GetMapping
    public List<TemplateDto> list() {
        return templateService.listActive().stream()
                .filter(t -> t.getKind() != TemplateKind.CONSENT)
                .map(mapper::template)
                .toList();
    }

    @GetMapping("/{id}")
    public TemplateDto get(@PathVariable Long id) {
        DocumentTemplate t = templateService.getById(id);
        if (t.getKind() == TemplateKind.CONSENT) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Шаблон не найден");
        }
        return mapper.template(t);
    }

    /** Генерация документа из шаблона (PDF) и сохранение в выбранный раздел. */
    @PostMapping("/{id}/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDto generate(@PathVariable Long id, @RequestBody GenerateRequest req,
                                Authentication auth) throws java.io.IOException {
        User user = currentUser.entity(auth);
        DocumentTemplate template = templateService.getById(id);
        if (template.getKind() == TemplateKind.CONSENT) {
            throw new IllegalArgumentException("Этот шаблон недоступен для генерации документов");
        }
        if (!template.isActive()) {
            throw new IllegalArgumentException("Шаблон недоступен");
        }
        Long folderId = req.folderId() != null ? req.folderId()
                : folderService.ensureDefault(user).getId();
        DocumentFolder folder = folderService.getOwned(folderId, user);

        Map<String, String> values = req.values();
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Не заполнены поля");
        }
        for (var f : templateService.parseFields(template)) {
            if (f.required()) {
                String v = values.get(f.key());
                if (v == null || v.isBlank()) {
                    throw new IllegalArgumentException(
                            "Не заполнено обязательное поле: " + f.label());
                }
            }
        }

        byte[] pdf = templateService.renderPdf(template, values);
        String fileName = template.getName() + "_"
                + LocalDateTime.now().format(STAMP) + ".pdf";
        Document doc = documentService.createDocument(
                pdf, fileName, "application/pdf", user, folder);

        audit.event("DOC_FROM_TEMPLATE", "SUCCESS", user.getEmail(), "DOCUMENT",
                String.valueOf(doc.getId()), template.getName(),
                "Сгенерирован из шаблона id=" + id);
        log.info("Шаблон {} → документ {} (пользователь {})",
                template.getId(), doc.getId(), user.getId());
        return mapper.document(doc);
    }

    /** Тело запроса генерации. */
    public record GenerateRequest(Long folderId, Map<String, String> values) {
    }
}
