package ru.afina.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentSignature;
import ru.afina.entity.PowerOfAttorney;
import ru.afina.repository.DocumentRepository;
import ru.afina.service.DocumentService;
import ru.afina.service.PowerOfAttorneyService;
import ru.afina.service.SignatureVerifier;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Ветки публичной проверки подписи без поднятия Spring-контекста:
 * недоступное содержимое, ошибки криптопроверки, найденная/ненайденная МЧД.
 *
 * <p>Зависимости — mock'и, поэтому каждую ветку можно проверить отдельно.</p>
 */
@DisplayName("PublicApi: ветки проверки подписи (unit)")
class PublicApiUnitTest {

    private static final String SIGNATURE = "MIIB...";
    private static final String SUBJECT = "CN=Иванов Иван Иванович, O=Афина, C=RU";

    private DocumentRepository documentRepository;
    private DocumentService documentService;
    private SignatureVerifier signatureVerifier;
    private PowerOfAttorneyService poaService;
    private PublicApi api;

    private Document document;
    private final UUID publicUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        documentService = mock(DocumentService.class);
        signatureVerifier = mock(SignatureVerifier.class);
        poaService = mock(PowerOfAttorneyService.class);
        api = new PublicApi(documentRepository, documentService, signatureVerifier, poaService);

        document = new Document();
        document.setId(1L);
        document.setPublicUuid(publicUuid);
        document.setOriginalName("Договор.pdf");
        document.setFileSha256("abc123");
        when(documentRepository.findByPublicUuid(publicUuid)).thenReturn(Optional.of(document));
    }

    // ──────────────────────────── помощники ────────────────────────────

    private DocumentSignature signature(String base64, boolean applyPoa) {
        DocumentSignature sig = new DocumentSignature();
        sig.setId(10L);
        sig.setDocument(document);
        sig.setSignatureBase64(base64);
        sig.setSignerSubject(SUBJECT);
        sig.setSignerSerial("1a2b");
        sig.setApplyPoa(applyPoa);
        return sig;
    }

    private Map<String, Object> infoWith(DocumentSignature sig) {
        document.setSignatures(List.of(sig));
        ResponseEntity<Map<String, Object>> response = api.info(publicUuid.toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        Object signatures = response.getBody().get("signatures");
        assertThat(signatures).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) signatures;
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    private static X509Certificate cert(Date notBefore, Date notAfter) {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getNotBefore()).thenReturn(notBefore);
        when(cert.getNotAfter()).thenReturn(notAfter);
        return cert;
    }

    // ──────────────────────────── ошибки запроса ────────────────────────────

    @Test
    @DisplayName("Неверный UUID → 400")
    void invalidUuid() {
        assertThatThrownBy(() -> api.info("not-a-uuid"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Неверный формат идентификатора");
        verifyNoInteractions(documentRepository);
    }

    @Test
    @DisplayName("Неизвестный документ → 404")
    void unknownDocument() {
        UUID absent = UUID.randomUUID();
        when(documentRepository.findByPublicUuid(absent)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> api.info(absent.toString()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Документ не найден");
    }

    @Test
    @DisplayName("Документ без подписей → пустой список и signed=false")
    void documentWithoutSignatures() throws Exception {
        when(documentService.getBytes(document)).thenReturn("%PDF".getBytes());

        ResponseEntity<Map<String, Object>> response = api.info(publicUuid.toString());

        assertThat(response.getBody()).containsEntry("signatureCount", 0)
                .containsEntry("signed", false)
                .containsEntry("documentName", "Договор.pdf")
                .containsEntry("sha256", "abc123");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signatures =
                (List<Map<String, Object>>) response.getBody().get("signatures");
        assertThat(signatures).isEmpty();
    }

    // ──────────────────────────── успешная проверка ────────────────────────────

    @Test
    @DisplayName("Криптоподпись и цепочка сходятся → cryptoOk=true, chainOk=true")
    void signatureWithValidChain() throws Exception {
        DocumentSignature sig = signature(SIGNATURE, false);
        byte[] content = "содержимое".getBytes();
        when(documentService.getBytes(document)).thenReturn(content);
        X509Certificate validCert = cert(new Date(System.currentTimeMillis() - 1000),
                new Date(System.currentTimeMillis() + 86_400_000L));
        when(signatureVerifier.extractSignerCert(SIGNATURE)).thenReturn(validCert);
        when(signatureVerifier.verifyDetachedBasic(content, SIGNATURE))
                .thenReturn(Map.of("valid", true));
        when(signatureVerifier.verifyDetached(content, SIGNATURE))
                .thenReturn(Map.of("valid", true));

        Map<String, Object> info = infoWith(sig);

        assertThat(info).containsEntry("cryptoOk", true)
                .containsEntry("cryptoMessage", null)
                .containsEntry("chainOk", true)
                .containsEntry("chainMessage", null)
                .containsEntry("certValidNow", true)
                .containsEntry("subject", SUBJECT)
                .containsEntry("applyPoa", false);
        assertThat(info.get("certNotBefore")).isNotNull();
        assertThat(info.get("certNotAfter")).isNotNull();
    }

    @Test
    @DisplayName("Сертификат подписанта вне срока действия → certValidNow=false")
    void signatureWithExpiredCertificate() throws Exception {
        DocumentSignature sig = signature(SIGNATURE, false);
        when(documentService.getBytes(document)).thenReturn("x".getBytes());
        X509Certificate expiredCert = cert(
                new Date(System.currentTimeMillis() - 172_800_000L),
                new Date(System.currentTimeMillis() - 86_400_000L));
        when(signatureVerifier.extractSignerCert(SIGNATURE)).thenReturn(expiredCert);
        when(signatureVerifier.verifyDetachedBasic(any(), eq(SIGNATURE)))
                .thenReturn(Map.of("valid", true));

        Map<String, Object> info = infoWith(sig);

        assertThat(info).containsEntry("certValidNow", false);
    }

    @Test
    @DisplayName("Сертификат не извлекается → полей сертификата нет")
    void signatureWithoutExtractableCertificate() throws Exception {
        DocumentSignature sig = signature(SIGNATURE, false);
        when(documentService.getBytes(document)).thenReturn("x".getBytes());
        when(signatureVerifier.extractSignerCert(SIGNATURE)).thenReturn(null);
        when(signatureVerifier.verifyDetachedBasic(any(), eq(SIGNATURE)))
                .thenReturn(Map.of("valid", false));

        Map<String, Object> info = infoWith(sig);

        assertThat(info).doesNotContainKeys("certNotBefore", "certNotAfter", "certValidNow");
        assertThat(info).containsEntry("cryptoOk", false);
    }

    // ──────────────────────────── ошибки проверки ────────────────────────────

    @Test
    @DisplayName("Подпись от другого файла → cryptoMessage о несоответствии")
    void signatureMismatch() throws Exception {
        DocumentSignature sig = signature(SIGNATURE, false);
        when(documentService.getBytes(document)).thenReturn("x".getBytes());
        when(signatureVerifier.extractSignerCert(anyString())).thenReturn(null);
        when(signatureVerifier.verifyDetachedBasic(any(), eq(SIGNATURE)))
                .thenThrow(new SignatureVerifier.SignatureMismatchException("не то"));

        Map<String, Object> info = infoWith(sig);

        assertThat(info).containsEntry("cryptoOk", false)
                .containsEntry("cryptoMessage", "Подпись не соответствует документу")
                .containsEntry("chainOk", false)
                .containsEntry("chainMessage", "Криптоподпись недействительна");
        verify(signatureVerifier, never()).verifyDetached(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("Непредвиденная ошибка проверки → текст ошибки в cryptoMessage")
    void signatureVerificationThrows() throws Exception {
        DocumentSignature sig = signature(SIGNATURE, false);
        when(documentService.getBytes(document)).thenReturn("x".getBytes());
        when(signatureVerifier.extractSignerCert(anyString())).thenReturn(null);
        when(signatureVerifier.verifyDetachedBasic(any(), eq(SIGNATURE)))
                .thenThrow(new IllegalStateException("провайдер недоступен"));

        Map<String, Object> info = infoWith(sig);

        assertThat(info).containsEntry("cryptoOk", false);
        assertThat((String) info.get("cryptoMessage"))
                .isEqualTo("Не удалось проверить: провайдер недоступен");
    }

    @Test
    @DisplayName("verifyDetachedBasic вернул valid=false → цепочка не проверяется")
    void signatureCryptoInvalid() throws Exception {
        DocumentSignature sig = signature(SIGNATURE, false);
        when(documentService.getBytes(document)).thenReturn("x".getBytes());
        when(signatureVerifier.extractSignerCert(anyString())).thenReturn(null);
        when(signatureVerifier.verifyDetachedBasic(any(), eq(SIGNATURE)))
                .thenReturn(Map.of("valid", false));

        Map<String, Object> info = infoWith(sig);

        assertThat(info).containsEntry("cryptoOk", false)
                .containsEntry("chainOk", false)
                .containsEntry("chainMessage", "Криптоподпись недействительна");
        verify(signatureVerifier, never()).verifyDetached(any(byte[].class), anyString());
    }

    @Test
    @DisplayName("Цепочка не проверена → chainOk=false с причиной")
    void signatureChainFails() throws Exception {
        DocumentSignature sig = signature(SIGNATURE, false);
        when(documentService.getBytes(document)).thenReturn("x".getBytes());
        when(signatureVerifier.extractSignerCert(anyString())).thenReturn(null);
        when(signatureVerifier.verifyDetachedBasic(any(), eq(SIGNATURE)))
                .thenReturn(Map.of("valid", true));
        when(signatureVerifier.verifyDetached(any(byte[].class), eq(SIGNATURE)))
                .thenThrow(new IllegalStateException("Корневой сертификат не в truststore"));

        Map<String, Object> info = infoWith(sig);

        assertThat(info).containsEntry("cryptoOk", true)
                .containsEntry("chainOk", false);
        assertThat((String) info.get("chainMessage"))
                .contains("Цепочка/отзыв не проверены")
                .contains("truststore");
    }

    @Test
    @DisplayName("Содержимое документа недоступно → понятное сообщение")
    void contentUnavailable() throws Exception {
        DocumentSignature sig = signature(SIGNATURE, false);
        when(documentService.getBytes(document)).thenThrow(new IOException("нет файла"));
        when(signatureVerifier.extractSignerCert(anyString())).thenReturn(null);

        Map<String, Object> info = infoWith(sig);

        assertThat(info).containsEntry("cryptoOk", false)
                .containsEntry("cryptoMessage", "Не удалось получить содержимое документа")
                .containsEntry("chainOk", false);
        verify(signatureVerifier, never()).verifyDetachedBasic(any(), anyString());
    }

    @Test
    @DisplayName("Подпись без base64 → сообщение о недоступном содержимом")
    void signatureWithoutBase64() throws Exception {
        DocumentSignature sig = signature(SIGNATURE, false);
        sig.setSignatureBase64(null);
        when(documentService.getBytes(document)).thenReturn("x".getBytes());
        when(signatureVerifier.extractSignerCert(null)).thenReturn(null);

        Map<String, Object> info = infoWith(sig);

        assertThat(info).containsEntry("cryptoOk", false)
                .containsEntry("cryptoMessage", "Не удалось получить содержимое документа");
    }

    // ──────────────────────────── МЧД в подписи ────────────────────────────

    @Test
    @DisplayName("applyPoa=true и доверенность найдена → реквизиты МЧД в ответе")
    void applyPoaFound() throws Exception {
        DocumentSignature sig = signature(SIGNATURE, true);
        PowerOfAttorney poa = new PowerOfAttorney();
        poa.setId(5L);
        poa.setFio("Иванов Иван Иванович");
        poa.setStampNumber("2b1c6f44-6f1c-4c67-9c62-9d3f0e9f0001");
        poa.setValidFrom(LocalDate.of(2025, 1, 15));
        poa.setValidUntil(LocalDate.of(2030, 12, 31));
        poa.setActive(true);

        when(documentService.getBytes(document)).thenReturn("x".getBytes());
        when(signatureVerifier.extractSignerCert(anyString())).thenReturn(null);
        when(signatureVerifier.verifyDetachedBasic(any(), eq(SIGNATURE)))
                .thenReturn(Map.of("valid", true));
        when(poaService.findEffectiveByFio("Иванов Иван Иванович")).thenReturn(Optional.of(poa));

        Map<String, Object> info = infoWith(sig);

        assertThat(info).containsEntry("applyPoa", true)
                .containsEntry("poaFound", true)
                .containsEntry("poaStampNumber", "2b1c6f44-6f1c-4c67-9c62-9d3f0e9f0001")
                .containsEntry("poaValidFrom", "2025-01-15")
                .containsEntry("poaValidUntil", "2030-12-31")
                .containsEntry("poaEffective", true);
    }

    @Test
    @DisplayName("applyPoa=true и МЧД не найдена → poaFound=false")
    void applyPoaNotFound() throws Exception {
        DocumentSignature sig = signature(SIGNATURE, true);
        when(documentService.getBytes(document)).thenReturn("x".getBytes());
        when(signatureVerifier.extractSignerCert(anyString())).thenReturn(null);
        when(signatureVerifier.verifyDetachedBasic(any(), eq(SIGNATURE)))
                .thenReturn(Map.of("valid", true));
        when(poaService.findEffectiveByFio(anyString())).thenReturn(Optional.empty());

        Map<String, Object> info = infoWith(sig);

        assertThat(info).containsEntry("poaFound", false);
        assertThat(info).doesNotContainKeys("poaStampNumber", "poaEffective");
    }

    @Test
    @DisplayName("У МЧД нет дат → поля null, но poaFound=true")
    void applyPoaWithoutDates() throws Exception {
        DocumentSignature sig = signature(SIGNATURE, true);
        PowerOfAttorney poa = new PowerOfAttorney();
        poa.setStampNumber("guid");
        poa.setActive(true);

        when(documentService.getBytes(document)).thenReturn("x".getBytes());
        when(signatureVerifier.extractSignerCert(anyString())).thenReturn(null);
        when(signatureVerifier.verifyDetachedBasic(any(), eq(SIGNATURE)))
                .thenReturn(Map.of("valid", true));
        when(poaService.findEffectiveByFio(anyString())).thenReturn(Optional.of(poa));

        Map<String, Object> info = infoWith(sig);

        assertThat(info).containsEntry("poaFound", true)
                .containsEntry("poaValidFrom", null)
                .containsEntry("poaValidUntil", null);
    }

    // ──────────────────────────── extractCn ────────────────────────────

    @Test
    @DisplayName("extractCn: null, отсутствие CN и разные регистры")
    void extractCnFormats() {
        assertThat(PublicApi.extractCn(null)).isEqualTo("—");
        assertThat(PublicApi.extractCn("O=Афина, C=RU")).isEqualTo("O=Афина, C=RU");
        assertThat(PublicApi.extractCn("CN=Иванов Иван Иванович, O=Афина"))
                .isEqualTo("Иванов Иван Иванович");
        assertThat(PublicApi.extractCn("cn=\"Test User\"")).isEqualTo("Test User");
    }
}
