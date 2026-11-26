package ru.afina.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import ru.afina.entity.Document;
import ru.afina.entity.User;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("Уведомления о подписанном документе")
class DocumentNotifyServiceTest {

    @Test
    @DisplayName("Письмо владельцу с названием документа")
    void sendsToOwner() {
        EmailService email = Mockito.mock(EmailService.class);
        DocumentNotifyService service = new DocumentNotifyService(email);
        ReflectionTestUtils.setField(service, "enabled", true);

        User owner = new User();
        owner.setEmail("client@example.com");
        owner.setFullName("Клиент");
        User signer = new User();
        signer.setFullName("Сотрудник");
        Document doc = new Document();
        doc.setOriginalName("Согласие.pdf");
        doc.setOwner(owner);

        service.documentSigned(doc, signer);

        verify(email).sendAlert(eq("client@example.com"),
                contains("подписан"), contains("Согласие.pdf"));
    }

    @Test
    @DisplayName("При выключенных уведомлениях письма нет")
    void respectsFlag() {
        EmailService email = Mockito.mock(EmailService.class);
        DocumentNotifyService service = new DocumentNotifyService(email);
        ReflectionTestUtils.setField(service, "enabled", false);
        Document doc = new Document();
        doc.setOriginalName("x.pdf");
        User owner = new User();
        owner.setEmail("client@example.com");
        doc.setOwner(owner);

        service.documentSigned(doc, owner);

        verify(email, never()).sendAlert(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString());
    }
}
