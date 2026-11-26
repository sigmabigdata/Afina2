package ru.afina.gosklyuch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ru.afina.entity.Document;
import ru.afina.entity.GosklyuchOrder;
import ru.afina.entity.GosklyuchOrderItem;
import ru.afina.entity.User;
import ru.afina.gosklyuch.client.GosklyuchClient;
import ru.afina.gosklyuch.client.GosklyuchOrderMeta;
import ru.afina.gosklyuch.model.GosklyuchAttribute;
import ru.afina.gosklyuch.model.GosklyuchDocument;
import ru.afina.gosklyuch.model.GosklyuchSignRequest;
import ru.afina.repository.GosklyuchOrderRepository;
import ru.afina.repository.UserRepository;
import ru.afina.service.AuditService;
import ru.afina.service.ConsentService;
import ru.afina.service.CrlOnDemandService;
import ru.afina.service.DocumentService;
import ru.afina.service.EmailService;
import ru.afina.service.SignatureVisualizationService;
import ru.afina.service.SignatureVerifier;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Отправка документов на подписание в МП «Госключ» и обработка результата.
 *
 * <p>Сервис полностью управляется конфигурацией инсталляции:</p>
 * <ul>
 *   <li>{@code app.gosklyuch.enabled=false} (по умолчанию) — отправка запрещена,
 *       внешние вызовы не выполняются;</li>
 *   <li>{@code app.gosklyuch.provider} — {@code disabled} | {@code fake} | {@code epgu};</li>
 *   <li>пока не настроен подписант пакета УКЭП ({@link GosklyuchSigner}),
 *       отправка запрещена: незаверенный архив в ЕПГУ не уходит.</li>
 * </ul>
 */
@Service
public class GosklyuchService {

    private static final Logger log = LoggerFactory.getLogger(GosklyuchService.class);

    private static final String PROVIDER_DISABLED = "disabled";
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_FAIL = "FAIL";
    private static final String TARGET_DOCUMENT = "DOCUMENT";
    private static final String TARGET_GOSKLYUCH = "GOSKLYUCH";
    private static final int MAX_ERROR = 500;

    private final GosklyuchProperties properties;
    private final GosklyuchOrderRepository orderRepository;
    private final UserRepository userRepository;
    private final DocumentService documentService;
    private final ConsentService consentService;
    private final SignatureVerifier signatureVerifier;
    private final CrlOnDemandService crlOnDemandService;
    private final EmailService emailService;
    private final SignatureVisualizationService visualizationService;
    private final AuditService audit;
    private final GosklyuchRequestXmlBuilder xmlBuilder;
    private final GosklyuchPackageBuilder packageBuilder;
    private final GosklyuchClient client;
    private final GosklyuchSigner signer;

    @org.springframework.beans.factory.annotation.Value("${app.base-url:}")
    private String baseUrl = "";

    @SuppressWarnings("java:S107") // сервис агрегирует доменные зависимости
    public GosklyuchService(GosklyuchProperties properties,
                            GosklyuchOrderRepository orderRepository,
                            UserRepository userRepository,
                            DocumentService documentService,
                            ConsentService consentService,
                            SignatureVerifier signatureVerifier,
                            CrlOnDemandService crlOnDemandService,
                            EmailService emailService,
                            SignatureVisualizationService visualizationService,
                            AuditService audit,
                            GosklyuchRequestXmlBuilder xmlBuilder,
                            GosklyuchPackageBuilder packageBuilder,
                            GosklyuchClient client,
                            GosklyuchSigner signer) {
        this.properties = properties;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.documentService = documentService;
        this.consentService = consentService;
        this.signatureVerifier = signatureVerifier;
        this.crlOnDemandService = crlOnDemandService;
        this.emailService = emailService;
        this.visualizationService = visualizationService;
        this.audit = audit;
        this.xmlBuilder = xmlBuilder;
        this.packageBuilder = packageBuilder;
        this.client = client;
        this.signer = signer;
    }

    /** Интеграция включена и провайдер выбран (кнопка в интерфейсе). */
    public boolean isEnabled() {
        return properties.isEnabled() && providerConfigured();
    }

    /** Можно ли реально отправить документ: включено и настроен подписант пакета. */
    public boolean isReadyToSend() {
        return isEnabled() && !(signer instanceof GosklyuchSignerUnavailable);
    }

    private boolean providerConfigured() {
        String provider = properties.getProvider();
        return provider != null && !provider.isBlank()
                && !PROVIDER_DISABLED.equalsIgnoreCase(provider);
    }

    /** Последняя заявка по документу (для отображения статуса). */
    public Optional<GosklyuchOrder> latestOrder(Long documentId) {
        if (documentId == null) return Optional.empty();
        return orderRepository.findTopByDocumentIdOrderByIdDesc(documentId);
    }

    /** Все заявки по документу, новые первыми (история отправок в карточке). */
    public List<GosklyuchOrder> ordersForDocument(Long documentId) {
        if (documentId == null) return List.of();
        return orderRepository.findByDocumentIdOrderByIdDesc(documentId);
    }

    /**
     * Последние заявки клиента для карточки клиента (только при включённой интеграции).
     */
    public List<GosklyuchOrder> recentForOwner(Long ownerId, int limit) {
        if (!isEnabled() || ownerId == null || limit < 1) return List.of();
        return orderRepository.findRecentByOwner(ownerId, PageRequest.of(0, limit));
    }

    /**
     * Последняя заявка по каждому документу раздела (documentId → заявка).
     * При выключенной интеграции запросов к БД не делаем.
     */
    public Map<Long, GosklyuchOrder> latestByDocumentIds(List<Long> documentIds) {
        if (!isEnabled() || documentIds == null || documentIds.isEmpty()) return Map.of();
        Map<Long, GosklyuchOrder> latest = new LinkedHashMap<>();
        for (GosklyuchOrder order : orderRepository.findByDocumentIds(documentIds)) {
            if (order.getDocument() != null) {
                latest.putIfAbsent(order.getDocument().getId(), order);
            }
            for (GosklyuchOrderItem item : order.getItems()) {
                if (item.getDocument() != null) {
                    latest.putIfAbsent(item.getDocument().getId(), order);
                }
            }
        }
        return latest;
    }

    /** Есть ли незавершённая заявка по документу (включая пакетные). */
    public boolean hasPendingOrder(Long documentId) {
        if (documentId == null) return false;
        return orderRepository.countPendingForDocument(documentId,
                List.of(GosklyuchStatus.NEW, GosklyuchStatus.SENT)) > 0;
    }

    /** Максимум документов в заявке (спецификация — 15). */
    public int maxDocuments() {
        return Math.min(properties.getMaxDocuments(),
                GosklyuchRequestXmlBuilder.MAX_DOCUMENTS);
    }

    /** Заявки для админки (с документом и владельцем), фильтр по статусу. */
    public List<GosklyuchOrder> adminOrders(GosklyuchStatus status, int limit) {
        if (!isEnabled()) return List.of();
        return orderRepository.findForAdmin(status, PageRequest.of(0, limit));
    }

    /** Заявка с документом и владельцем (для страницы/действий). */
    public Optional<GosklyuchOrder> findOrder(Long id) {
        if (id == null) return Optional.empty();
        return orderRepository.findWithDocumentAndOwnerById(id);
    }

    /** Обновить статус одной заявки по кнопке (без ожидания планировщика). */
    public void refreshOrder(Long id) {
        GosklyuchOrder order = orderRepository.findWithDocumentAndOwnerById(id)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена"));
        if (order.getExternalOrderId() == null) {
            throw new IllegalArgumentException("У заявки нет внешнего номера ЕПГУ");
        }
        refresh(order);
    }

    /**
     * Ручное применение подписи, полученной от Госключа, к документу.
     *
     * @return true — подпись проверена и приложена
     */
    public boolean applyStoredSignature(Long orderId, User actor) {
        requireReady();
        GosklyuchOrder order = orderRepository.findWithDocumentAndOwnerById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена"));
        if (order.getStatus() != GosklyuchStatus.SIGNED) {
            throw new IllegalArgumentException(
                    "Подпись можно применить только к заявке со статусом «подписано»");
        }
        if (order.getSignatureBase64() == null && order.getItems().stream()
                .noneMatch(i -> i.getSignatureBase64() != null)) {
            throw new IllegalArgumentException("В заявке нет сохранённой подписи");
        }

        List<GosklyuchOrderItem> items = order.getItems();
        if (items.isEmpty()) {
            GosklyuchOrderItem legacy = new GosklyuchOrderItem();
            legacy.setOrder(order);
            legacy.setDocument(order.getDocument());
            legacy.setSignatureBase64(order.getSignatureBase64());
            items = List.of(legacy);
        }

        int applied = 0;
        int failed = 0;
        for (GosklyuchOrderItem item : items) {
            String base64 = item.getSignatureBase64() != null
                    ? item.getSignatureBase64() : order.getSignatureBase64();
            if (base64 == null || base64.isBlank() || item.isApplied()) continue;
            Document doc = item.getDocument();
            boolean already = documentService.listSignatures(doc.getId()).stream()
                    .anyMatch(s -> base64.equals(s.getSignatureBase64()));
            if (already) {
                item.setApplied(true);
                continue;
            }
            if (applyToDocument(order, doc, base64, item)) {
                applied++;
            } else {
                failed++;
            }
        }
        orderRepository.save(order);
        if (applied == 0 && failed == 0) {
            throw new IllegalArgumentException("Все подписи уже применены к документам");
        }
        if (applied > 0) {
            audit.event("GOSKLYUCH_APPLY_MANUAL", RESULT_SUCCESS, actorEmail(actor),
                    TARGET_GOSKLYUCH, String.valueOf(order.getId()),
                    "orderId=" + order.getExternalOrderId(), "применено: " + applied);
        }
        return failed == 0;
    }

    /**
     * Отправить документ на подписание в Госключ.
     */
    public GosklyuchOrder submit(Document document, User actor) {
        return submitAll(List.of(document), actor);
    }

    /**
     * Отправить пакет документов одной заявкой (до 15, спецификация Госключа).
     * Все документы должны принадлежать одному клиенту.
     */
    public GosklyuchOrder submitAll(List<Document> documents, User actor) {
        requireReady();
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("Не выбраны документы для отправки");
        }

        List<Document> docs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Document d : documents) {
            if (d == null || d.getId() == null || !seen.add(d.getId())) continue;
            docs.add(documentService.getById(d.getId()));
        }
        if (docs.isEmpty()) {
            throw new IllegalArgumentException("Не выбраны документы для отправки");
        }
        if (docs.size() > maxDocuments()) {
            throw new IllegalArgumentException(
                    "В одной заявке допускается не более "
                            + maxDocuments() + " документов");
        }

        User owner = loadOwner(docs.get(0));
        if (owner == null) {
            throw new IllegalArgumentException("Владелец документа не найден");
        }
        String snils = GosklyuchSnils.digits(owner.getSnils());
        if (snils == null) {
            throw new IllegalArgumentException(
                    "У клиента не указан СНИЛС — отправка в Госключ невозможна");
        }
        if (!GosklyuchSnils.isValid(snils)) {
            throw new IllegalArgumentException(
                    "СНИЛС клиента не проходит проверку контрольного числа");
        }
        Long ownerId = owner.getId();
        for (Document doc : docs) {
            if (doc.isSigned()) {
                throw new IllegalArgumentException(
                        "Документ уже подписан: " + doc.getOriginalName());
            }
            User docOwner = doc.getOwner();
            if (docOwner == null || docOwner.getId() == null
                    || !docOwner.getId().equals(ownerId)) {
                throw new IllegalArgumentException(
                        "Все документы одной заявки должны принадлежать одному клиенту");
            }
            ensureNoPendingOrder(doc.getId());
        }

        Instant expiration = Instant.now().plus(properties.getSignExpiration());
        List<GosklyuchDocument> specs = new ArrayList<>();
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, Document> byName = new LinkedHashMap<>();
        for (Document doc : docs) {
            String fileName = GosklyuchFileTypes.archiveFileName(doc);
            String mime = GosklyuchFileTypes.mimeType(doc);
            if (files.containsKey(fileName)) {
                throw new IllegalArgumentException(
                        "Повторяющееся имя файла в заявке: " + fileName);
            }
            files.put(fileName, readBytes(doc));
            byName.put(fileName, doc);
            specs.add(documentSpec(doc, fileName, mime, expiration));
        }
        byte[] requestXml = xmlBuilder.build(
                new GosklyuchSignRequest(GosklyuchSnils.formatted(snils), null, specs),
                isUkep());
        byte[] zip = packageBuilder.build(requestXml, files);

        GosklyuchOrder order = new GosklyuchOrder();
        order.setDocument(docs.get(0));
        order.setOwner(owner);
        order.setSignerSnils(snils);
        order.setStatus(GosklyuchStatus.NEW);
        for (Map.Entry<String, Document> e : byName.entrySet()) {
            GosklyuchOrderItem item = new GosklyuchOrderItem();
            item.setOrder(order);
            item.setDocument(e.getValue());
            item.setFileName(e.getKey());
            order.getItems().add(item);
        }
        order = orderRepository.save(order);

        long externalId;
        try {
            externalId = client.createOrder(meta());
            order.setExternalOrderId(externalId);
            order = orderRepository.save(order);
            client.pushPackage(externalId, zip, meta());
        } catch (RuntimeException e) {
            markError(order, e);
            audit.event("GOSKLYUCH_SUBMIT", RESULT_FAIL, actorEmail(actor),
                    TARGET_DOCUMENT, String.valueOf(docs.get(0).getId()),
                    docs.get(0).getOriginalName(),
                    "docs=" + docs.size() + ", " + e.getMessage());
            throw e;
        }

        order.setStatus(GosklyuchStatus.SENT);
        order.setStatusCode(17);
        order.setStatusName("Заявление в очереди на отправку");
        order = orderRepository.save(order);
        audit.event("GOSKLYUCH_SUBMIT", RESULT_SUCCESS, actorEmail(actor),
                TARGET_DOCUMENT, String.valueOf(docs.get(0).getId()),
                docs.get(0).getOriginalName(),
                "docs=" + docs.size() + ", orderId=" + externalId);
        log.info("Госключ: отправлено документов {}, orderId={}", docs.size(), externalId);
        return order;
    }

    /**
     * Обновить статус заявки по данным ЕПГУ и при необходимости применить
     * полученную подпись к документу.
     */
    public void refresh(GosklyuchOrder order) {
        if (order == null || order.getExternalOrderId() == null) return;
        if (order.getStatus() != null && order.getStatus().isTerminal()) return;

        var info = client.getOrder(order.getExternalOrderId());
        GosklyuchStatus status = GosklyuchStatus.fromCode(info.statusCode());
        order.setStatusCode(info.statusCode());
        order.setStatusName(truncate(info.statusName(), 100));
        order.setStatus(status);

        if (status == GosklyuchStatus.SIGNED) {
            handleSigned(order, info);
        } else if (status.isTerminal()) {
            order.finish(status, Instant.now());
            order.setError(statusNameOrDefault(info.statusName(), status));
            orderRepository.save(order);
            audit.event("GOSKLYUCH_STATUS", RESULT_FAIL, null,
                    TARGET_GOSKLYUCH, String.valueOf(order.getId()),
                    "orderId=" + order.getExternalOrderId(), order.getError());
            notifyOwner(order, status);
        } else {
            orderRepository.save(order);
        }
    }

    /** Опросить все незавершённые заявки; возвращает число обработанных. */
    public int refreshPending() {
        if (!isEnabled() || !providerConfigured()) return 0;
        List<GosklyuchOrder> pending = orderRepository.findPendingWithDocument(
                List.of(GosklyuchStatus.NEW, GosklyuchStatus.SENT));
        int processed = 0;
        for (GosklyuchOrder order : pending) {
            if (order.getExternalOrderId() == null) continue;
            try {
                refresh(order);
                processed++;
            } catch (RuntimeException e) {
                log.warn("Госключ: не удалось обновить заявку {}: {}",
                        order.getId(), e.getMessage());
            }
        }
        return processed;
    }

    // ── внутреннее ──────────────────────────────────────────

    /** Владелец документа: загружается отдельно, чтобы не зависеть от открытой сессии. */
    private User loadOwner(Document doc) {
        if (doc.getOwner() == null) return null;
        Long ownerId = doc.getOwner().getId();
        return ownerId == null ? null : userRepository.findById(ownerId).orElse(null);
    }

    private void requireReady() {
        if (!properties.isEnabled()) {
            throw new GosklyuchUnavailableException(
                    "Интеграция с Госключом выключена (app.gosklyuch.enabled=false)");
        }
        if (!providerConfigured()) {
            throw new GosklyuchUnavailableException(
                    "Провайдер Госключа не выбран (app.gosklyuch.provider=disabled)");
        }
        if (signer instanceof GosklyuchSignerUnavailable) {
            throw new GosklyuchUnavailableException(GosklyuchSignerUnavailable.MESSAGE);
        }
    }

    private void ensureNoPendingOrder(Long documentId) {
        long pending = orderRepository.countPendingForDocument(documentId,
                List.of(GosklyuchStatus.NEW, GosklyuchStatus.SENT));
        if (pending > 0) {
            throw new IllegalArgumentException(
                    "По документу уже есть заявка, ожидающая подписания в Госключе");
        }
    }

    private GosklyuchDocument documentSpec(Document doc, String fileName,
                                          String mime, Instant expiration) {
        List<GosklyuchAttribute> attributes = new ArrayList<>();
        addAttribute(attributes, "mnemonics", properties.getMnemonic());
        addAttribute(attributes, "orgName", properties.getOrgName());
        addAttribute(attributes, "serviceName", properties.getServiceName());
        return new GosklyuchDocument(
                fileName,
                mime,
                truncate(descriptionOf(doc), 250),
                blankToNull(properties.getBacklink()),
                expiration,
                attributes);
    }

    private static void addAttribute(List<GosklyuchAttribute> target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.add(new GosklyuchAttribute(name, truncate(value.trim(), 250)));
        }
    }

    private static String descriptionOf(Document doc) {
        String name = doc.getOriginalName();
        return name == null || name.isBlank() ? "Документ" : name.trim();
    }

    private GosklyuchOrderMeta meta() {
        return new GosklyuchOrderMeta(
                nullToEmpty(properties.getRegion()),
                nullToEmpty(properties.getServiceCode()),
                nullToEmpty(properties.getTargetCode()));
    }

    private boolean isUkep() {
        return "UKEP".equalsIgnoreCase(properties.getSignatureType());
    }

    private void handleSigned(GosklyuchOrder order,
                              ru.afina.gosklyuch.client.GosklyuchOrderInfo info) {
        order.setStatusCode(info.statusCode());
        order.setStatusName(truncate(info.statusName(), 100));

        List<GosklyuchOrderItem> items = order.getItems();
        if (items.isEmpty() && order.getDocument() != null) {
            GosklyuchOrderItem legacy = new GosklyuchOrderItem();
            legacy.setOrder(order);
            legacy.setDocument(order.getDocument());
            legacy.setFileName(GosklyuchFileTypes.archiveFileName(order.getDocument()));
            items = List.of(legacy);
        }

        boolean anySignature = false;
        for (GosklyuchOrderItem item : items) {
            String expected = item.getFileName() != null
                    ? item.getFileName()
                    : GosklyuchFileTypes.archiveFileName(item.getDocument());
            var sigFile = info.files().stream()
                    .filter(f -> expected.equals(stripSig(f.fileName())))
                    .findFirst()
                    .orElse(null);
            if (sigFile == null) {
                log.warn("Госключ: заявка {} — .sig для {} не найден",
                        order.getId(), expected);
                continue;
            }
            byte[] raw = client.downloadFile(order.getExternalOrderId(), sigFile);
            String base64 = Base64.getEncoder().encodeToString(raw);
            item.setSignatureBase64(base64);
            if (order.getSignatureBase64() == null) {
                order.setSignatureBase64(base64);
            }
            anySignature = true;
            if (properties.isApplySignature()) {
                applyToDocument(order, item.getDocument(), base64, item);
            }
        }

        order.finish(GosklyuchStatus.SIGNED, Instant.now());
        if (!anySignature) {
            order.setError("Подпись получена, но файлы подписи не найдены в ответе ЕПГУ");
        }
        orderRepository.save(order);

        audit.event("GOSKLYUCH_STATUS", RESULT_SUCCESS, null,
                TARGET_GOSKLYUCH, String.valueOf(order.getId()),
                "orderId=" + order.getExternalOrderId(),
                "подписей получено: " + items.stream()
                        .filter(i -> i.getSignatureBase64() != null).count());
        if (!properties.isApplySignature()) {
            log.info("Госключ: подписи заявки {} сохранены; применение к документам "
                    + "выключено (app.gosklyuch.apply-signature=false)", order.getId());
        }
        notifyOwner(order, GosklyuchStatus.SIGNED);
    }

    /** Письмо клиенту о смене статуса заявки (если включено конфигурацией). */
    private void notifyOwner(GosklyuchOrder order, GosklyuchStatus status) {
        if (!properties.isNotifyEnabled()) return;
        User owner = order.getOwner();
        if (owner == null || owner.getEmail() == null || owner.getEmail().isBlank()) return;
        String docName;
        if (order.getItems().size() == 1
                && order.getItems().get(0).getDocument() != null) {
            docName = order.getItems().get(0).getDocument().getOriginalName();
        } else if (!order.getItems().isEmpty()) {
            docName = "документов: " + order.getItems().size();
        } else {
            docName = order.getDocument() != null
                    ? order.getDocument().getOriginalName() : "документ";
        }
        String label = switch (status) {
            case SIGNED -> "подписан";
            case REJECTED -> "отклонён подписантом";
            case EXPIRED -> "истёк срок подписания";
            case ERROR -> "ошибка обработки заявки";
            default -> "обновлён";
        };
        String statusText = order.getStatusName() != null && !order.getStatusName().isBlank()
                ? order.getStatusName() : label;
        String body = "Документ: " + docName + "\n"
                + "Статус: " + statusText + "\n\n"
                + "Личный кабинет: " + baseUrl + "/dashboard";
        try {
            emailService.sendAlert(owner.getEmail(),
                    "Афина · Госключ: " + label, body);
        } catch (Exception e) {
            log.warn("Госключ: не удалось отправить уведомление клиенту: {}", e.getMessage());
        }
    }

    private boolean applyToDocument(GosklyuchOrder order, Document doc,
                                    String signatureBase64, GosklyuchOrderItem item) {
        try {
            try {
                crlOnDemandService.ensureForSignature(signatureBase64, doc.getId(), null);
            } catch (Exception crlEx) {
                log.warn("Госключ: CRL download before verify failed for doc {}: {}",
                        doc.getId(), crlEx.getMessage());
            }
            var result = signatureVerifier.verifyDetached(
                    documentService.getBytes(doc), signatureBase64);
            String subject = String.valueOf(result.getOrDefault("signerSubject", "—"));
            String serial = String.valueOf(result.getOrDefault("signerSerial", "—"));
            documentService.addSignature(doc.getId(), null, signatureBase64, subject, serial);
            if (consentService.isConsentDocument(order.getOwner(), doc)) {
                consentService.markSigned(order.getOwner());
            }
            try {
                visualizationService.generate(doc);
            } catch (Exception e) {
                log.warn("Госключ: визуализация не обновлена для doc {}: {}",
                        doc.getId(), e.getMessage());
            }
            if (item != null) item.setApplied(true);
            audit.event("GOSKLYUCH_SIGN", RESULT_SUCCESS, null,
                    TARGET_DOCUMENT, String.valueOf(doc.getId()), subject,
                    "orderId=" + order.getExternalOrderId());
            return true;
        } catch (Exception e) {
            order.setError(truncate("Подпись получена, но не принята: " + e.getMessage(),
                    MAX_ERROR));
            orderRepository.save(order);
            audit.event("GOSKLYUCH_SIGN", RESULT_FAIL, null,
                    TARGET_DOCUMENT, String.valueOf(doc.getId()), doc.getOriginalName(),
                    e.getMessage());
            log.warn("Госключ: подпись заявки {} не применена: {}",
                    order.getId(), e.getMessage());
            return false;
        }
    }

    private void markError(GosklyuchOrder order, RuntimeException e) {
        order.setStatus(GosklyuchStatus.ERROR);
        order.setError(truncate(e.getMessage(), MAX_ERROR));
        orderRepository.save(order);
        log.warn("Госключ: заявка {} не отправлена: {}", order.getId(), e.getMessage());
    }

    private byte[] readBytes(Document doc) {
        try {
            return documentService.getBytes(doc);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Не удалось прочитать документ: " + e.getMessage(), e);
        }
    }

    private static String stripSig(String fileName) {
        return fileName != null && fileName.endsWith(".sig")
                ? fileName.substring(0, fileName.length() - 4) : "";
    }

    private static String actorEmail(User actor) {
        return actor == null ? null : actor.getEmail();
    }

    private static String statusNameOrDefault(String name, GosklyuchStatus status) {
        return name == null || name.isBlank() ? "Заявка завершена: " + status : name;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static String truncate(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }
}
