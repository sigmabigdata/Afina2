package ru.afina.api;

import org.springframework.stereotype.Component;
import ru.afina.api.dto.AuditEventDto;
import ru.afina.api.dto.CrlDto;
import ru.afina.api.dto.DocumentDto;
import ru.afina.api.dto.FolderDto;
import ru.afina.api.dto.GosklyuchOrderDto;
import ru.afina.api.dto.PoaDto;
import ru.afina.api.dto.TemplateDto;
import ru.afina.api.dto.UserDto;
import ru.afina.entity.AuditEvent;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentFolder;
import ru.afina.entity.DocumentSignature;
import ru.afina.entity.DocumentTemplate;
import ru.afina.entity.DocumentVisualization;
import ru.afina.entity.GosklyuchOrder;
import ru.afina.entity.GosklyuchOrderItem;
import ru.afina.entity.PowerOfAttorney;
import ru.afina.entity.User;
import ru.afina.repository.DocumentSignatureRepository;
import ru.afina.repository.DocumentVisualizationRepository;
import ru.afina.service.ConsentService;
import ru.afina.service.CrlService;
import ru.afina.service.DocumentTemplateService;

import java.util.List;

/**
 * Преобразование сущностей в DTO для REST API.
 *
 * <p>Маппер намеренно «плоский»: никаких ленивых связей не дергает сверх
 * необходимого, чтобы контроллеры могли работать вне транзакции.</p>
 */
@Component
public class ApiMapper {

    private final DocumentVisualizationRepository visualizationRepository;
    private final DocumentSignatureRepository signatureRepository;
    private final DocumentTemplateService templateService;
    private final ConsentService consentService;

    public ApiMapper(DocumentVisualizationRepository visualizationRepository,
                     DocumentSignatureRepository signatureRepository,
                     DocumentTemplateService templateService,
                     ConsentService consentService) {
        this.visualizationRepository = visualizationRepository;
        this.signatureRepository = signatureRepository;
        this.templateService = templateService;
        this.consentService = consentService;
    }

    // ─────────────────────────── пользователи ───────────────────────────

    public UserDto user(User u) {
        return user(u, 0, 0);
    }

    public UserDto user(User u, long documentsTotal, long documentsSigned) {
        boolean profileComplete = !consentService.needsProfile(u);
        boolean consentSigned = consentService.consentSigned(u);
        return new UserDto(
                u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getSnils(),
                u.getRole().name(), u.isEnabled(),
                profileComplete, consentSigned, u.getConsentSignedAt(),
                u.getLastLoginAt(), u.getCreatedAt(),
                documentsTotal, documentsSigned);
    }

    // ─────────────────────────── документы ───────────────────────────

    public DocumentDto document(Document d) {
        return document(d, null);
    }

    public DocumentDto document(Document d, GosklyuchOrder lastOrder) {
        List<DocumentDto.SignatureDto> signatures = d.getSignatures() == null ? List.of()
                : d.getSignatures().stream().map(this::signature).toList();

        DocumentDto.VisualizationDto vis = null;
        try {
            var opt = visualizationRepository.findByDocument(d);
            if (opt.isPresent()) {
                DocumentVisualization v = opt.get();
                vis = new DocumentDto.VisualizationDto(
                        v.getVersion(), v.getSize(), v.getCreatedAt(), v.getUpdatedAt());
            }
        } catch (Exception ignored) {
            // визуализация не критична для карточки документа
        }

        String folderName = null;
        Long folderId = null;
        try {
            if (d.getFolder() != null) {
                folderId = d.getFolder().getId();
                folderName = d.getFolder().getName();
            }
        } catch (Exception ignored) {
            // lazy-связь недоступна вне транзакции — не ломаем выдачу
        }

        Long ownerId = null;
        String ownerName = null;
        try {
            if (d.getOwner() != null) {
                ownerId = d.getOwner().getId();
                ownerName = d.getOwner().getFullName();
            }
        } catch (Exception ignored) {
            // см. выше
        }

        return new DocumentDto(
                d.getId(), d.getOriginalName(), d.getContentType(), d.getSize(),
                humanSize(d.getSize()), extensionOf(d.getOriginalName()),
                d.getFileSha256(), d.isSigned(), d.getSignedAt(), d.getSignerSubject(),
                d.getUploadedAt(), d.isApplyPoa(), d.getPublicUuid(),
                folderId, folderName, ownerId, ownerName,
                signatures.size(), signatures, vis,
                lastOrder == null ? null : order(lastOrder));
    }

    public DocumentDto.SignatureDto signature(DocumentSignature s) {
        Long signerId = null;
        String signerName = null;
        try {
            if (s.getSignerUser() != null) {
                signerId = s.getSignerUser().getId();
                signerName = s.getSignerUser().getFullName();
            }
        } catch (Exception ignored) {
            // lazy
        }
        return new DocumentDto.SignatureDto(
                s.getId(), s.getSignerSubject(), s.getSignerSerial(),
                s.getSignedAt(), s.isApplyPoa(), signerId, signerName);
    }

    // ─────────────────────────── папки ───────────────────────────

    public FolderDto folder(DocumentFolder f, long documentsCount, long signedCount) {
        return new FolderDto(f.getId(), f.getName(), documentsCount, signedCount,
                f.getCreatedAt());
    }

    // ─────────────────────────── шаблоны ───────────────────────────

    public TemplateDto template(DocumentTemplate t) {
        int fields = 0;
        try {
            fields = templateService.parseFields(t).size();
        } catch (Exception ignored) {
            // некорректный fieldsJson не должен ломать список шаблонов
        }
        return new TemplateDto(
                t.getId(), t.getKind() == null ? "DOCUMENT" : t.getKind().name(),
                t.getName(), t.getDescription(), t.getHtmlContent(), t.getFieldsJson(),
                t.isActive(), t.getSortOrder(), t.getCreatedBy(),
                t.getCreatedAt(), t.getUpdatedAt(), fields);
    }

    // ─────────────────────────── Госключ ───────────────────────────

    public GosklyuchOrderDto order(GosklyuchOrder o) {
        Long docId = null;
        String docName = null;
        try {
            if (o.getDocument() != null) {
                docId = o.getDocument().getId();
                docName = o.getDocument().getOriginalName();
            }
        } catch (Exception ignored) {
            // lazy
        }
        Long ownerId = null;
        String ownerName = null;
        try {
            if (o.getOwner() != null) {
                ownerId = o.getOwner().getId();
                ownerName = o.getOwner().getFullName();
            }
        } catch (Exception ignored) {
            // lazy
        }
        List<GosklyuchOrderDto.ItemDto> items = o.getItems() == null ? List.of()
                : o.getItems().stream().map(this::orderItem).toList();

        return new GosklyuchOrderDto(
                o.getId(), docId, docName, ownerId, ownerName,
                maskSnils(o.getSignerSnils()), o.getExternalOrderId(),
                o.getStatus() == null ? "NEW" : o.getStatus().name(),
                o.getStatusName(), o.getStatusCode(), o.getError(),
                o.getCreatedAt(), o.getUpdatedAt(), o.getCompletedAt(), items);
    }

    private GosklyuchOrderDto.ItemDto orderItem(GosklyuchOrderItem i) {
        Long docId = null;
        try {
            if (i.getDocument() != null) docId = i.getDocument().getId();
        } catch (Exception ignored) {
            // lazy
        }
        return new GosklyuchOrderDto.ItemDto(i.getId(), docId, i.getFileName(),
                i.getSignatureBase64() != null, i.isApplied());
    }

    // ─────────────────────────── аудит ───────────────────────────

    public AuditEventDto audit(AuditEvent e) {
        return new AuditEventDto(
                e.getId(), e.getEventTime(), e.getEventType(), e.getResult(),
                e.getActorEmail(), e.getActorRole(), e.getActorIp(),
                e.getTargetType(), e.getTargetId(), e.getTargetInfo(), e.getDetails());
    }

    // ─────────────────────────── доверенности ───────────────────────────

    public PoaDto poa(PowerOfAttorney p) {
        return new PoaDto(
                p.getId(), p.getFio(), maskSnils(p.getSnils()), p.getInn(),
                p.getOrgName(), p.getOrgInn(), p.getDocNumber(), p.getStampNumber(),
                p.getDocDate(), p.getValidFrom(), p.getValidUntil(), p.getNotaryName(),
                p.isActive(), p.isEffective(), p.getCreatedBy(), p.getCreatedAt());
    }

    // ─────────────────────────── CRL ───────────────────────────

    public CrlDto crl(CrlService.CrlInfo c, String status) {
        return new CrlDto(
                c.fileName(), c.sizeBytes(), c.sizePretty(), c.issuer(),
                c.lastUpdate(), c.nextUpdate(), c.downloadedAt(),
                c.daysToExpiry(), c.manual(), status);
    }

    // ─────────────────────────── утилиты ───────────────────────────

    /** {@code 1536} → {@code 1,5 КБ}. */
    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f КБ", kb).replace('.', ',');
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f МБ", mb).replace('.', ',');
        return String.format("%.1f ГБ", mb / 1024.0).replace('.', ',');
    }

    public static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    /** СНИЛС в выдаче маскируется: показываем только последние 4 цифры. */
    public static String maskSnils(String snils) {
        if (snils == null || snils.isBlank()) return null;
        String digits = snils.replaceAll("\\D", "");
        if (digits.length() < 4) return "***";
        return "***-" + digits.substring(digits.length() - 4);
    }
}
