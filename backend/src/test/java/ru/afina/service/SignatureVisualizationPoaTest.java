package ru.afina.service;

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ru.afina.entity.DocumentSignature;
import ru.afina.entity.PowerOfAttorney;
import ru.afina.repository.DocumentRepository;
import ru.afina.repository.DocumentVisualizationRepository;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Штамп подписи в визуализации: при включённой доверенности должна
 * печататься строка «Доверенность №…» с реквизитами.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Визуализация: блок доверенности в штампе подписи")
class SignatureVisualizationPoaTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentVisualizationRepository visRepo;
    @Mock private DocumentService documentService;
    @Mock private PowerOfAttorneyService poaService;
    @Mock private SignatureVerifier signatureVerifier;

    @TempDir Path tempDir;

    private SignatureVisualizationService service;
    private Class<?> certInfoClass;

    @BeforeEach
    void setUp() throws Exception {
        service = new SignatureVisualizationService(documentRepository, visRepo,
                documentService, poaService, signatureVerifier,
                org.mockito.Mockito.mock(ru.afina.service.BrandingService.class),
                tempDir.toString());
        certInfoClass = Class.forName(
                "ru.afina.service.SignatureVisualizationService$CertInfo");
    }

    private Object certInfo() throws Exception {
        Constructor<?> c = certInfoClass.getDeclaredConstructors()[0];
        c.setAccessible(true);
        return c.newInstance("ABC123", Instant.now().minusSeconds(86_400),
                Instant.now().plusSeconds(86_400),
                "Дырул Анатолий Юрьевич", "Дырул Анатолий Юрьевич (ПАО Афина)", Instant.now());
    }

    private List<String> drawAndCapture(PowerOfAttorney poa, boolean applyPoa) throws Exception {
        DocumentSignature sig = new DocumentSignature();
        sig.setId(124L);
        sig.setSignedAt(Instant.now().minusSeconds(60));

        PDPageContentStream cs = mock(PDPageContentStream.class);
        PDFont font = mock(PDFont.class);
        when(font.getStringWidth(anyString())).thenReturn(1000f);

        ReflectionTestUtils.invokeMethod(service, "drawSignatureBlock",
                cs, font, sig, certInfo(), poa, applyPoa, 595f, 500f);

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(cs, atLeastOnce()).showText(texts.capture());
        return texts.getAllValues();
    }

    @Test
    @DisplayName("applyPoa=true + доверенность найдена → печатается «Доверенность №…»")
    void drawsPoaLine() throws Exception {
        PowerOfAttorney poa = new PowerOfAttorney();
        poa.setStampNumber("77/2025-123456");
        poa.setValidFrom(LocalDate.now().minusDays(10));
        poa.setValidUntil(LocalDate.now().plusDays(300));

        List<String> lines = drawAndCapture(poa, true);

        assertThat(lines)
                .anyMatch(l -> l.startsWith("Доверенность №77/2025-123456"))
                .anyMatch(l -> l.startsWith("Действительна: "));
        assertThat(lines).noneMatch(l -> l.contains("не найдены"));
    }

    @Test
    @DisplayName("applyPoa=true, но доверенность не найдена → строка-предупреждение")
    void drawsMissingPoaHint() throws Exception {
        List<String> lines = drawAndCapture(null, true);

        assertThat(lines).anyMatch(l -> l.contains("Доверенность: сведения не найдены"));
    }

    @Test
    @DisplayName("applyPoa=false → строки доверенности нет")
    void noPoaLineWhenDisabled() throws Exception {
        PowerOfAttorney poa = new PowerOfAttorney();
        poa.setStampNumber("1");

        List<String> lines = drawAndCapture(poa, false);

        assertThat(lines).noneMatch(l -> l.contains("Доверенность"));
    }
}
