package ru.afina.gosklyuch;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.afina.entity.Document;
import ru.afina.entity.GosklyuchOrder;
import ru.afina.entity.Role;
import ru.afina.entity.User;
import ru.afina.gosklyuch.client.FakeGosklyuchClient;
import ru.afina.gosklyuch.client.GosklyuchFile;
import ru.afina.repository.GosklyuchOrderRepository;
import ru.afina.repository.UserRepository;
import ru.afina.service.AuditService;
import ru.afina.service.ConsentService;
import ru.afina.service.DocumentService;
import ru.afina.service.SignatureVerifier;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Проверка реального пути применения подписи Госключа: клиент возвращает
 * настоящую отсоединённую CMS-подпись (BouncyCastle, self-signed сертификат),
 * сервис проверяет её «как из ЕПГУ» и прикладывает к документу при
 * {@code app.gosklyuch.apply-signature=true}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Госключ: применение реальной отсоединённой подписи")
class GosklyuchSignatureApplicationTest {

    private static final byte[] CONTENT = "%PDF-1.4 демонстрационный документ".getBytes(StandardCharsets.UTF_8);

    static KeyPair keyPair;
    static X509Certificate cert;

    @TempDir Path tempDir;

    @Mock private GosklyuchOrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private DocumentService documentService;
    @Mock private ConsentService consentService;
    @Mock private AuditService audit;

    private GosklyuchProperties properties;
    private SignatureVerifier verifier;
    private Document doc;
    private User owner;

    @BeforeAll
    static void generateCert() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=Иванов Иван, O=AfinaZ, C=RU");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.currentTimeMillis()),
                new Date(System.currentTimeMillis() - 3_600_000L),
                new Date(System.currentTimeMillis() + 86_400_000L),
                subject, keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        cert = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
    }

    @BeforeEach
    void setUp() throws Exception {
        Path certs = tempDir.resolve("certs");
        Path crls = tempDir.resolve("crls");
        Files.createDirectories(certs);
        Files.createDirectories(crls);
        Files.write(certs.resolve("test-root.cer"), cert.getEncoded());
        verifier = new SignatureVerifier(certs.toString(), crls.toString(), null);

        properties = new GosklyuchProperties();
        properties.setEnabled(true);
        properties.setProvider("fake");
        properties.setRegion("45000000000");
        properties.setServiceCode("10000000374");
        properties.setTargetCode("-10000000374");
        properties.setBacklink("https://lk.gosuslugi.ru/notifications");
        properties.setSignExpiration(Duration.ofHours(24));
        properties.setApplySignature(true);

        owner = new User();
        owner.setId(10L);
        owner.setEmail("client@example.com");
        owner.setRole(Role.ROLE_USER);
        owner.setSnils("11223344595");

        doc = new Document();
        doc.setId(1L);
        doc.setOwner(owner);
        doc.setOriginalName("Согласие.pdf");
        doc.setContentType("application/pdf");

        lenient().when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(documentService.getById(1L)).thenReturn(doc);
        lenient().when(documentService.getBytes(doc)).thenReturn(CONTENT);
        lenient().when(userRepository.findById(10L)).thenReturn(Optional.of(owner));
    }

    @Test
    @DisplayName("Подпись из Госключа проходит проверку и прикладывается к документу")
    void appliesRealSignature() throws Exception {
        String cms = signDetached(CONTENT);
        GosklyuchService service = service(clientReturning(Base64.getDecoder().decode(cms)));

        GosklyuchOrder order = service.submit(doc, owner);
        service.refresh(order);

        assertThat(order.getStatus()).isEqualTo(GosklyuchStatus.SIGNED);
        verify(documentService).addSignature(eq(1L), isNull(), anyString(),
                contains("Иванов Иван"), anyString());
    }

    @Test
    @DisplayName("Подпись от другого файла не принимается и не прикладывается")
    void rejectsForeignSignature() throws Exception {
        String cms = signDetached("другой документ".getBytes(StandardCharsets.UTF_8));
        GosklyuchService service = service(clientReturning(Base64.getDecoder().decode(cms)));

        GosklyuchOrder order = service.submit(doc, owner);
        service.refresh(order);

        assertThat(order.getStatus()).isEqualTo(GosklyuchStatus.SIGNED);
        assertThat(order.getError()).contains("не принята");
        verify(documentService, never()).addSignature(any(), any(), anyString(), anyString(), anyString());
    }

    // ── инфраструктура теста ────────────────────────────────

    private GosklyuchService service(FakeGosklyuchClient client) {
        return new GosklyuchService(properties, orderRepository, userRepository, documentService,
                consentService, verifier,
                org.mockito.Mockito.mock(ru.afina.service.CrlOnDemandService.class),
                org.mockito.Mockito.mock(ru.afina.service.EmailService.class),
                org.mockito.Mockito.mock(ru.afina.service.SignatureVisualizationService.class),
                audit, new GosklyuchRequestXmlBuilder(),
                GosklyuchPackageBuilder.from((name, data) -> new byte[]{1, 2, 3}, properties),
                client, (name, data) -> new byte[]{1, 2, 3});
    }

    /** Клиент, возвращающий заданную «подпись» вместо .sig из Госключа. */
    private FakeGosklyuchClient clientReturning(byte[] signature) {
        return new FakeGosklyuchClient() {
            @Override
            public byte[] downloadFile(long orderId, GosklyuchFile file) {
                return signature.clone();
            }
        };
    }

    private static String signDetached(byte[] content) throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                .build(cs, cert));
        gen.addCertificates(new JcaCertStore(List.of(cert)));
        CMSSignedData cms = gen.generate(new CMSProcessableByteArray(content), false);
        return Base64.getEncoder().encodeToString(cms.getEncoded());
    }
}
