package ru.afina.gosklyuch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.entity.Document;
import ru.afina.entity.GosklyuchOrder;
import ru.afina.entity.GosklyuchOrderItem;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.gosklyuch.client.FakeGosklyuchClient;
import ru.afina.gosklyuch.client.GosklyuchClient;
import ru.afina.gosklyuch.client.GosklyuchOrderInfo;
import ru.afina.repository.GosklyuchOrderRepository;
import ru.afina.repository.UserRepository;
import ru.afina.service.AuditService;
import ru.afina.service.ConsentService;
import ru.afina.service.DocumentService;
import ru.afina.service.EmailService;
import ru.afina.service.SignatureVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Госключ: отправка заявки и обработка статусов")
class GosklyuchServiceTest {

    @Mock private GosklyuchOrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private DocumentService documentService;
    @Mock private ConsentService consentService;
    @Mock private EmailService emailService;
    @Mock private ru.afina.service.SignatureVisualizationService visualizationService;
    @Mock private SignatureVerifier signatureVerifier;
    @Mock private AuditService audit;

    private GosklyuchProperties properties;
    private FakeGosklyuchClient fakeClient;
    private GosklyuchSigner signer;
    private Document doc;
    private User owner;
    private final List<GosklyuchOrder> saved = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        properties = new GosklyuchProperties();
        fakeClient = new FakeGosklyuchClient();
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

        lenient().when(orderRepository.save(any())).thenAnswer(inv -> {
            GosklyuchOrder o = inv.getArgument(0);
            saved.add(o);
            return o;
        });
        lenient().when(documentService.getById(1L)).thenReturn(doc);
        lenient().when(documentService.getBytes(doc))
                .thenReturn("%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        lenient().when(userRepository.findById(10L)).thenReturn(Optional.of(owner));
    }

    @Test
    @DisplayName("При выключенной интеграции отправка запрещена")
    void disabledIntegration() {
        GosklyuchService service = service(fakeClient, signer);
        assertThat(service.isEnabled()).isFalse();
        assertThatThrownBy(() -> service.submit(doc, owner))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("выключена");
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("Без настроенного подписанта пакета отправка запрещена")
    void requiresSigner() {
        enable("fake");
        GosklyuchService service = service(fakeClient, new GosklyuchSignerUnavailable());
        assertThat(service.isEnabled()).isTrue();
        assertThat(service.isReadyToSend()).isFalse();
        assertThatThrownBy(() -> service.submit(doc, owner))
                .isInstanceOf(GosklyuchUnavailableException.class)
                .hasMessageContaining("УКЭП");
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("Успешная отправка: заявка SENT, архив собран, req.xml содержит СНИЛС")
    void submitsSuccessfully() {
        enable("fake");
        GosklyuchService service = service(fakeClient, signer);

        GosklyuchOrder order = service.submit(doc, owner);

        assertThat(order.getStatus()).isEqualTo(GosklyuchStatus.SENT);
        assertThat(order.getExternalOrderId()).isNotNull();
        assertThat(order.getSignerSnils()).isEqualTo("11223344595");
        assertThat(order.getDocument()).isSameAs(doc);
        assertThat(fakeClient.packageOf(order.getExternalOrderId())).isNotEmpty();
        assertThat(fakeClient.statusOf(order.getExternalOrderId())).isEqualTo(3);
        assertThat(saved).isNotEmpty();
        assertThat(saved.get(saved.size() - 1).getStatus()).isEqualTo(GosklyuchStatus.SENT);
    }

    @Test
    @DisplayName("Пакетная отправка: несколько документов в одной заявке")
    void submitsPackage() throws Exception {
        enable("fake");
        Document second = new Document();
        second.setId(2L);
        second.setOwner(owner);
        second.setOriginalName("Заявление.pdf");
        second.setContentType("application/pdf");
        lenient().when(documentService.getById(2L)).thenReturn(second);
        lenient().when(documentService.getBytes(second))
                .thenReturn("%PDF-1.4 second".getBytes(StandardCharsets.UTF_8));

        GosklyuchService service = service(fakeClient, signer);
        GosklyuchOrder order = service.submitAll(List.of(doc, second), owner);

        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getStatus()).isEqualTo(GosklyuchStatus.SENT);
        assertThat(order.getExternalOrderId()).isNotNull();

        service.refresh(order);

        assertThat(order.getStatus()).isEqualTo(GosklyuchStatus.SIGNED);
        assertThat(order.getItems()).allMatch(i -> i.getSignatureBase64() != null);
    }

    @Test
    @DisplayName("Без СНИЛС и с неверным контрольным числом отправка отклоняется")
    void rejectsBadSnils() {
        enable("fake");
        GosklyuchService service = service(fakeClient, signer);

        owner.setSnils(null);
        assertThatThrownBy(() -> service.submit(doc, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("СНИЛС");

        owner.setSnils("11223344596");
        assertThatThrownBy(() -> service.submit(doc, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("контрольного числа");
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("Повторная отправка при ожидающей заявке отклоняется")
    void rejectsDuplicatePending() {
        enable("fake");
        when(orderRepository.countPendingForDocument(anyLong(), any()))
                .thenReturn(1L);
        GosklyuchService service = service(fakeClient, signer);
        assertThatThrownBy(() -> service.submit(doc, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже есть заявка");
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("Подписанный документ повторно не отправляется")
    void rejectsSignedDocument() {
        enable("fake");
        doc.setSigned(true);
        GosklyuchService service = service(fakeClient, signer);
        assertThatThrownBy(() -> service.submit(doc, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже подписан");
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("Ошибка клиента ЕПГУ переводит заявку в ERROR")
    void marksErrorOnClientFailure() {
        enable("fake");
        GosklyuchClient failing = new FakeGosklyuchClient() {
            @Override
            public long createOrder(ru.afina.gosklyuch.client.GosklyuchOrderMeta meta) {
                throw new ru.afina.gosklyuch.client.GosklyuchClientException("нет связи");
            }
        };
        GosklyuchService service = service(failing, signer);
        assertThatThrownBy(() -> service.submit(doc, owner))
                .isInstanceOf(ru.afina.gosklyuch.client.GosklyuchClientException.class);
        assertThat(saved.get(saved.size() - 1).getStatus()).isEqualTo(GosklyuchStatus.ERROR);
        assertThat(saved.get(saved.size() - 1).getError()).contains("нет связи");
    }

    @Test
    @DisplayName("Статус «подписано»: подпись сохраняется, но флаг применения выключен")
    void refreshesSignedOrderWithoutApplying() {
        enable("fake");
        GosklyuchService service = service(fakeClient, signer);
        GosklyuchOrder order = submittedOrder(service);

        service.refresh(order);

        assertThat(order.getStatus()).isEqualTo(GosklyuchStatus.SIGNED);
        assertThat(order.getSignatureBase64()).isNotBlank();
        assertThat(order.getCompletedAt()).isNotNull();
        verify(documentService, never()).addSignature(anyLong(), any(), anyString(), anyString(), anyString());
        verify(emailService, never()).sendAlert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Ручное применение подписи: проверка и привязка к документу")
    void appliesStoredSignatureManually() throws Exception {
        enable("fake");
        GosklyuchOrder order = new GosklyuchOrder();
        order.setId(77L);
        order.setDocument(doc);
        order.setOwner(owner);
        order.setStatus(GosklyuchStatus.SIGNED);
        order.setSignatureBase64("c2lnbmF0dXJl");
        when(orderRepository.findWithDocumentAndOwnerById(77L)).thenReturn(Optional.of(order));
        when(documentService.listSignatures(1L)).thenReturn(List.of());
        when(signatureVerifier.verifyDetached(any(byte[].class), anyString()))
                .thenReturn(Map.of("signerSubject", "Иванов Иван", "signerSerial", "123"));

        GosklyuchService service = service(fakeClient, signer);
        boolean ok = service.applyStoredSignature(77L, owner);

        assertThat(ok).isTrue();
        verify(documentService).addSignature(eq(1L), isNull(), eq("c2lnbmF0dXJl"),
                eq("Иванов Иван"), eq("123"));
    }

    @Test
    @DisplayName("Повторное применение той же подписи отклоняется")
    void rejectsDuplicateManualApply() {
        enable("fake");
        GosklyuchOrder order = new GosklyuchOrder();
        order.setId(78L);
        order.setDocument(doc);
        order.setOwner(owner);
        order.setStatus(GosklyuchStatus.SIGNED);
        order.setSignatureBase64("c2lnbmF0dXJl");
        when(orderRepository.findWithDocumentAndOwnerById(78L)).thenReturn(Optional.of(order));
        ru.afina.entity.DocumentSignature existing =
                new ru.afina.entity.DocumentSignature();
        existing.setSignatureBase64("c2lnbmF0dXJl");
        when(documentService.listSignatures(1L)).thenReturn(List.of(existing));

        GosklyuchService service = service(fakeClient, signer);

        assertThatThrownBy(() -> service.applyStoredSignature(78L, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже применены");
    }

    @Test
    @DisplayName("Пакет: карта последних заявок включает все документы пакета")
    void latestByDocumentIdsIncludesPackageItems() {
        enable("fake");
        Document second = new Document();
        second.setId(2L);
        second.setOwner(owner);
        GosklyuchOrder order = new GosklyuchOrder();
        order.setId(5L);
        order.setDocument(doc);
        order.setOwner(owner);
        order.setStatus(GosklyuchStatus.SENT);
        GosklyuchOrderItem item = new GosklyuchOrderItem();
        item.setOrder(order);
        item.setDocument(second);
        item.setFileName("document_2.pdf");
        order.getItems().add(item);
        when(orderRepository.findByDocumentIds(any())).thenReturn(List.of(order));

        GosklyuchService service = service(fakeClient, signer);
        Map<Long, GosklyuchOrder> map = service.latestByDocumentIds(List.of(1L, 2L));

        assertThat(map).containsKeys(1L, 2L);
        assertThat(map.get(2L).getId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Пакет: пустой список и документы разных клиентов отклоняются")
    void rejectsInvalidPackage() {
        enable("fake");
        GosklyuchService service = service(fakeClient, signer);

        assertThatThrownBy(() -> service.submitAll(List.of(), owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Не выбраны");

        User other = new User();
        other.setId(11L);
        other.setEmail("other@example.com");
        Document foreign = new Document();
        foreign.setId(2L);
        foreign.setOwner(other);
        foreign.setOriginalName("Другой.pdf");
        foreign.setContentType("application/pdf");
        lenient().when(documentService.getById(2L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.submitAll(List.of(doc, foreign), owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("одному клиенту");
    }

    @Test
    @DisplayName("Служебные методы: лимит, pending, поиск и обновление заявки")
    void serviceHelpers() {
        enable("fake");
        when(orderRepository.countPendingForDocument(anyLong(), any())).thenReturn(1L);
        GosklyuchOrder order = new GosklyuchOrder();
        order.setId(9L);
        order.setDocument(doc);
        order.setOwner(owner);
        order.setStatus(GosklyuchStatus.SENT);
        lenient().when(orderRepository.findWithDocumentAndOwnerById(9L))
                .thenReturn(Optional.of(order));

        GosklyuchService service = service(fakeClient, signer);
        assertThat(service.maxDocuments()).isEqualTo(15);
        assertThat(service.hasPendingOrder(1L)).isTrue();
        assertThat(service.findOrder(9L)).isPresent();

        assertThatThrownBy(() -> service.refreshOrder(9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("внешнего номера");
    }

    @Test
    @DisplayName("Пакет: подпись применяется к каждому документу")
    void appliesPackageSignaturesManually() throws Exception {
        enable("fake");
        Document second = new Document();
        second.setId(2L);
        second.setOwner(owner);
        second.setOriginalName("Заявление.pdf");
        second.setContentType("application/pdf");
        lenient().when(documentService.getBytes(second)).thenReturn("second".getBytes());

        GosklyuchOrder order = new GosklyuchOrder();
        order.setId(55L);
        order.setDocument(doc);
        order.setOwner(owner);
        order.setStatus(GosklyuchStatus.SIGNED);
        for (Document d : List.of(doc, second)) {
            GosklyuchOrderItem item = new GosklyuchOrderItem();
            item.setOrder(order);
            item.setDocument(d);
            item.setFileName("document_" + d.getId() + ".pdf");
            item.setSignatureBase64("sig-" + d.getId());
            order.getItems().add(item);
        }
        lenient().when(orderRepository.findWithDocumentAndOwnerById(55L))
                .thenReturn(Optional.of(order));
        lenient().when(documentService.listSignatures(anyLong())).thenReturn(List.of());
        lenient().when(signatureVerifier.verifyDetached(any(byte[].class), anyString()))
                .thenReturn(Map.of("signerSubject", "Иванов", "signerSerial", "1"));

        GosklyuchService service = service(fakeClient, signer);
        boolean ok = service.applyStoredSignature(55L, owner);

        assertThat(ok).isTrue();
        verify(documentService).addSignature(eq(1L), isNull(), anyString(), anyString(), anyString());
        verify(documentService).addSignature(eq(2L), isNull(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Пакет без файлов подписи: статус подписан, но ошибка фиксируется")
    void packageSignedWithoutFiles() {
        enable("fake");
        GosklyuchService service = service(fakeClient, signer);
        GosklyuchOrder order = service.submitAll(List.of(doc), owner);
        var clientNoFiles = new FakeGosklyuchClient() {
            @Override
            public GosklyuchOrderInfo getOrder(long orderId) {
                return new GosklyuchOrderInfo(orderId, 3, "Документы подписаны", List.of());
            }
        };
        GosklyuchService noFiles = new GosklyuchService(properties, orderRepository,
                userRepository, documentService, consentService, signatureVerifier,
                org.mockito.Mockito.mock(ru.afina.service.CrlOnDemandService.class),
                emailService, visualizationService, audit,
                new GosklyuchRequestXmlBuilder(),
                GosklyuchPackageBuilder.from(signer, properties), clientNoFiles, signer);

        noFiles.refresh(order);

        assertThat(order.getStatus()).isEqualTo(GosklyuchStatus.SIGNED);
        assertThat(order.getError()).contains("файлы подписи");
    }

    @Test
    @DisplayName("Пакет: уведомление содержит количество документов")
    void notifiesOwnerForPackage() throws Exception {
        enable("fake");
        properties.setNotifyEnabled(true);
        Document second = new Document();
        second.setId(2L);
        second.setOwner(owner);
        second.setOriginalName("Заявление.pdf");
        second.setContentType("application/pdf");
        lenient().when(documentService.getById(2L)).thenReturn(second);
        lenient().when(documentService.getBytes(second)).thenReturn("second".getBytes());

        GosklyuchService service = service(fakeClient, signer);
        GosklyuchOrder order = service.submitAll(List.of(doc, second), owner);
        service.refresh(order);

        verify(emailService).sendAlert(eq("client@example.com"), anyString(),
                contains("документов: 2"));
    }

    @Test
    @DisplayName("Пакет: больше 15 документов отклоняется")
    void rejectsTooManyDocuments() {
        enable("fake");
        List<Document> many = new ArrayList<>();
        for (long i = 2; i < 18; i++) {
            Document d = new Document();
            d.setId(i);
            d.setOwner(owner);
            d.setOriginalName("doc_" + i + ".pdf");
            d.setContentType("application/pdf");
            many.add(d);
        }
        lenient().when(documentService.getById(anyLong())).thenReturn(doc);
        GosklyuchService service = service(fakeClient, signer);

        assertThatThrownBy(() -> service.submitAll(many, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("15");
    }

    @Test
    @DisplayName("Уведомление клиенту при подписании (если включено)")
    void notifiesOwnerWhenEnabled() {
        enable("fake");
        properties.setNotifyEnabled(true);
        GosklyuchService service = service(fakeClient, signer);
        GosklyuchOrder order = service.submit(doc, owner);

        service.refresh(order);

        verify(emailService).sendAlert(eq("client@example.com"),
                contains("Госключ"), contains("Согласие.pdf"));
    }

    @Test
    @DisplayName("Статус «подписано» с apply-signature: подпись применяется к документу")
    void appliesSignatureWhenEnabled() throws Exception {
        enable("fake");
        properties.setApplySignature(true);
        when(signatureVerifier.verifyDetached(any(byte[].class), anyString()))
                .thenReturn(Map.of("signerSubject", "Иванов Иван", "signerSerial", "123"));
        GosklyuchService service = service(fakeClient, signer);
        GosklyuchOrder order = submittedOrder(service);

        service.refresh(order);

        assertThat(order.getStatus()).isEqualTo(GosklyuchStatus.SIGNED);
        verify(documentService).addSignature(1L, null, order.getSignatureBase64(),
                "Иванов Иван", "123");
        verify(visualizationService).generate(doc);
    }

    @Test
    @DisplayName("Отказ подписанта: заявка REJECTED и закрыта")
    void refreshesRejectedOrder() {
        enable("fake");
        GosklyuchService service = service(fakeClient, signer);
        GosklyuchOrder order = service.submit(doc, owner);
        fakeClient.setStatus(order.getExternalOrderId(), 4);

        service.refresh(order);

        assertThat(order.getStatus()).isEqualTo(GosklyuchStatus.REJECTED);
        assertThat(order.getCompletedAt()).isNotNull();
        assertThat(order.getError()).contains("статус 4");
    }

    @Test
    @DisplayName("refreshPending обрабатывает только заявки с внешним номером")
    void refreshesPendingOrders() {
        enable("fake");
        GosklyuchService service = service(fakeClient, signer);
        GosklyuchOrder withId = submittedOrder(service);
        GosklyuchOrder withoutId = new GosklyuchOrder();
        withoutId.setStatus(GosklyuchStatus.SENT);
        when(orderRepository.findPendingWithDocument(any())).thenReturn(List.of(withId, withoutId));

        int processed = service.refreshPending();

        assertThat(processed).isEqualTo(1);
        assertThat(withId.getStatus()).isEqualTo(GosklyuchStatus.SIGNED);
    }

    @Test
    @DisplayName("Карточка клиента: при выключенной интеграции заявки не запрашиваются")
    void recentOrdersNotQueriedWhenDisabled() {
        GosklyuchService service = service(fakeClient, signer);
        assertThat(service.recentForOwner(10L, 5)).isEmpty();
        assertThat(service.latestByDocumentIds(List.of(1L))).isEmpty();
        verify(orderRepository, never()).findRecentByOwner(any(), any());
        verify(orderRepository, never()).findByDocumentIds(any());
    }

    @Test
    @DisplayName("Карточка/раздел: по каждому документу возвращается только последняя заявка")
    void latestOrdersPerDocument() {
        enable("fake");
        GosklyuchOrder latest = orderFor(doc, 2L, GosklyuchStatus.SENT);
        GosklyuchOrder older = orderFor(doc, 1L, GosklyuchStatus.ERROR);
        lenient().when(orderRepository.findByDocumentIds(any()))
                .thenReturn(List.of(latest, older));
        lenient().when(orderRepository.findRecentByOwner(any(), any()))
                .thenReturn(List.of(latest));

        GosklyuchService service = service(fakeClient, signer);

        Map<Long, GosklyuchOrder> map = service.latestByDocumentIds(List.of(1L));
        assertThat(map).containsOnlyKeys(1L);
        assertThat(map.get(1L).getId()).isEqualTo(2L);
        assertThat(service.recentForOwner(10L, 5)).hasSize(1);
    }

    @Test
    @DisplayName("Терминальная заявка повторно не опрашивается")
    void skipsTerminalOrders() {
        enable("fake");
        GosklyuchService service = service(fakeClient, signer);
        GosklyuchOrder order = new GosklyuchOrder();
        order.setStatus(GosklyuchStatus.SIGNED);
        order.setExternalOrderId(5L);
        service.refresh(order);
        verify(documentService, never()).getById(anyLong());
    }

    private GosklyuchOrder orderFor(Document document, Long id, GosklyuchStatus status) {
        GosklyuchOrder order = new GosklyuchOrder();
        order.setId(id);
        order.setDocument(document);
        order.setOwner(owner);
        order.setStatus(status);
        return order;
    }

    private GosklyuchOrder submittedOrder(GosklyuchService service) {
        GosklyuchOrder order = service.submit(doc, owner);
        // после submit фейковый клиент отдаёт статус 3 (подписано)
        GosklyuchOrderInfo info = fakeClient.getOrder(order.getExternalOrderId());
        assertThat(info.statusCode()).isEqualTo(3);
        return order;
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

    private GosklyuchService service(GosklyuchClient client, GosklyuchSigner s) {
        return new GosklyuchService(properties, orderRepository, userRepository, documentService,
                consentService,
                signatureVerifier, org.mockito.Mockito.mock(ru.afina.service.CrlOnDemandService.class),
                emailService, visualizationService, audit, new GosklyuchRequestXmlBuilder(),
                GosklyuchPackageBuilder.from(s, properties), client, s);
    }
}
