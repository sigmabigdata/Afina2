package ru.afina.gosklyuch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import ru.afina.entity.Document;
import ru.afina.entity.GosklyuchOrder;
import ru.afina.entity.GosklyuchOrderItem;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.gosklyuch.client.FakeGosklyuchClient;
import ru.afina.gosklyuch.client.GosklyuchClient;
import ru.afina.gosklyuch.client.GosklyuchClientException;
import ru.afina.gosklyuch.client.GosklyuchFile;
import ru.afina.gosklyuch.client.GosklyuchOrderInfo;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Служебные методы и редкие ветки {@link GosklyuchService}: выключенная
 * интеграция, админские выборки, применение подписи с ошибками CRL/визуализации,
 * legacy-заявки без {@code items} и уведомления клиенту.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Госключ: служебные методы и ветки ошибок")
class GosklyuchServiceHelpersTest {

    @Mock private GosklyuchOrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private DocumentService documentService;
    @Mock private ConsentService consentService;
    @Mock private CrlOnDemandService crlOnDemandService;
    @Mock private EmailService emailService;
    @Mock private SignatureVisualizationService visualizationService;
    @Mock private SignatureVerifier signatureVerifier;
    @Mock private AuditService audit;

    private GosklyuchProperties properties;
    private FakeGosklyuchClient client;
    private GosklyuchSigner signer;
    private Document doc;
    private User owner;

    @BeforeEach
    void setUp() throws Exception {
        properties = new GosklyuchProperties();
        client = new FakeGosklyuchClient();
        signer = (name, content) -> ("SIG:" + name).getBytes(StandardCharsets.UTF_8);

        owner = new User();
        owner.setId(10L);
        owner.setEmail("client@example.com");
        owner.setFullName("Иванов Иван");
        owner.setRole(Role.ROLE_USER);
        owner.setSnils("11223344595");

        doc = new Document();
        doc.setId(1L);
        doc.setOwner(owner);
        doc.setOriginalName("Согласие.pdf");
        doc.setContentType("application/pdf");

        lenient().when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(documentService.getById(1L)).thenReturn(doc);
        lenient().when(documentService.getBytes(doc))
                .thenReturn("%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        lenient().when(userRepository.findById(10L)).thenReturn(Optional.of(owner));
    }

    // ──────────────────────────── помощники ────────────────────────────

    private GosklyuchService service(GosklyuchClient usedClient) {
        return new GosklyuchService(properties, orderRepository, userRepository, documentService,
                consentService, signatureVerifier, crlOnDemandService, emailService,
                visualizationService, audit, new GosklyuchRequestXmlBuilder(),
                GosklyuchPackageBuilder.from(signer, properties), usedClient, signer);
    }

    private GosklyuchService service() {
        return service(client);
    }

    private void enable(String provider) {
        properties.setEnabled(true);
        properties.setProvider(provider);
        properties.setRegion("45000000000");
        properties.setServiceCode("10000000374");
        properties.setTargetCode("-10000000374");
        properties.setSignExpiration(Duration.ofHours(24));
        properties.setBacklink("https://lk.gosuslugi.ru/notifications");
        properties.setMnemonic("MNSV03");
        properties.setOrgName("ООО «Тест»");
    }

    private GosklyuchOrder order(GosklyuchStatus status, String signature) {
        GosklyuchOrder order = new GosklyuchOrder();
        order.setId(77L);
        order.setDocument(doc);
        order.setOwner(owner);
        order.setStatus(status);
        order.setSignatureBase64(signature);
        order.setExternalOrderId(555L);
        order.setStatusName("статус");
        return order;
    }

    // ──────────────────────────── выключенная интеграция ────────────────────────────

    @Test
    @DisplayName("Интеграция выключена: все выборки пустые, исключений нет")
    void disabledIntegrationReturnsEmptyValues() {
        GosklyuchService service = service();

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.isReadyToSend()).isFalse();
        assertThat(service.adminOrders(null, 10)).isEmpty();
        assertThat(service.recentForOwner(10L, 5)).isEmpty();
        assertThat(service.latestByDocumentIds(List.of(1L))).isEmpty();
        assertThat(service.refreshPending()).isZero();
        verify(orderRepository, never()).findForAdmin(any(), any());
        verify(orderRepository, never()).findRecentByOwner(any(), any());
        verify(orderRepository, never()).findByDocumentIds(any());
    }

    @Test
    @DisplayName("Провайдер не выбран → отправка запрещена с отдельным сообщением")
    void enabledButProviderDisabled() {
        properties.setEnabled(true);
        properties.setProvider("disabled");
        GosklyuchService service = service();

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.adminOrders(null, 10)).isEmpty();
        assertThat(service.refreshPending()).isZero();
        assertThatThrownBy(() -> service.submit(doc, owner))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("Провайдер Госключа не выбран");

        properties.setProvider("  ");
        assertThat(service.isEnabled()).isFalse();
        properties.setProvider(null);
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("isReadyToSend=false без подписанта УКЭП")
    void notReadyWithoutSigner() {
        enable("fake");
        GosklyuchService service = new GosklyuchService(properties, orderRepository,
                userRepository, documentService, consentService, signatureVerifier,
                crlOnDemandService, emailService, visualizationService, audit,
                new GosklyuchRequestXmlBuilder(),
                GosklyuchPackageBuilder.from(new GosklyuchSignerUnavailable(), properties),
                client, new GosklyuchSignerUnavailable());

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.isReadyToSend()).isFalse();
    }

    // ──────────────────────────── выборки ────────────────────────────

    @Test
    @DisplayName("latestOrder/ordersForDocument: null → пусто, иначе запрос в репозиторий")
    void latestAndAllOrders() {
        GosklyuchOrder stored = order(GosklyuchStatus.SENT, null);
        lenient().when(orderRepository.findTopByDocumentIdOrderByIdDesc(1L))
                .thenReturn(Optional.of(stored));
        lenient().when(orderRepository.findByDocumentIdOrderByIdDesc(1L))
                .thenReturn(List.of(stored));
        GosklyuchService service = service();

        assertThat(service.latestOrder(null)).isEmpty();
        assertThat(service.latestOrder(1L)).contains(stored);
        assertThat(service.ordersForDocument(null)).isEmpty();
        assertThat(service.ordersForDocument(1L)).containsExactly(stored);
        assertThat(service.findOrder(null)).isEmpty();
    }

    @Test
    @DisplayName("adminOrders при включённой интеграции запрашивает страницу")
    void adminOrdersQueriesRepository() {
        enable("fake");
        GosklyuchOrder stored = order(GosklyuchStatus.SENT, null);
        when(orderRepository.findForAdmin(isNull(), eq(PageRequest.of(0, 5))))
                .thenReturn(List.of(stored));

        GosklyuchService service = service();

        lenient().when(orderRepository.findRecentByOwner(any(), any())).thenReturn(List.of());

        assertThat(service.adminOrders(null, 5)).containsExactly(stored);
        assertThat(service.recentForOwner(null, 5)).isEmpty();
        assertThat(service.recentForOwner(10L, 0)).isEmpty();
        assertThat(service.recentForOwner(10L, 3)).isEmpty();
        assertThat(service.latestByDocumentIds(null)).isEmpty();
        assertThat(service.latestByDocumentIds(List.of())).isEmpty();
    }

    @Test
    @DisplayName("maxDocuments: ограничен и настройкой, и спецификацией (15)")
    void maxDocumentsIsBounded() {
        GosklyuchService service = service();

        properties.setMaxDocuments(5);
        assertThat(service.maxDocuments()).isEqualTo(5);
        properties.setMaxDocuments(50);
        assertThat(service.maxDocuments()).isEqualTo(15);
    }

    @Test
    @DisplayName("hasPendingOrder: null → false, иначе учитывает NEW/SENT")
    void pendingOrders() {
        GosklyuchService service = service();
        assertThat(service.hasPendingOrder(null)).isFalse();

        when(orderRepository.countPendingForDocument(anyLong(), any())).thenReturn(2L);
        assertThat(service.hasPendingOrder(1L)).isTrue();
    }

    // ──────────────────────────── refresh ────────────────────────────

    @Test
    @DisplayName("refresh: null и заявка без внешнего номера игнорируются")
    void refreshIgnoresEmptyOrders() {
        GosklyuchService service = service();
        GosklyuchOrder noExternal = order(GosklyuchStatus.SENT, null);
        noExternal.setExternalOrderId(null);

        assertThatCode(() -> service.refresh(null)).doesNotThrowAnyException();
        assertThatCode(() -> service.refresh(noExternal)).doesNotThrowAnyException();

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("refresh: промежуточный статус сохраняется без завершения заявки")
    void refreshKeepsOrderInProgress() {
        enable("fake");
        GosklyuchOrder stored = order(GosklyuchStatus.NEW, null);
        FakeGosklyuchClient inProgress = new FakeGosklyuchClient() {
            @Override
            public GosklyuchOrderInfo getOrder(long orderId) {
                return new GosklyuchOrderInfo(orderId, 17, "в очереди", List.of());
            }
        };

        service(inProgress).refresh(stored);

        assertThat(stored.getStatus()).isEqualTo(GosklyuchStatus.SENT);
        assertThat(stored.getStatusCode()).isEqualTo(17);
        assertThat(stored.getCompletedAt()).isNull();
        verify(orderRepository).save(stored);
    }

    @Test
    @DisplayName("refreshPending: ошибка клиента по одной заявке не прерывает обработку")
    void refreshPendingSwallowsClientErrors() {
        enable("fake");
        GosklyuchOrder pending = order(GosklyuchStatus.SENT, null);
        when(orderRepository.findPendingWithDocument(any())).thenReturn(List.of(pending));
        GosklyuchClient failing = new FakeGosklyuchClient() {
            @Override
            public GosklyuchOrderInfo getOrder(long orderId) {
                throw new GosklyuchClientException("ЕПГУ недоступен");
            }
        };

        int processed = service(failing).refreshPending();

        assertThat(processed).isZero();
    }

    @Test
    @DisplayName("refreshOrder: заявка не найдена → IllegalArgumentException")
    void refreshOrderMissing() {
        when(orderRepository.findWithDocumentAndOwnerById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().refreshOrder(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Заявка не найдена");
    }

    // ──────────────────────────── применение подписи ────────────────────────────

    @Test
    @DisplayName("refreshOrder: завершённая заявка обновляется без обращения к ЕПГУ")
    void refreshOrderForTerminalOrder() {
        GosklyuchOrder signed = order(GosklyuchStatus.SIGNED, "c2ln");
        when(orderRepository.findWithDocumentAndOwnerById(77L)).thenReturn(Optional.of(signed));

        assertThatCode(() -> service().refreshOrder(77L)).doesNotThrowAnyException();
        assertThat(signed.getStatus()).isEqualTo(GosklyuchStatus.SIGNED);
    }

    @Test
    @DisplayName("applyStoredSignature: заявка не найдена / не подписана / без подписи")
    void applyStoredSignatureValidation() {
        enable("fake");
        GosklyuchService service = service();

        when(orderRepository.findWithDocumentAndOwnerById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.applyStoredSignature(1L, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Заявка не найдена");

        GosklyuchOrder sent = order(GosklyuchStatus.SENT, "c2ln");
        when(orderRepository.findWithDocumentAndOwnerById(2L)).thenReturn(Optional.of(sent));
        assertThatThrownBy(() -> service.applyStoredSignature(2L, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("только к заявке со статусом");

        GosklyuchOrder empty = order(GosklyuchStatus.SIGNED, null);
        when(orderRepository.findWithDocumentAndOwnerById(3L)).thenReturn(Optional.of(empty));
        assertThatThrownBy(() -> service.applyStoredSignature(3L, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("нет сохранённой подписи");
    }

    @Test
    @DisplayName("applyStoredSignature: подпись не принята → false и текст ошибки в заявке")
    void applyStoredSignatureReportsFailure() throws Exception {
        enable("fake");
        GosklyuchOrder signed = order(GosklyuchStatus.SIGNED, "c2lnbmF0dXJl");
        when(orderRepository.findWithDocumentAndOwnerById(77L)).thenReturn(Optional.of(signed));
        when(documentService.listSignatures(1L)).thenReturn(List.of());
        when(signatureVerifier.verifyDetached(any(byte[].class), anyString()))
                .thenThrow(new IllegalStateException("сертификат отозван"));

        boolean applied = service().applyStoredSignature(77L, owner);

        assertThat(applied).isFalse();
        assertThat(signed.getError()).contains("не принята").contains("отозван");
        verify(documentService, never()).addSignature(anyLong(), any(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("applyStoredSignature: ошибки CRL и визуализации не мешают применить подпись")
    void applyStoredSignatureIgnoresAncillaryFailures() throws Exception {
        enable("fake");
        GosklyuchOrder signed = order(GosklyuchStatus.SIGNED, "c2lnbmF0dXJl");
        when(orderRepository.findWithDocumentAndOwnerById(77L)).thenReturn(Optional.of(signed));
        when(documentService.listSignatures(1L)).thenReturn(List.of());
        when(crlOnDemandService.ensureForSignature(eq("c2lnbmF0dXJl"), eq(1L), isNull()))
                .thenThrow(new IllegalStateException("CRL недоступен"));
        when(signatureVerifier.verifyDetached(any(byte[].class), anyString()))
                .thenReturn(Map.of("signerSubject", "Иванов", "signerSerial", "1"));
        when(consentService.isConsentDocument(owner, doc)).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("нет шрифта"))
                .when(visualizationService).generate(doc);

        boolean applied = service().applyStoredSignature(77L, owner);

        assertThat(applied).isTrue();
        verify(documentService).addSignature(eq(1L), isNull(), eq("c2lnbmF0dXJl"),
                eq("Иванов"), eq("1"));
        verify(consentService).markSigned(owner);
        verify(visualizationService).generate(doc);
    }

    // ──────────────────────────── submit ────────────────────────────

    @Test
    @DisplayName("submitAll: документы без идентификаторов и без владельца отклоняются")
    void submitAllRejectsUnusableDocuments() {
        enable("fake");
        GosklyuchService service = service();

        Document withoutId = new Document();
        withoutId.setOwner(owner);
        assertThatThrownBy(() -> service.submitAll(List.of(withoutId), owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Не выбраны документы");

        Document noOwner = new Document();
        noOwner.setId(2L);
        noOwner.setOriginalName("Без владельца.pdf");
        noOwner.setContentType("application/pdf");
        lenient().when(documentService.getById(2L)).thenReturn(noOwner);
        assertThatThrownBy(() -> service.submitAll(List.of(noOwner), owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Владелец документа не найден");

        // владелец без id — тот же результат (вторая ветка loadOwner)
        User ghostOwner = new User();
        ghostOwner.setFullName("Без id");
        Document ghost = new Document();
        ghost.setId(3L);
        ghost.setOwner(ghostOwner);
        ghost.setOriginalName("Призрак.pdf");
        ghost.setContentType("application/pdf");
        lenient().when(documentService.getById(3L)).thenReturn(ghost);
        assertThatThrownBy(() -> service.submitAll(List.of(ghost), owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Владелец документа не найден");
    }

    @Test
    @DisplayName("submitAll: ошибка чтения файла → IllegalStateException")
    void submitAllWrapsIOException() throws Exception {
        enable("fake");
        when(documentService.getBytes(doc)).thenThrow(new IOException("диск недоступен"));

        assertThatThrownBy(() -> service().submitAll(List.of(doc), owner))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не удалось прочитать документ");
    }

    // ──────────────────────────── legacy-заявки и уведомления ────────────────────────────

    @Test
    @DisplayName("Legacy-заявка без items: подпись привязывается к документу заявки")
    void legacyOrderWithoutItems() {
        enable("fake");
        GosklyuchOrder legacy = order(GosklyuchStatus.SENT, null);
        String fileName = GosklyuchFileTypes.archiveFileName(doc);
        GosklyuchClient withSignature = new FakeGosklyuchClient() {
            @Override
            public GosklyuchOrderInfo getOrder(long orderId) {
                return new GosklyuchOrderInfo(orderId, 3, "подписано",
                        List.of(new GosklyuchFile(fileName + ".sig", 2, "application/octet-stream")));
            }
        };

        service(withSignature).refresh(legacy);

        assertThat(legacy.getStatus()).isEqualTo(GosklyuchStatus.SIGNED);
        assertThat(legacy.getSignatureBase64()).isNotBlank();
        assertThat(legacy.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Уведомление без документа в заявке: тема содержит «документ»")
    void notificationWithoutDocumentName() {
        enable("fake");
        properties.setNotifyEnabled(true);
        GosklyuchOrder rejected = new GosklyuchOrder();
        rejected.setId(88L);
        rejected.setOwner(owner);
        rejected.setStatus(GosklyuchStatus.SENT);
        rejected.setExternalOrderId(777L);
        GosklyuchClient rejecting = new FakeGosklyuchClient() {
            @Override
            public GosklyuchOrderInfo getOrder(long orderId) {
                return new GosklyuchOrderInfo(orderId, 4, "", List.of());
            }
        };

        service(rejecting).refresh(rejected);

        assertThat(rejected.getStatus()).isEqualTo(GosklyuchStatus.REJECTED);
        verify(emailService).sendAlert(eq("client@example.com"),
                eq("Афина · Госключ: отклонён подписантом"),
                org.mockito.ArgumentMatchers.contains("/dashboard"));
    }

    @Test
    @DisplayName("Уведомление об ошибке заявки: статус ERROR и понятная тема")
    void notificationForErrorStatus() {
        enable("fake");
        properties.setNotifyEnabled(true);
        GosklyuchOrder error = new GosklyuchOrder();
        error.setId(89L);
        error.setOwner(owner);
        error.setStatus(GosklyuchStatus.SENT);
        error.setExternalOrderId(778L);
        GosklyuchClient failing = new FakeGosklyuchClient() {
            @Override
            public GosklyuchOrderInfo getOrder(long orderId) {
                return new GosklyuchOrderInfo(orderId, 5, null, List.of());
            }
        };

        service(failing).refresh(error);

        assertThat(error.getStatus()).isEqualTo(GosklyuchStatus.ERROR);
        assertThat(error.getError()).isNotBlank();
        verify(emailService).sendAlert(eq("client@example.com"),
                eq("Афина · Госключ: ошибка обработки заявки"), anyString());
    }

    @Test
    @DisplayName("Уведомление без email у клиента не отправляется")
    void notificationSkippedWithoutEmail() {
        enable("fake");
        properties.setNotifyEnabled(true);
        owner.setEmail(null);
        GosklyuchOrder rejected = new GosklyuchOrder();
        rejected.setId(90L);
        rejected.setOwner(owner);
        rejected.setStatus(GosklyuchStatus.SENT);
        rejected.setExternalOrderId(779L);
        GosklyuchClient rejecting = new FakeGosklyuchClient() {
            @Override
            public GosklyuchOrderInfo getOrder(long orderId) {
                return new GosklyuchOrderInfo(orderId, 4, "отказ", List.of());
            }
        };

        service(rejecting).refresh(rejected);

        verify(emailService, never()).sendAlert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Ошибка SMTP при уведомлении не ломает обработку статуса")
    void notificationEmailFailureIsSwallowed() {
        enable("fake");
        properties.setNotifyEnabled(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("SMTP недоступен"))
                .when(emailService).sendAlert(anyString(), anyString(), anyString());
        GosklyuchOrder rejected = new GosklyuchOrder();
        rejected.setId(91L);
        rejected.setOwner(owner);
        rejected.setStatus(GosklyuchStatus.SENT);
        rejected.setExternalOrderId(780L);
        GosklyuchClient rejecting = new FakeGosklyuchClient() {
            @Override
            public GosklyuchOrderInfo getOrder(long orderId) {
                return new GosklyuchOrderInfo(orderId, 4, "отказ", List.of());
            }
        };

        assertThatCode(() -> service(rejecting).refresh(rejected))
                .doesNotThrowAnyException();
        assertThat(rejected.getStatus()).isEqualTo(GosklyuchStatus.REJECTED);
    }

    @Test
    @DisplayName("Уведомление о подписании: тема «подписан»")
    void notificationForSignedStatus() {
        enable("fake");
        properties.setNotifyEnabled(true);
        GosklyuchOrder submitted = service().submit(doc, owner);
        submitted.setOwner(owner);

        service().refresh(submitted);

        assertThat(submitted.getStatus()).isEqualTo(GosklyuchStatus.SIGNED);
        verify(emailService).sendAlert(eq("client@example.com"),
                eq("Афина · Госключ: подписан"), anyString());
    }
}
