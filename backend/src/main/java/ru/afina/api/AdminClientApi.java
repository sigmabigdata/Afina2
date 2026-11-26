package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.api.dto.DocumentDto;
import ru.afina.api.dto.FolderDto;
import ru.afina.api.dto.PageDto;
import ru.afina.api.dto.UserDto;
import ru.afina.dto.SignRequest;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.gosklyuch.GosklyuchService;
import ru.afina.repository.DocumentRepository;
import ru.afina.repository.UserRepository;
import ru.afina.security.PiiMask;
import ru.afina.service.AuditService;
import ru.afina.service.ClientExportService;
import ru.afina.service.ConsentService;
import ru.afina.service.CrlOnDemandService;
import ru.afina.service.DocumentNotifyService;
import ru.afina.service.DocumentService;
import ru.afina.service.FolderService;
import ru.afina.service.SignatureVerifier;
import ru.afina.service.SignatureVisualizationService;
import ru.afina.service.UserService;
import ru.afina.util.FileNames;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Админский API: карточки клиентов, их документы, подписание за клиента.
 *
 * <p>Все методы требуют роль {@code ROLE_ADMIN}; доступ дополнительно
 * ограничен IP-whitelist на уровне {@code SecurityConfig}.</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminClientApi {

    private static final Logger log = LoggerFactory.getLogger(AdminClientApi.class);
    private static final String TARGET_DOCUMENT = "DOCUMENT";

    private final UserService userService;
    private final UserRepository userRepository;
    private final FolderService folderService;
    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final CurrentUser currentUser;
    private final ApiMapper mapper;
    private final AuditService audit;
    private final ConsentService consentService;
    private final ClientExportService exportService;
    private final SignatureVerifier signatureVerifier;
    private final SignatureVisualizationService visualizationService;
    private final CrlOnDemandService crlOnDemandService;
    private final DocumentNotifyService documentNotifyService;
    private final GosklyuchService gosklyuchService;
    private final ru.afina.service.EmailService emailService;
    private final ru.afina.security.PiiEncryptor pii;

    /** Полная проверка .sig (цепочка+CRL); в демо/тестах выключается. */
    @org.springframework.beans.factory.annotation.Value(
            "${app.signature.attachment.require-chain:true}")
    private boolean attachmentRequireChain = true;

    @SuppressWarnings("java:S107")
    public AdminClientApi(UserService userService, UserRepository userRepository,
                          FolderService folderService, DocumentService documentService,
                          DocumentRepository documentRepository, CurrentUser currentUser,
                          ApiMapper mapper, AuditService audit, ConsentService consentService,
                          ClientExportService exportService, SignatureVerifier signatureVerifier,
                          SignatureVisualizationService visualizationService,
                          CrlOnDemandService crlOnDemandService,
                          DocumentNotifyService documentNotifyService,
                          GosklyuchService gosklyuchService,
                          ru.afina.service.EmailService emailService,
                          ru.afina.security.PiiEncryptor pii) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.folderService = folderService;
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.currentUser = currentUser;
        this.mapper = mapper;
        this.audit = audit;
        this.consentService = consentService;
        this.exportService = exportService;
        this.signatureVerifier = signatureVerifier;
        this.visualizationService = visualizationService;
        this.crlOnDemandService = crlOnDemandService;
        this.documentNotifyService = documentNotifyService;
        this.gosklyuchService = gosklyuchService;
        this.emailService = emailService;
        this.pii = pii;
    }

    // ═══════════════════════════════════════════════════════
    //  Клиенты
    // ═══════════════════════════════════════════════════════

    /** Список клиентов с поиском и фильтром по статусу. */
    @GetMapping("/clients")
    public PageDto<UserDto> clients(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    Authentication auth) {
        currentUser.requireAdmin(auth);
        Boolean enabled = switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
            case "active" -> Boolean.TRUE;
            case "blocked" -> Boolean.FALSE;
            default -> null;
        };
        String query = q == null ? null : q.trim();
        String like = (query == null || query.isEmpty()) ? null
                : "%" + query.toLowerCase(Locale.ROOT) + "%";
        String emailHash = (query == null || query.isEmpty()) ? null : pii.hash(query);
        List<UserDto> all = userRepository.searchForAdmin(enabled, null, like, emailHash).stream()
                .map(u -> mapper.user(u,
                        documentRepository.countByOwner(u),
                        documentRepository.countByOwnerAndSignedTrue(u)))
                .toList();
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return PageDto.of(all.subList(from, to), all.size(), page, size);
    }

    /** Карточка клиента с разделами и документами. */
    @GetMapping("/clients/{id}")
    public Map<String, Object> client(@PathVariable Long id, Authentication auth) {
        currentUser.requireAdmin(auth);
        User client = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Клиент не найден"));
        List<DocumentFolder> folders = folderService.listForOwner(client);
        List<Document> docs = documentRepository.findAllByOwnerOrderByUploadedAtDesc(client);
        Map<Long, ru.afina.entity.GosklyuchOrder> orders = gosklyuchService
                .latestByDocumentIds(docs.stream().map(Document::getId).toList());

        List<FolderDto> folderDtos = folders.stream()
                .map(f -> mapper.folder(f,
                        documentRepository.findAllByFolderOrderByUploadedAtDesc(f).size(),
                        documentRepository.countByFolderAndSignedTrue(f)))
                .toList();
        List<DocumentDto> docDtos = docs.stream()
                .map(d -> mapper.document(d, orders.get(d.getId())))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", mapper.user(client,
                documentRepository.countByOwner(client),
                documentRepository.countByOwnerAndSignedTrue(client)));
        body.put("folders", folderDtos);
        body.put("documents", docDtos);
        body.put("gosklyuchOrders", gosklyuchService
                .recentForOwner(client.getId(), 20).stream().map(mapper::order).toList());
        body.put("consentConfigured", consentService.consentConfigured());
        return body;
    }

    /** Создание клиента. */
    @PostMapping("/clients")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto createClient(@RequestBody CreateClientRequest req, Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        User created = userService.adminCreate(req.email(), req.fullName(), req.phone(),
                req.snils(), req.role() == null ? Role.ROLE_USER : req.role(),
                req.enabled() == null || req.enabled());
        audit.userCreate(admin.getEmail(), created.getId(), created.getEmail());
        // Клиенту сразу создаём раздел по умолчанию: без него некуда загружать документы.
        // Пустой раздел удаляется вместе с клиентом (см. UserService.adminDelete).
        if (created.getRole() == Role.ROLE_USER) {
            folderService.ensureDefault(created);
        }
        return mapper.user(created);
    }

    /** Обновление клиента. */
    @PatchMapping("/clients/{id}")
    public UserDto updateClient(@PathVariable Long id, @RequestBody UpdateClientRequest req,
                                Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        // null в запросе означает «не менять»: иначе PATCH без поля enabled
        // молча разблокировал бы клиента
        User current = userRepository.findById(id)
                .orElseThrow(() -> new ru.afina.error.NotFoundException("Клиент не найден"));
        boolean enabled = req.enabled() == null ? current.isEnabled() : req.enabled();
        User updated = userService.adminUpdate(id, req.email(), req.fullName(), req.phone(),
                req.snils(), req.role(), enabled);
        audit.userUpdate(admin.getEmail(), updated.getId(), updated.getEmail());
        return mapper.user(updated);
    }

    /** Дополнительные поля профиля (паспорт, ИНН, адрес и т.д.). */
    @PatchMapping("/clients/{id}/profile")
    public UserDto updateProfile(@PathVariable Long id, @RequestBody Map<String, String> fields,
                                 Authentication auth) {
        currentUser.requireAdmin(auth);
        userService.applyClientProfile(id, fields);
        User updated = userRepository.findById(id).orElseThrow();
        return mapper.user(updated);
    }

    @DeleteMapping("/clients/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteClient(@PathVariable Long id, Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        User client = userRepository.findById(id)
                .orElseThrow(() -> new ru.afina.error.NotFoundException("Клиент не найден"));
        userService.adminDelete(id, admin.getEmail());
        audit.userDelete(admin.getEmail(), id, client.getEmail());
    }

    @PostMapping("/clients/{id}/toggle")
    public UserDto toggleClient(@PathVariable Long id, Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        userService.adminToggle(id);
        User updated = userRepository.findById(id)
                .orElseThrow(() -> new ru.afina.error.NotFoundException("Клиент не найден"));
        audit.userUpdate(admin.getEmail(), id, updated.getEmail());
        return mapper.user(updated);
    }

    /** Отправить клиенту ссылку для входа. */
    @PostMapping("/clients/{id}/login-link")
    public Map<String, Object> sendLoginLink(@PathVariable Long id, Authentication auth,
                                             @org.springframework.beans.factory.annotation.Value(
                                                     "${app.base-url}") String baseUrl) {
        User admin = currentUser.requireAdmin(auth);
        User client = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Клиент не найден"));
        if (!client.isEnabled()) {
            throw new IllegalStateException("Клиент заблокирован");
        }
        String link = userService.generateLoginLink(client.getEmail(), baseUrl);
        if (link == null) {
            throw new IllegalStateException("Не удалось создать ссылку для входа");
        }
        // Ссылка не только создаётся, но и отправляется на почту клиента
        emailService.sendLoginLink(client.getEmail(), link, client.getFullName());
        audit.event("LOGIN_LINK_SENT", "SUCCESS", admin.getEmail(), "USER",
                String.valueOf(id), PiiMask.email(client.getEmail()),
                "Ссылка для входа отправлена администратором");
        return Map.of("sent", true, "email", PiiMask.email(client.getEmail()));
    }

    /** Выгрузка карточки клиента в ZIP (документы + реестр). */
    @GetMapping("/clients/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id, Authentication auth)
            throws IOException {
        User admin = currentUser.requireAdmin(auth);
        byte[] zip = exportService.exportClient(id);
        User client = userRepository.findById(id).orElseThrow();
        audit.event("CLIENT_EXPORT", "SUCCESS", admin.getEmail(), "USER",
                String.valueOf(id), client.getFullName(), "Выгрузка карточки клиента");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.attachment("client_" + id + ".zip"))
                .body(zip);
    }

    // ═══════════════════════════════════════════════════════
    //  Документы клиента
    // ═══════════════════════════════════════════════════════

    /** Загрузка документа за клиента в выбранный раздел. */
    @PostMapping(value = "/clients/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDto uploadForClient(@PathVariable Long id,
                                       @RequestPart("file") MultipartFile file,
                                       @RequestParam(required = false) Long folderId,
                                       Authentication auth) throws IOException {
        User admin = currentUser.requireAdmin(auth);
        User client = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Клиент не найден"));
        DocumentFolder folder = folderId != null
                ? folderService.getOwned(folderId, client)
                : folderService.ensureDefault(client);
        Document doc = documentService.upload(file, client, folder);
        audit.documentUpload(admin.getEmail(), doc.getId(), doc.getOriginalName());
        return mapper.document(doc);
    }

    /** Создание раздела у клиента. */
    @PostMapping("/clients/{id}/folders")
    @ResponseStatus(HttpStatus.CREATED)
    public FolderDto createFolder(@PathVariable Long id, @RequestBody Map<String, String> body,
                                  Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        User client = userRepository.findById(id)
                .orElseThrow(() -> new ru.afina.error.NotFoundException("Клиент не найден"));
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Укажите название раздела");
        }
        DocumentFolder folder = folderService.create(client, name);
        audit.folderCreate(admin.getEmail(), folder.getId(), folder.getName());
        return mapper.folder(folder, 0, 0);
    }

    // Менеджер заявок и прочие админские операции вынесены в отдельные контроллеры

    @GetMapping("/documents/{id}")
    public DocumentDto document(@PathVariable Long id, Authentication auth) {
        currentUser.requireAdmin(auth);
        Document doc = documentRepository.findByIdWithSignatures(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Документ не найден"));
        return mapper.document(doc, gosklyuchService.latestOrder(id).orElse(null));
    }

    @GetMapping("/documents/{id}/content")
    public ResponseEntity<org.springframework.core.io.Resource> documentContent(
            @PathVariable Long id, Authentication auth) throws IOException {
        User admin = currentUser.requireAdmin(auth);
        Document doc = documentService.getById(id);
        var resource = documentService.loadAsResource(doc);
        MediaType mt = doc.getContentType() != null
                ? MediaType.parseMediaType(doc.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        audit.event("DOC_VIEW", "SUCCESS", admin.getEmail(), TARGET_DOCUMENT,
                String.valueOf(id), doc.getOriginalName(), "Просмотр (админ)");
        return ResponseEntity.ok()
                .contentType(mt)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.inline(doc.getOriginalName()))
                .body(resource);
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<org.springframework.core.io.Resource> documentDownload(
            @PathVariable Long id, Authentication auth) throws IOException {
        User admin = currentUser.requireAdmin(auth);
        Document doc = documentService.getById(id);
        var resource = documentService.loadAsResource(doc);
        audit.event("DOC_DOWNLOAD", "SUCCESS", admin.getEmail(), TARGET_DOCUMENT,
                String.valueOf(id), doc.getOriginalName(), "Скачивание (админ)");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.attachment(doc.getOriginalName()))
                .body(resource);
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable Long id, Authentication auth) throws IOException {
        User admin = currentUser.requireAdmin(auth);
        Document doc = documentService.getById(id);
        documentService.deleteAsAdmin(id);
        audit.documentDelete(admin.getEmail(), id, doc.getOriginalName(), doc.isSigned());
    }

    /** Администратор подписывает документ клиента своей УКЭП. */
    @PostMapping("/documents/{id}/sign")
    public Map<String, Object> signAsAdmin(@PathVariable Long id, @RequestBody SignRequest req,
                                           Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        Document doc = documentRepository.findByIdWithOwner(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Документ не найден"));
        if (documentService.hasSignatureByUser(doc.getId(), admin.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Вы уже подписали этот документ");
        }
        String sig = req.getSignatureBase64();
        if (sig == null || sig.isBlank()) {
            throw new IllegalArgumentException("Пустая подпись");
        }
        try {
            crlOnDemandService.ensureForSignature(sig, id, admin.getEmail());
        } catch (Exception e) {
            log.warn("CRL before admin sign failed for doc {}: {}", id, e.getMessage());
        }
        try {
            byte[] content = documentService.getBytes(doc);
            Map<String, Object> result = attachmentRequireChain
                    ? signatureVerifier.verifyDetached(content, sig)
                    : signatureVerifier.verifyDetachedBasic(content, sig);
            String subject = String.valueOf(result.getOrDefault("signerSubject", ""));
            String serial = String.valueOf(result.getOrDefault("signerSerial", ""));
            documentService.addSignature(id, admin, sig, subject, serial);
            documentNotifyService.documentSigned(doc, admin);
            audit.signSuccess(admin.getEmail(), id, subject + " (ROLE_ADMIN)");
            try {
                visualizationService.generate(doc);
            } catch (Exception e) {
                log.debug("Визуализация не создана: {}", e.getMessage());
            }
            return Map.of("valid", true, "signerSubject", subject, "signerSerial", serial);
        } catch (IllegalArgumentException e) {
            audit.signFail(admin.getEmail(), id, e.getMessage());
            throw e;
        } catch (Exception e) {
            audit.signFail(admin.getEmail(), id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Ошибка проверки подписи: " + e.getMessage());
        }
    }

    /** Скачать .sig документа (админ). */
    @GetMapping("/documents/{id}/signature")
    public ResponseEntity<byte[]> documentSignature(@PathVariable Long id, Authentication auth) {
        currentUser.requireAdmin(auth);
        Document doc = documentService.getById(id);
        String signature = documentService.primarySignatureBase64(doc);
        if (signature == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Подпись отсутствует");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.attachment(doc.getOriginalName() + ".sig"))
                .body(java.util.Base64.getDecoder().decode(signature.replaceAll("\\s+", "")));
    }

    /** Все подписи клиента одним ZIP. */
    @GetMapping("/clients/{id}/signatures.zip")
    public ResponseEntity<byte[]> allSignatures(@PathVariable Long id, Authentication auth)
            throws IOException {
        currentUser.requireAdmin(auth);
        User client = userRepository.findById(id).orElseThrow();
        List<Document> docs = documentRepository.findAllByOwnerOrderByUploadedAtDesc(client);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.util.Set<String> used = new java.util.HashSet<>();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(baos)) {
            for (Document d : docs) {
                String sig = documentService.primarySignatureBase64(d);
                if (sig == null) continue;
                String name = DocumentApi.uniqueEntryName(
                        FileNames.sanitizeZipEntry(d.getOriginalName()) + ".sig", used);
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                zip.write(java.util.Base64.getDecoder().decode(sig.replaceAll("\\s+", "")));
                zip.closeEntry();
            }
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.attachment("signatures_client_" + id + ".zip"))
                .body(baos.toByteArray());
    }

    // ═══════════════════════════════════════════════════════
    //  DTO запросов
    // ═══════════════════════════════════════════════════════

    public record CreateClientRequest(String email, String fullName, String phone,
                                      String snils, Role role, Boolean enabled) {
    }

    public record UpdateClientRequest(String email, String fullName, String phone,
                                      String snils, Role role, Boolean enabled) {
    }
}
