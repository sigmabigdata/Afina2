package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentSignature;
import ru.afina.entity.PowerOfAttorney;
import ru.afina.repository.DocumentRepository;
import ru.afina.service.DocumentService;
import ru.afina.service.PowerOfAttorneyService;
import ru.afina.service.SignatureVerifier;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Публичная проверка подписи по QR-коду (без авторизации).
 *
 * <p>Это <b>не</b> полноценная криптографическая верификация: проверяется
 * криптоподпись содержимого и (по возможности) цепочка/отзыв, но юридически
 * значимая проверка — в аккредитованном УЦ / Госуслугах.</p>
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicApi {

    private static final Logger log = LoggerFactory.getLogger(PublicApi.class);

    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final SignatureVerifier signatureVerifier;
    private final PowerOfAttorneyService poaService;

    public PublicApi(DocumentRepository documentRepository, DocumentService documentService,
                     SignatureVerifier signatureVerifier, PowerOfAttorneyService poaService) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.signatureVerifier = signatureVerifier;
        this.poaService = poaService;
    }

    @GetMapping("/info/{uuid}")
    public ResponseEntity<Map<String, Object>> info(@PathVariable String uuid) {
        UUID parsed;
        try {
            parsed = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Неверный формат идентификатора");
        }

        Optional<Document> opt = documentRepository.findByPublicUuid(parsed);
        if (opt.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Документ не найден");
        }

        Document doc = opt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentName", doc.getOriginalName());
        result.put("sizeBytes", doc.getSize());
        result.put("uploadedAt", doc.getUploadedAt());
        result.put("sha256", doc.getFileSha256());

        byte[] content = null;
        try {
            content = documentService.getBytes(doc);
        } catch (Exception e) {
            log.warn("public info: cannot read doc {}: {}", doc.getId(), e.getMessage());
        }

        List<Map<String, Object>> signatures = new ArrayList<>();
        for (DocumentSignature sig : doc.getSignatures()) {
            signatures.add(buildSignatureInfo(sig, content));
        }
        result.put("signatures", signatures);
        result.put("signatureCount", signatures.size());
        result.put("signed", doc.isSigned());
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildSignatureInfo(DocumentSignature sig, byte[] content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", sig.getSignerSubject());
        m.put("serial", sig.getSignerSerial());
        m.put("signedAt", sig.getSignedAt());
        m.put("applyPoa", sig.isApplyPoa());

        X509Certificate cert = signatureVerifier.extractSignerCert(sig.getSignatureBase64());
        if (cert != null) {
            Date notBefore = cert.getNotBefore();
            Date notAfter = cert.getNotAfter();
            Date now = new Date();
            m.put("certNotBefore", notBefore.toInstant().toString());
            m.put("certNotAfter", notAfter.toInstant().toString());
            m.put("certValidNow", now.after(notBefore) && now.before(notAfter));
        }

        boolean cryptoOk = false;
        if (content != null && sig.getSignatureBase64() != null) {
            try {
                var vr = signatureVerifier.verifyDetachedBasic(content, sig.getSignatureBase64());
                cryptoOk = Boolean.TRUE.equals(vr.get("valid"));
                m.put("cryptoOk", cryptoOk);
                m.put("cryptoMessage", null);
            } catch (SignatureVerifier.SignatureMismatchException e) {
                m.put("cryptoOk", false);
                m.put("cryptoMessage", "Подпись не соответствует документу");
            } catch (Exception e) {
                m.put("cryptoOk", false);
                m.put("cryptoMessage", "Не удалось проверить: " + e.getMessage());
            }
        } else {
            m.put("cryptoOk", false);
            m.put("cryptoMessage", "Не удалось получить содержимое документа");
        }

        if (cryptoOk) {
            try {
                signatureVerifier.verifyDetached(content, sig.getSignatureBase64());
                m.put("chainOk", true);
                m.put("chainMessage", null);
            } catch (Exception e) {
                m.put("chainOk", false);
                m.put("chainMessage", "Цепочка/отзыв не проверены: " + e.getMessage());
            }
        } else {
            m.put("chainOk", false);
            m.put("chainMessage", "Криптоподпись недействительна");
        }

        if (sig.isApplyPoa()) {
            String fio = extractCn(sig.getSignerSubject());
            Optional<PowerOfAttorney> poa = poaService.findEffectiveByFio(fio);
            if (poa.isPresent()) {
                PowerOfAttorney p = poa.get();
                m.put("poaFound", true);
                m.put("poaStampNumber", p.getStampNumber());
                m.put("poaValidFrom", p.getValidFrom() != null ? p.getValidFrom().toString() : null);
                m.put("poaValidUntil", p.getValidUntil() != null ? p.getValidUntil().toString() : null);
                m.put("poaEffective", p.isEffective());
            } else {
                m.put("poaFound", false);
            }
        }
        return m;
    }

    static String extractCn(String subject) {
        if (subject == null) return "—";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("CN=([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(subject);
        return m.find() ? m.group(1).replace("\"", "").trim() : subject;
    }
}
