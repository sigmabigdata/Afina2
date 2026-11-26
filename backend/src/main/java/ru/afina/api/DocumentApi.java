package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.api.dto.DocumentDto;
import ru.afina.api.dto.GosklyuchOrderDto;
import ru.afina.api.dto.PageDto;
import ru.afina.dto.SignRequest;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.DocumentSignature;
import ru.afina.entity.User;
import ru.afina.gosklyuch.GosklyuchService;
import ru.afina.repository.DocumentRepository;
import ru.afina.repository.DocumentSignatureRepository;
import ru.afina.service.AuditService;
import ru.afina.service.ConsentService;
import ru.afina.service.CrlOnDemandService;
import ru.afina.service.DocumentNotifyService;
import ru.afina.service.DocumentService;
import ru.afina.service.FolderService;
import ru.afina.service.SignatureVerifier;
import ru.afina.service.SignatureVisualizationService;
import ru.afina.service.SigningCertificateService;
import ru.afina.util.FileNames;
import ru.afina.util.PersonData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Документы клиента: загрузка, просмотр, подписание, визуализация, Госключ.
 *
 * <p>Все операции проверяют принадлежность документа текущему пользователю;
 * администраторы работают через {@code /api/v1/admin/…}.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentApi {

    private static final Logger log = LoggerFactory.getLogger(DocumentApi.class);
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String TARGET_DOCUMENT = "DOCUMENT";

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final DocumentSignatureRepository signatureRepository;
    private final FolderService folderService;
    private final CurrentUser currentUser;
    private final ApiMapper mapper;
    private final AuditService audit;
    private final ConsentService consentService;
    private final CrlOnDemandService crlOnDemandService;
    private final SignatureVerifier signatureVerifier;
    private final SignatureVisualizationService visualizationService;
    private final SigningCertificateService certificateService;
    private final DocumentNotifyService documentNotifyService;
    private final GosklyuchService gosklyuchService;

    /** Полная проверка .sig (цепочка+CRL). В демо можно отключить: self-signed сертификат. */
    @Value("${app.signature.attachment.require-chain:true}")
    private boolean attachmentRequireChain;

    @SuppressWarnings("java:S107") // контроллер агрегирует зависимости сервисов
    public DocumentApi(DocumentService documentService,
                       DocumentRepository documentRepository,
                       DocumentSignatureRepository signatureRepository,
                       FolderService folderService,
                       CurrentUser currentUser,
                       ApiMapper mapper,
                       AuditService audit,
                       ConsentService consentService,
                       CrlOnDemandService crlOnDemandService,
                       SignatureVerifier signatureVerifier,
                       SignatureVisualizationService visualizationService,
                       SigningCertificateService certificateService,
                       DocumentNotifyService documentNotifyService,
                       GosklyuchService gosklyuchService) {
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.signatureRepository = signatureRepository;
        this.folderService = folderService;
        this.currentUser = currentUser;
        this.mapper = mapper;
        this.audit = audit;
        this.consentService = consentService;
        this.crlOnDemandService = crlOnDemandService;
        this.signatureVerifier = signatureVerifier;
        this.visualizationService = visualizationService;
        this.certificateService = certificateService;
        this.documentNotifyService = documentNotifyService;
        this.gosklyuchService = gosklyuchService;
    }

    // ═══════════════════════════════════════════════════════
    //  Список и карточка
    // ═══════════════════════════════════════════════════════

    /** Список документов клиента с фильтрами по разделу, статусу и названию. */
    @GetMapping
    public PageDto<DocumentDto> list(Authentication auth,
                                     @RequestParam(required = false) Long folderId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String search,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        User user = currentUser.entity(auth);
        List<Document> docs;
        if (folderId != null) {
            DocumentFolder folder = folderService.getOwned(folderId, user);
            docs = documentRepository.findAllByFolderOrderByUploadedAtDesc(folder);
        } else {
            docs = documentRepository.findAllByOwnerOrderByUploadedAtDesc(user);
        }

        Map<Long, ru.afina.entity.GosklyuchOrder> orders = Map.of();
        if (!docs.isEmpty()) {
            orders = gosklyuchService.latestByDocumentIds(
                    docs.stream().map(Document::getId).toList());
        }

        String q = search == null ? null : search.trim().toLowerCase(Locale.ROOT);
        String st = status == null ? null : status.trim().toLowerCase(Locale.ROOT);
        Map<Long, ru.afina.entity.GosklyuchOrder> finalOrders = orders;
        List<DocumentDto> filtered = docs.stream()
                .filter(d -> q == null || q.isEmpty()
                        || d.getOriginalName().toLowerCase(Locale.ROOT).contains(q))
                .filter(d -> matchesStatus(d, st, finalOrders))
                .map(d -> mapper.document(d, finalOrders.get(d.getId())))
                .toList();

        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return PageDto.of(filtered.subList(from, to), filtered.size(), page, size);
    }

    private static boolean matchesStatus(Document d, String status,
                                         Map<Long, ru.afina.entity.GosklyuchOrder> orders) {
        if (status == null || status.isEmpty() || "all".equals(status)) return true;
        boolean pending = orders.containsKey(d.getId())
                && orders.get(d.getId()).getStatus() != null
                && !orders.get(d.getId()).getStatus().isTerminal();
        return switch (status) {
            case "signed" -> d.isSigned();
            case "unsigned" -> !d.isSigned() && !pending;
            case "pending" -> pending;
            default -> true;
        };
    }

    /** Карточка документа. */
    @GetMapping("/{id}")
    public DocumentDto get(@PathVariable Long id, Authentication auth) {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        Document fresh = documentRepository.findByIdWithSignatures(doc.getId()).orElse(doc);
        return mapper.document(fresh, gosklyuchService.latestOrder(id).orElse(null));
    }

    // ═══════════════════════════════════════════════════════
    //  Загрузка / удаление
    // ═══════════════════════════════════════════════════════

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDto upload(@RequestPart("file") MultipartFile file,
                              @RequestParam(required = false) Long folderId,
                              Authentication auth) throws IOException {
        User user = currentUser.entity(auth);
        requireConsent(user);
        DocumentFolder folder = folderId != null
                ? folderService.getOwned(folderId, user)
                : folderService.ensureDefault(user);
        Document doc = documentService.upload(file, user, folder);
        audit.documentUpload(user.getEmail(), doc.getId(), doc.getOriginalName());
        return mapper.document(doc);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication auth) throws IOException {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        documentService.delete(id, user);
        audit.documentDelete(user.getEmail(), id, doc.getOriginalName(), doc.isSigned());
    }

    // ═══════════════════════════════════════════════════════
    //  Содержимое
    // ═══════════════════════════════════════════════════════

    /** Инлайн-просмотр (PDF в iframe). */
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable Long id, Authentication auth)
            throws IOException {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        Resource r = documentService.loadAsResource(doc);
        MediaType mt = doc.getContentType() != null
                ? MediaType.parseMediaType(doc.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        audit.event("DOC_VIEW", RESULT_SUCCESS, user.getEmail(), TARGET_DOCUMENT,
                String.valueOf(id), doc.getOriginalName(), "Просмотр документа");
        return ResponseEntity.ok()
                .contentType(mt)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.inline(doc.getOriginalName()))
                .body(r);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id, Authentication auth)
            throws IOException {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        Resource r = documentService.loadAsResource(doc);
        audit.event("DOC_DOWNLOAD", RESULT_SUCCESS, user.getEmail(), TARGET_DOCUMENT,
                String.valueOf(id), doc.getOriginalName(), "Скачивание документа");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.attachment(doc.getOriginalName()))
                .body(r);
    }

    /** Скачать .sig основной подписи. */
    @GetMapping("/{id}/signature")
    public ResponseEntity<byte[]> signature(@PathVariable Long id, Authentication auth) {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        String signature = documentService.primarySignatureBase64(doc);
        if (!doc.isSigned() || signature == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Подпись отсутствует");
        }
        audit.event("SIG_DOWNLOAD", RESULT_SUCCESS, user.getEmail(), TARGET_DOCUMENT,
                String.valueOf(id), doc.getOriginalName(), "Скачивание подписи");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.attachment(doc.getOriginalName() + ".sig"))
                .body(Base64.getDecoder().decode(signature.replaceAll("\\s+", "")));
    }

    /** Скачать конкретную подпись из истории. */
    @GetMapping("/{id}/signatures/{sigId}/download")
    public ResponseEntity<byte[]> signatureById(@PathVariable Long id, @PathVariable Long sigId,
                                                Authentication auth) {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        DocumentSignature sig = documentService.getSignature(sigId);
        if (!sig.getDocument().getId().equals(doc.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Подпись не относится к документу");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.attachment(doc.getOriginalName() + ".sig"))
                .body(Base64.getDecoder().decode(sig.getSignatureBase64().replaceAll("\\s+", "")));
    }

    /** Документ + подпись одним ZIP. */
    @GetMapping("/{id}/download-signed")
    public ResponseEntity<byte[]> downloadSigned(@PathVariable Long id, Authentication auth)
            throws IOException {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        String signature = documentService.primarySignatureBase64(doc);
        if (!doc.isSigned() || signature == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Подпись отсутствует");
        }
        byte[] docBytes = documentService.getBytes(doc);
        byte[] sigBytes = Base64.getDecoder().decode(signature.replaceAll("\\s+", ""));
        String safeName = FileNames.sanitizeZipEntry(doc.getOriginalName());
        String base = stripExtension(safeName);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Set<String> used = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            addEntry(zip, uniqueEntryName(safeName, used), docBytes);
            addEntry(zip, uniqueEntryName(base + ".sig", used), sigBytes);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.attachment(base + "_signed.zip"))
                .body(baos.toByteArray());
    }

    private static void addEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    // ═══════════════════════════════════════════════════════
    //  Подписание (отсоединённая CAdES-BES от плагина КриптоПро)
    // ═══════════════════════════════════════════════════════

    /** Подписать документ подписью из браузерного плагина КриптоПро. */
    @PostMapping("/{id}/sign")
    public Map<String, Object> sign(@PathVariable Long id, @RequestBody SignRequest req,
                                    Authentication auth) {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        if (documentService.hasSignatureByUser(doc.getId(), user.getId())) {
            audit.signFail(user.getEmail(), doc.getId(), "Повторное подписание");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Вы уже подписали этот документ. Повторное подписание невозможно.");
        }
        return acceptSignature(doc, user, req.getSignatureBase64());
    }

    /** Пакетное подписание: несколько документов одним запросом. */
    @PostMapping("/sign-batch")
    public Map<String, Object> signBatch(@RequestBody List<SignRequest> requests,
                                         Authentication auth) {
        User user = currentUser.entity(auth);
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("Список документов пуст");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        int fail = 0;
        for (SignRequest req : requests) {
            Long id = req.getDocumentId();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("documentId", id);
            try {
                if (req.getSignatureBase64() == null || req.getSignatureBase64().isBlank()) {
                    throw new IllegalArgumentException("Пустая подпись");
                }
                Document doc = documentService.getOwned(id, user);
                if (documentService.hasSignatureByUser(doc.getId(), user.getId())) {
                    r.put("ok", false);
                    r.put("error", "Уже подписан");
                    fail++;
                } else {
                    acceptSignature(doc, user, req.getSignatureBase64());
                    r.put("ok", true);
                    ok++;
                }
            } catch (IllegalArgumentException e) {
                r.put("ok", false);
                r.put("error", e.getMessage());
                fail++;
            } catch (Exception e) {
                r.put("ok", false);
                r.put("error", "Ошибка: " + e.getMessage());
                fail++;
            }
            results.add(r);
        }
        return Map.of("ok", ok, "fail", fail, "total", requests.size(), "results", results);
    }

    /** Загрузка отсоединённой подписи файлом (.sig) — например, подписанной в другом месте. */
    @PostMapping(value = "/{id}/signature", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadSignature(@PathVariable Long id,
                                               @RequestPart("file") MultipartFile file,
                                               Authentication auth) throws IOException {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Файл подписи пустой");
        }
        String sigBase64 = Base64.getEncoder().encodeToString(file.getBytes());
        ensureCrls(sigBase64, id, user.getEmail());

        byte[] content = documentService.getBytes(doc);
        Map<String, Object> result;
        try {
            result = attachmentRequireChain
                    ? signatureVerifier.verifyDetached(content, sigBase64)
                    : signatureVerifier.verifyDetachedBasic(content, sigBase64);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Подпись не принята: " + e.getMessage());
        }

        String subject = String.valueOf(result.getOrDefault("signerSubject", "—"));
        String serial = String.valueOf(result.getOrDefault("signerSerial", "—"));
        documentService.addSignature(id, user, sigBase64, subject, serial);
        documentNotifyService.documentSigned(doc, user);
        if (consentService.isConsentDocument(user, doc)) {
            consentService.markSigned(user);
        }
        audit.event("DOC_UPLOAD_SIG", RESULT_SUCCESS, user.getEmail(),
                TARGET_DOCUMENT, String.valueOf(id), subject, "client upload .sig");
        regenerateQuietly(doc);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", true);
        body.put("signerSubject", subject);
        body.put("signerSerial", serial);
        String warning = PersonData.nameMismatch(user.getFullName(), subject);
        if (warning != null) body.put("warning", warning);
        return body;
    }

    /** Применить/отключить доверенность к подписи. */
    @PostMapping("/{id}/signatures/{sigId}/poa")
    public Map<String, Object> applyPoa(@PathVariable Long id, @PathVariable Long sigId,
                                        @RequestParam boolean apply, Authentication auth) {
        User user = currentUser.entity(auth);
        documentService.getOwned(id, user);
        // Подписи нужны вместе с документом: вне транзакции ленивая коллекция недоступна
        Document doc = documentRepository.findByIdWithSignatures(id)
                .orElseThrow(() -> new ru.afina.error.NotFoundException("Документ не найден"));
        boolean belongs = doc.getSignatures().stream()
                .anyMatch(s -> s.getId().equals(sigId));
        if (!belongs) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Подпись не относится к документу");
        }
        signatureRepository.updateApplyPoa(sigId, apply);
        audit.event("SIG_APPLY_POA", RESULT_SUCCESS, user.getEmail(), "SIGNATURE",
                String.valueOf(sigId), null,
                apply ? "Доверенность применена (client)" : "Доверенность отключена (client)");
        documentRepository.findByIdWithSignatures(id).ifPresent(this::regenerateQuietly);
        return Map.of("applyPoa", apply);
    }

    // ═══════════════════════════════════════════════════════
    //  Визуализация, справка, публичная ссылка
    // ═══════════════════════════════════════════════════════

    /** PDF-визуализация штампов подписи. */
    @GetMapping("/{id}/visualization")
    public ResponseEntity<byte[]> visualization(@PathVariable Long id,
                                                @RequestParam(defaultValue = "false") boolean regen,
                                                Authentication auth) throws IOException {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        byte[] bytes;
        var vis = visualizationService.findFor(doc);
        if (vis.isPresent() && !regen) {
            bytes = visualizationService.loadBytes(vis.get());
        } else {
            bytes = visualizationService.loadBytes(visualizationService.generate(doc));
            audit.event("VISUALIZATION_GENERATE", RESULT_SUCCESS, user.getEmail(),
                    TARGET_DOCUMENT, String.valueOf(id), null, "client");
        }
        String fileName = "ВИЗУАЛИЗАЦИЯ_" + stripExtension(doc.getOriginalName()) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, FileNames.attachment(fileName))
                .body(bytes);
    }

    /** Пересоздать визуализацию (например, после ручной проверки). */
    @PostMapping("/{id}/visualization")
    public Map<String, Object> regenerate(@PathVariable Long id, Authentication auth)
            throws IOException {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        var vis = visualizationService.generate(doc);
        return Map.of("version", vis.getVersion(), "size", vis.getSize());
    }

    /** Справка о подписании (PDF). */
    @GetMapping("/{id}/signing-certificate")
    public ResponseEntity<byte[]> signingCertificate(@PathVariable Long id, Authentication auth)
            throws IOException {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        byte[] pdf = certificateService.generate(id);
        String fileName = "СПРАВКА_" + stripExtension(doc.getOriginalName()) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, FileNames.attachment(fileName))
                .body(pdf);
    }

    /** Публичная ссылка для проверки подписи (QR-код). */
    @GetMapping("/{id}/public-link")
    public Map<String, Object> publicLink(@PathVariable Long id, Authentication auth,
                                          @Value("${app.base-url}") String baseUrl) {
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        String url = baseUrl + "/verify/" + doc.getPublicUuid();
        return Map.of("uuid", doc.getPublicUuid().toString(), "url", url);
    }

    // ═══════════════════════════════════════════════════════
    //  Госключ
    // ═══════════════════════════════════════════════════════

    /** Отправить документ на подпись в мобильное приложение «Госключ». */
    @PostMapping("/{id}/gosklyuch")
    public GosklyuchOrderDto sendToGosklyuch(@PathVariable Long id, Authentication auth) {
        if (!gosklyuchService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Интеграция выключена");
        }
        User user = currentUser.entity(auth);
        Document doc = documentService.getOwned(id, user);
        var order = gosklyuchService.submit(doc, user);
        return mapper.order(order);
    }

    /** Отправить пакетом все неподписанные документы раздела. */
    @PostMapping("/folders/{folderId}/gosklyuch")
    public Map<String, Object> sendPackage(@PathVariable Long folderId, Authentication auth) {
        if (!gosklyuchService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Интеграция выключена");
        }
        User user = currentUser.entity(auth);
        DocumentFolder folder = folderService.getOwned(folderId, user);
        List<Document> docs = documentRepository
                .findAllByFolderOrderByUploadedAtDesc(folder).stream()
                .filter(d -> !d.isSigned())
                .filter(d -> !gosklyuchService.hasPendingOrder(d.getId()))
                .limit(gosklyuchService.maxDocuments())
                .toList();
        if (docs.isEmpty()) {
            throw new IllegalStateException("Нет неподписанных документов для отправки");
        }
        var order = gosklyuchService.submitAll(docs, user);
        return Map.of("orderId", order.getId(), "documents", docs.size());
    }

    // ═══════════════════════════════════════════════════════
    //  Внутреннее
    // ═══════════════════════════════════════════════════════

    private Map<String, Object> acceptSignature(Document doc, User signer, String sig) {
        if (sig == null || sig.isBlank()) {
            throw new IllegalArgumentException("Пустая подпись");
        }
        try {
            ensureCrls(sig, doc.getId(), signer.getEmail());
            Map<String, Object> result = signatureVerifier.verifyDetached(
                    documentService.getBytes(doc), sig);
            String subject = String.valueOf(result.getOrDefault("signerSubject", ""));
            String serial = String.valueOf(result.getOrDefault("signerSerial", ""));
            documentService.addSignature(doc.getId(), signer, sig, subject, serial);
            documentNotifyService.documentSigned(doc, signer);
            audit.signSuccess(signer.getEmail(), doc.getId(),
                    subject + " (" + signer.getRole().name() + ")");

            if (consentService.isConsentDocument(signer, doc)) {
                consentService.markSigned(signer);
            }
            regenerateQuietly(doc);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("valid", true);
            body.put("signersCount", result.get("signersCount"));
            body.put("signersInfo", result.get("signersInfo"));
            if (signer.getRole() == ru.afina.entity.Role.ROLE_USER) {
                String warning = PersonData.nameMismatch(signer.getFullName(), subject);
                if (warning != null) body.put("warning", warning);
            }
            return body;
        } catch (IllegalArgumentException e) {
            audit.signFail(signer.getEmail(), doc.getId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            audit.signFail(signer.getEmail(), doc.getId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Ошибка проверки подписи: " + e.getMessage());
        }
    }

    /** CRL по CDP сертификатов подписи — best-effort, не блокирует подписание. */
    private void ensureCrls(String sigBase64, Long docId, String actor) {
        try {
            int count = crlOnDemandService.ensureForSignature(sigBase64, docId, actor);
            if (count > 0) {
                log.info("CRL downloaded for .sig: {} (doc {})", count, docId);
            }
        } catch (Exception e) {
            log.warn("CRL download failed before .sig upload: {}", e.getMessage());
        }
    }

    private void regenerateQuietly(Document doc) {
        try {
            visualizationService.generate(doc);
        } catch (Exception e) {
            log.debug("Визуализация не создана для документа {}: {}", doc.getId(), e.getMessage());
        }
    }

    private void requireConsent(User user) {
        if (consentService.needsProfile(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Заполните профиль (ФИО и телефон) — без этого нельзя загружать документы");
        }
        if (!consentService.consentSigned(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Подпишите согласие на обработку персональных данных");
        }
    }

    static String stripExtension(String name) {
        if (name == null) return "file";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String uniqueEntryName(String desired, Set<String> used) {
        String name = (desired == null || desired.isBlank()) ? "file" : desired;
        if (used.add(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        String candidate;
        do {
            candidate = base + "_" + i + ext;
            i++;
        } while (!used.add(candidate));
        return candidate;
    }

    /** Обновление статусов незавершённых заявок Госключа (используется планировщиком). */
    @GetMapping("/gosklyuch/orders")
    public List<GosklyuchOrderDto> orders(Authentication auth,
                                          @RequestParam(defaultValue = "50") int limit) {
        User user = currentUser.entity(auth);
        return gosklyuchService.recentForOwner(user.getId(), Math.min(limit, 200))
                .stream().map(mapper::order).toList();
    }

    /** Заявки по документу (история отправок). */
    @GetMapping("/{id}/gosklyuch/orders")
    public List<GosklyuchOrderDto> documentOrders(@PathVariable Long id, Authentication auth) {
        User user = currentUser.entity(auth);
        documentService.getOwned(id, user);
        return gosklyuchService.ordersForDocument(id).stream().map(mapper::order).toList();
    }

    /** Текущее время сервера — нужно фронтенду для синхронизации часов. */
    @GetMapping("/server-time")
    public Map<String, String> serverTime() {
        return Map.of("now", Instant.now().toString());
    }

    /** Сортировка по дате загрузки (убывание) — используется в тестах. */
    static Comparator<Document> newestFirst() {
        return Comparator.comparing(Document::getUploadedAt).reversed();
    }
}
