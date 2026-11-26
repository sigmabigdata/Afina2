package ru.afina.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.afina.entity.Document;
import ru.afina.entity.User;

/**
 * Уведомления по документам: письмо владельцу после подписания.
 *
 * <p>Отправляется при подписании с плагина, загрузке {@code .sig} и
 * применении подписи Госключа. Выключается настройкой
 * {@code app.notify.document-signed=false} (env {@code APP_NOTIFY_DOCUMENT_SIGNED}).</p>
 */
@Service
public class DocumentNotifyService {

    private static final Logger log = LoggerFactory.getLogger(DocumentNotifyService.class);

    private final EmailService emailService;

    @Value("${app.notify.document-signed:true}")
    private boolean enabled;

    public DocumentNotifyService(EmailService emailService) {
        this.emailService = emailService;
    }

    /** Письмо владельцу документа: кто и когда подписал. */
    public void documentSigned(Document doc, User signer) {
        if (!enabled || doc == null) return;
        User owner = doc.getOwner();
        if (owner == null || owner.getEmail() == null || owner.getEmail().isBlank()) return;
        String signerName = signer == null ? "—"
                : (signer.getFullName() != null ? signer.getFullName() : signer.getEmail());
        String body = "Документ: " + doc.getOriginalName() + "\n"
                + "Подписал: " + signerName + "\n"
                + "Время: " + java.time.ZonedDateTime.now() + "\n\n"
                + "Открыть сервис: /dashboard";
        try {
            emailService.sendAlert(owner.getEmail(), "Афина · Документ подписан", body);
        } catch (Exception e) {
            log.warn("Не удалось отправить уведомление о подписи: {}", e.getMessage());
        }
    }
}
