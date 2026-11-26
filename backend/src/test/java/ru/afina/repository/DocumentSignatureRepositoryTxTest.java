package ru.afina.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentSignature;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.security.PiiEncryptor;
import ru.afina.security.PiiStringConverter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессия на 500 на проде: {@code updateApplyPoa} вызывается из контроллера
 * БЕЗ внешней транзакции, поэтому сам метод обязан быть @Transactional —
 * иначе flush падает с TransactionRequiredException.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(DocumentSignatureRepositoryTxTest.PiiStubConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("DocumentSignatureRepository: updateApplyPoa без внешней транзакции")
class DocumentSignatureRepositoryTxTest {

    /** Заглушка шифрования: ключей PII в тестовом окружении нет. */
    @TestConfiguration
    static class PiiStubConfig {
        @Bean
        PiiEncryptor piiEncryptor() {
            PiiEncryptor enc = Mockito.mock(PiiEncryptor.class);
            Mockito.when(enc.encrypt(Mockito.anyString()))
                    .thenAnswer(i -> "enc:" + i.getArgument(0));
            Mockito.when(enc.decrypt(Mockito.anyString()))
                    .thenAnswer(i -> ((String) i.getArgument(0)).replaceFirst("^enc:", ""));
            return enc;
        }

        @Bean
        PiiStringConverter piiStringConverter(PiiEncryptor enc) {
            return new PiiStringConverter(enc);
        }
    }

    @Autowired private DocumentSignatureRepository signatureRepo;
    @Autowired private DocumentRepository documentRepo;
    @Autowired private UserRepository userRepo;

    @Test
    @DisplayName("UPDATE выполняется и коммитится без @Transactional у вызывающего")
    void updateApplyPoaWorksWithoutOuterTransaction() {
        User owner = new User();
        owner.setEmail("test@example.ru");
        owner.setEmailHash("hash-1");
        owner.setFullName("Тестовый Пользователь");
        owner.setRole(Role.ROLE_USER);
        owner.setEnabled(true);
        userRepo.save(owner);

        Document doc = new Document();
        doc.setOriginalName("doc.pdf");
        doc.setStoredName("doc.pdf");
        doc.setContentType("application/pdf");
        doc.setOwner(owner);
        documentRepo.save(doc);

        DocumentSignature sig = new DocumentSignature();
        sig.setDocument(doc);
        sig.setSignatureBase64("AA==");
        signatureRepo.save(sig);

        // именно так вызывает контроллер — вне какой-либо транзакции
        int updated = signatureRepo.updateApplyPoa(sig.getId(), true);

        assertThat(updated).isEqualTo(1);
        assertThat(signatureRepo.findById(sig.getId()).orElseThrow().isApplyPoa()).isTrue();
    }
}
