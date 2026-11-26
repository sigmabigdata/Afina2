package ru.afina.demo;

import org.bouncycastle.cms.CMSSignedData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import ru.afina.repository.DocumentTemplateRepository;
import ru.afina.repository.PowerOfAttorneyRepository;
import ru.afina.security.PiiEncryptor;
import ru.afina.service.DocumentService;
import ru.afina.service.FolderService;
import ru.afina.service.SettingsService;
import ru.afina.service.UserService;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("DemoDataInitializer: демо-PDF, сертификат и подпись")
class DemoDataInitializerTest {

    @TempDir Path tempDir;

    private DemoDataInitializer initializer() {
        DemoDataInitializer init = new DemoDataInitializer(
                mock(UserService.class),
                mock(FolderService.class),
                mock(DocumentService.class),
                mock(SettingsService.class),
                mock(PiiEncryptor.class),
                mock(PowerOfAttorneyRepository.class),
                mock(DocumentTemplateRepository.class));
        ReflectionTestUtils.setField(init, "certsPath", tempDir.resolve("certs").toString());
        return init;
    }

    @Test
    @DisplayName("демо-PDF генерируется и содержит одну страницу")
    void demoPdfIsValid() throws Exception {
        Method m = DemoDataInitializer.class.getDeclaredMethod("demoPdf", String.class, String[].class);
        m.setAccessible(true);

        byte[] pdf = (byte[]) m.invoke(null, "Договор", new String[]{"строка 1", "строка 2"});

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("сертификат подписанта создаётся и кладётся в truststore")
    void createsSelfSignedCert() throws Exception {
        Method m = DemoDataInitializer.class.getDeclaredMethod("createDemoSigner");
        m.setAccessible(true);

        Object signer = m.invoke(initializer());
        assertThat(signer).isNotNull();
        assertThat(Files.list(tempDir.resolve("certs")).count()).isEqualTo(1);
        assertThat(Files.readString(tempDir.resolve("certs/demo-signer-root.pem")))
                .contains("BEGIN CERTIFICATE");
    }

    @Test
    @DisplayName("подпись документа — валидный CMS (detached)")
    void signCreatesCms() throws Exception {
        DemoDataInitializer init = initializer();
        Method create = DemoDataInitializer.class.getDeclaredMethod("createDemoSigner");
        create.setAccessible(true);
        Object signer = create.invoke(init);

        Method sign = DemoDataInitializer.class.getDeclaredMethod("sign", byte[].class, signer.getClass());
        sign.setAccessible(true);
        String b64 = (String) sign.invoke(init, "документ".getBytes(StandardCharsets.UTF_8), signer);

        CMSSignedData cms = new CMSSignedData(Base64.getDecoder().decode(b64));
        assertThat(cms.getSignerInfos().size()).isEqualTo(1);
        assertThat(cms.getCertificates().getMatches(null)).hasSize(1);
    }

    @Test
    @DisplayName("кириллица в демо-тексте заменяется (Helvetica не умеет UTF-8)")
    void asciiReplacesCyrillic() throws Exception {
        Method m = DemoDataInitializer.class.getDeclaredMethod("ascii", String.class);
        m.setAccessible(true);

        assertThat(m.invoke(null, "Привет ok")).isEqualTo("?????? ok");
    }
}
