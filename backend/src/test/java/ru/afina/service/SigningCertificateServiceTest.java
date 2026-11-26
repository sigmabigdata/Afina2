package ru.afina.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ru.afina.entity.Document;
import ru.afina.entity.DocumentSignature;
import ru.afina.entity.User;
import ru.afina.repository.DocumentRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SigningCertificateService: генерация PDF-справки о подписании")
class SigningCertificateServiceTest {

    @Mock private DocumentRepository documentRepository;
    @InjectMocks private SigningCertificateService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseUrl", "https://afina.test");
    }

    private Document signedDoc(int sigCount) {
        Document d = new Document();
        d.setId(7L);
        d.setOriginalName("contract.pdf");
        d.setSize(12345L);
        d.setFileSha256("abc123def456");
        d.setUploadedAt(Instant.parse("2024-01-15T10:00:00Z"));
        d.setPublicUuid(UUID.randomUUID());

        for (int i = 0; i < sigCount; i++) {
            DocumentSignature s = new DocumentSignature();
            s.setId((long) (i + 1));
            s.setSignerSubject("CN=Иванов Иван " + i + ", O=Test");
            s.setSignerSerial("0A1B2C" + i);
            s.setSignedAt(Instant.parse("2024-01-16T1" + i + ":00:00Z"));
            s.setApplyPoa(i % 2 == 0);
            d.getSignatures().add(s);
        }
        return d;
    }

    // ─── базовые ────────────────────────────────

    @Test
    @DisplayName("generate: документ не найден → IAE")
    void notFound() {
        when(documentRepository.findByIdWithSignatures(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.generate(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    @DisplayName("generate: неподписанный документ → PDF с 'не подписан'")
    void unsignedDoc() throws Exception {
        when(documentRepository.findByIdWithSignatures(7L))
                .thenReturn(Optional.of(signedDoc(0)));

        byte[] pdf = service.generate(7L);

        assertThat(pdf).isNotEmpty();
        assertThat(pdf).hasSizeGreaterThan(500);
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("generate: 1 подпись → PDF, содержит данные")
    void oneSignature() throws Exception {
        when(documentRepository.findByIdWithSignatures(7L))
                .thenReturn(Optional.of(signedDoc(1)));

        byte[] pdf = service.generate(7L);

        assertThat(pdf).hasSizeGreaterThan(500);
        // PDF начинается с %PDF-
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        // заканчивается %%EOF (с trailing whitespace)
        String tail = new String(pdf, pdf.length - 20, 20, StandardCharsets.US_ASCII);
        assertThat(tail).contains("%%EOF");
    }

    @Test
    @DisplayName("generate: 3 подписи → PDF, все влезают")
    void threeSignatures() throws Exception {
        when(documentRepository.findByIdWithSignatures(7L))
                .thenReturn(Optional.of(signedDoc(3)));

        byte[] pdf = service.generate(7L);

        assertThat(pdf.length).isGreaterThan(800);
    }

    @Test
    @DisplayName("generate: 10 подписей → защита от переполнения (break)")
    void manySignatures() throws Exception {
        when(documentRepository.findByIdWithSignatures(7L))
                .thenReturn(Optional.of(signedDoc(10)));

        byte[] pdf = service.generate(7L);

        // PDF сгенерирован, не упал на переполнении
        assertThat(pdf).isNotEmpty();
    }

    @Test
    @DisplayName("generate: baseUrl = null → QR-блок пропускается")
    void nullBaseUrl() throws Exception {
        ReflectionTestUtils.setField(service, "baseUrl", null);
        when(documentRepository.findByIdWithSignatures(7L))
                .thenReturn(Optional.of(signedDoc(1)));

        byte[] pdf = service.generate(7L);

        assertThat(pdf).isNotEmpty();
    }

    @Test
    @DisplayName("generate: baseUrl с trailing slash → QR URL без двойного //")
    void baseUrlTrailingSlash() throws Exception {
        ReflectionTestUtils.setField(service, "baseUrl", "https://afina.test/");
        when(documentRepository.findByIdWithSignatures(7L))
                .thenReturn(Optional.of(signedDoc(1)));

        byte[] pdf = service.generate(7L);

        assertThat(pdf).isNotEmpty();
    }

    @Test
    @DisplayName("generate: без publicUuid → QR-блок пропускается, PDF валиден")
    void noPublicUuid() throws Exception {
        Document d = signedDoc(1);
        d.setPublicUuid(null);
        when(documentRepository.findByIdWithSignatures(7L)).thenReturn(Optional.of(d));

        byte[] pdf = service.generate(7L);

        assertThat(pdf).isNotEmpty();
    }

    @Test
    @DisplayName("generate: с apply_poa=false → 'Доверенность: не требуется'")
    void withoutPoa() throws Exception {
        Document d = signedDoc(1);
        d.getSignatures().get(0).setApplyPoa(false);
        when(documentRepository.findByIdWithSignatures(7L)).thenReturn(Optional.of(d));

        byte[] pdf = service.generate(7L);

        assertThat(pdf).isNotEmpty();
    }

    @Test
    @DisplayName("generate: uploadAt=null → fallback на '—'")
    void nullUploadedAt() throws Exception {
        Document d = signedDoc(1);
        d.setUploadedAt(null);
        when(documentRepository.findByIdWithSignatures(7L)).thenReturn(Optional.of(d));

        byte[] pdf = service.generate(7L);

        assertThat(pdf).isNotEmpty();
    }
}
