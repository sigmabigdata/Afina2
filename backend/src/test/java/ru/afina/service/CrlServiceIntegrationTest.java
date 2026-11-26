package ru.afina.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CrlService: интеграция с реальными CRL")
class CrlServiceIntegrationTest {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @TempDir
    Path tmpDir;

    private CrlService service;
    private static KeyPair caKeyPair;

    @BeforeAll
    static void generateCa() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        caKeyPair = kpg.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        service = new CrlService(tmpDir.toString());
    }

    private Path writeCrl(String fileName, String issuerCn, int daysValid) throws Exception {
        X500Name issuer = new X500Name("CN=" + issuerCn + ", O=Test, C=RU");
        Date now = new Date();
        Date nextUpdate = new Date(now.getTime() + TimeUnit.DAYS.toMillis(daysValid));

        X509v2CRLBuilder builder = new X509v2CRLBuilder(issuer, now);
        builder.setNextUpdate(nextUpdate);
        builder.addCRLEntry(BigInteger.valueOf(12345), now, 1);

        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(caKeyPair.getPrivate());
        var crlHolder = builder.build(signer);

        var crl = new JcaX509CRLConverter().setProvider("BC").getCRL(crlHolder);
        Path f = tmpDir.resolve(fileName);
        Files.write(f, crl.getEncoded());
        return f;
    }

    @Test
    @DisplayName("listAll: 1 реальный CRL → все поля заполнены")
    void listAllOneRealCrl() throws Exception {
        writeCrl("manual-bank.crl", "Bank CA", 30);

        var list = service.listAll();
        assertThat(list).hasSize(1);
        var info = list.get(0);
        assertThat(info.fileName()).isEqualTo("manual-bank.crl");
        assertThat(info.issuer()).contains("Bank CA");
        assertThat(info.lastUpdate()).isNotNull();
        assertThat(info.nextUpdate()).isNotNull();
        assertThat(info.daysToExpiry()).isPositive();
        assertThat(info.manual()).isTrue();
        assertThat(info.sizeBytes()).isGreaterThan(100);
    }

    @Test
    @DisplayName("listAll: auto-префикс → manual=false")
    void autoCrlMarkedManualFalse() throws Exception {
        writeCrl("auto-abc123.crl", "CA", 30);
        assertThat(service.listAll().get(0).manual()).isFalse();
    }

    @Test
    @DisplayName("listAll: сортировка по issuer")
    void listAllSorted() throws Exception {
        writeCrl("z.crl", "AAA", 30);
        writeCrl("a.crl", "ZZZ", 30);
        assertThat(service.listAll()).extracting(i -> i.issuer()).isSorted();
    }

    @Test
    @DisplayName("listAll: битый файл → пропускается, не падает")
    void listAllSkipsBroken() throws Exception {
        writeCrl("good.crl", "CA", 30);
        Files.writeString(tmpDir.resolve("bad.crl"), "not a crl");
        var list = service.listAll();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).fileName()).isEqualTo("good.crl");
    }

    @Test
    @DisplayName("getTotalSize + getCount: 2 CRL")
    void sizeAndCount() throws Exception {
        writeCrl("a.crl", "A", 30);
        writeCrl("b.crl", "B", 30);
        assertThat(service.getTotalSize()).isGreaterThan(200);
        assertThat(service.getCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("deleteAllAuto: удаляет auto-*.crl, ручные остаются")
    void deleteAllAutoKeepsManual() throws Exception {
        writeCrl("manual.crl", "M", 30);
        writeCrl("auto-x.crl", "A", 30);
        writeCrl("auto-y.crl", "B", 30);

        assertThat(service.deleteAllAuto()).isEqualTo(2);
        assertThat(Files.exists(tmpDir.resolve("manual.crl"))).isTrue();
        assertThat(Files.exists(tmpDir.resolve("auto-x.crl"))).isFalse();
        assertThat(Files.exists(tmpDir.resolve("auto-y.crl"))).isFalse();
    }

    @Test
    @DisplayName("deleteAllAuto: только ручные → 0")
    void deleteAllAutoOnlyManual() throws Exception {
        writeCrl("manual.crl", "M", 30);
        assertThat(service.deleteAllAuto()).isZero();
    }

    @Test
    @DisplayName("deleteMany: 2 реальных → n=2")
    void deleteManyReal() throws Exception {
        writeCrl("x.crl", "X", 30);
        writeCrl("y.crl", "Y", 30);
        assertThat(service.deleteMany(List.of("x.crl", "y.crl"))).isEqualTo(2);
        assertThat(service.getCount()).isZero();
    }

    @Test
    @DisplayName("saveManual: реальный CRL → файл записан, listAll видит")
    void saveManualReal() throws Exception {
        Path tmp = writeCrl("from-builder.crl", "Saved CA", 60);
        byte[] data = Files.readAllBytes(tmp);
        Files.delete(tmp);

        service.saveManual("uploaded.crl", data);
        assertThat(Files.exists(tmpDir.resolve("uploaded.crl"))).isTrue();

        var info = service.listAll();
        assertThat(info).hasSize(1);
        assertThat(info.get(0).issuer()).contains("Saved CA");
    }
}
