package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.entity.DocumentSignature;
import ru.afina.repository.DocumentRepository;
import ru.afina.repository.DocumentVisualizationRepository;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * QR-код в визуализации рисуется по соответствию подписи документу,
 * а не по сроку действия сертификата (иначе QR исчезал у документов
 * с подписью, у которой истёк сертификат).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QR в визуализации: только проверка соответствия документу")
class SignatureVisualizationQrTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentVisualizationRepository visRepo;
    @Mock private DocumentService documentService;
    @Mock private PowerOfAttorneyService poaService;
    @Mock private SignatureVerifier signatureVerifier;

    @TempDir Path tempDir;

    private SignatureVisualizationService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SignatureVisualizationService(documentRepository, visRepo,
                documentService, poaService, signatureVerifier,
                org.mockito.Mockito.mock(ru.afina.service.BrandingService.class),
                tempDir.toString());
    }

    private static DocumentSignature sig(long id) {
        DocumentSignature s = new DocumentSignature();
        s.setId(id);
        s.setSignatureBase64("AA==");
        return s;
    }

    @Test
    @DisplayName("подпись сходится с документом → QR рисуется")
    void drawsQrWhenContentMatches() throws Exception {
        when(signatureVerifier.verifyDetachedBasic(any(), anyString()))
                .thenReturn(Map.of("valid", Boolean.TRUE, "certExpired", Boolean.TRUE));

        boolean draw = service.allSignaturesMatchContent(
                List.of(sig(1), sig(2)), "doc".getBytes());

        assertThat(draw).isTrue();
    }

    @Test
    @DisplayName(".sig от другого файла → QR не рисуется")
    void noQrWhenMismatch() throws Exception {
        when(signatureVerifier.verifyDetachedBasic(any(), anyString()))
                .thenThrow(new SignatureVerifier.SignatureMismatchException(
                        "Файл подписи не соответствует документу"));

        boolean draw = service.allSignaturesMatchContent(List.of(sig(1)), "doc".getBytes());

        assertThat(draw).isFalse();
    }

    @Test
    @DisplayName("пустой список подписей → QR не рисуется")
    void noQrWithoutSignatures() {
        assertThat(service.allSignaturesMatchContent(List.of(), new byte[]{1})).isFalse();
    }
}
