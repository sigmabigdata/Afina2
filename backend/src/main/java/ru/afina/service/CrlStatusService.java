package ru.afina.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Состояние CRL в каталоге: срок действия, подпись издателя, ближайшее истечение.
 *
 * <p>Страница {@code /admin/crl} показывает таблицу, а планировщик раз в сутки
 * пишет предупреждение и (если включены алерты) письмо, когда CRL истёк,
 * истекает в ближайшие дни или не проверяется сертификатом издателя.</p>
 */
@Service
public class CrlStatusService {

    private static final Logger log = LoggerFactory.getLogger(CrlStatusService.class);
    private static final int EXPIRING_DAYS = 7;

    /** Статус CRL для отображения и алертов. */
    public enum Status { OK, EXPIRING, EXPIRED, UNTRUSTED }

    /** Один CRL. */
    public record CrlStatusInfo(String fileName, String issuerCn, Instant thisUpdate,
                                Instant nextUpdate, String sizePretty,
                                boolean signatureVerified, Status status, String note) {
    }

    /** Сводка по каталогу. */
    public record Summary(int total, int ok, int expiring, int expired, int untrusted) {
    }

    /** Полный отчёт для страницы. */
    public record Report(List<CrlStatusInfo> items, Summary summary) {
    }

    private final Path crlsDir;
    private final Path certsDir;
    private final SettingsService settings;
    private final EmailService emailService;

    /** В демо у каждой сессии своя БД и нет фоновых задач. */
    @Value("${app.demo.enabled:false}")
    private boolean demoMode;

    public CrlStatusService(@Value("${app.crl-path:/app/crls}") String crlsPath,
                            @Value("${app.certificates-path:/app/certs}") String certsPath,
                            SettingsService settings,
                            EmailService emailService) {
        this.crlsDir = Path.of(crlsPath);
        this.certsDir = Path.of(certsPath);
        this.settings = settings;
        this.emailService = emailService;
    }

    /** Отчёт по всем CRL каталога (без сети). */
    public Report report() {
        Map<String, List<X509Certificate>> certs = loadCerts();
        List<CrlStatusInfo> items = new ArrayList<>();
        int ok = 0, expiring = 0, expired = 0, untrusted = 0;

        for (Path file : listCrlFiles()) {
            CrlStatusInfo info = inspect(file, certs);
            if (info == null) continue;
            items.add(info);
            switch (info.status()) {
                case OK -> ok++;
                case EXPIRING -> expiring++;
                case EXPIRED -> expired++;
                case UNTRUSTED -> untrusted++;
            }
        }
        items.sort(Comparator.comparing(CrlStatusInfo::nextUpdate,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return new Report(items, new Summary(items.size(), ok, expiring, expired, untrusted));
    }

    /**
     * Ежедневная проверка: истёкшие/истекающие/непроверенные CRL.
     * Алерт отправляется не чаще раза в сутки.
     */
    @Scheduled(cron = "${app.crl.monitor-cron:0 30 6 * * *}")
    public void scheduledCheck() {
        if (demoMode) return;
        Report report = report();
        Summary s = report.summary();
        if (s.expired() == 0 && s.expiring() == 0 && s.untrusted() == 0) {
            return;
        }
        String details = buildAlertDetails(report);
        log.warn("CRL: истекло={}, истекает={}, не проверено={}\n{}",
                s.expired(), s.expiring(), s.untrusted(), details);
        sendAlert(s, details);
    }

    private void sendAlert(Summary summary, String details) {
        if (!settings.getBoolOrDefault("alert.enabled", true)) return;
        String to = settings.getOrDefault("monitor.mail_to", "");
        if (to == null || to.isBlank()) return;
        String today = java.time.LocalDate.now().toString();
        if (today.equals(settings.getOrDefault("crl.last_alert_date", ""))) return;
        String subject = "Афина · CRL: истекло " + summary.expired()
                + ", истекает " + summary.expiring()
                + ", не проверено " + summary.untrusted();
        try {
            emailService.sendAlert(to, subject, details);
            settings.set("crl.last_alert_date", today, "system");
        } catch (Exception e) {
            log.warn("CRL alert email failed: {}", e.getMessage());
        }
    }

    private String buildAlertDetails(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Состояние CRL на ").append(Instant.now()).append("\n\n");
        for (CrlStatusInfo i : report.items()) {
            if (i.status() == Status.OK) continue;
            sb.append(i.status()).append(": ").append(i.fileName())
                    .append(" (issuer=").append(i.issuerCn())
                    .append(", nextUpdate=").append(i.nextUpdate())
                    .append(")\n");
        }
        return sb.toString();
    }

    private CrlStatusInfo inspect(Path file, Map<String, List<X509Certificate>> certs) {
        try (InputStream in = Files.newInputStream(file)) {
            X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(in);
            String issuer = crl.getIssuerX500Principal().getName();
            String issuerCn = cn(issuer);
            Instant thisUpdate = toInstant(crl.getThisUpdate());
            Instant nextUpdate = toInstant(crl.getNextUpdate());
            boolean verified = verifySignature(crl,
                    certs.getOrDefault(issuer, List.of()));
            Status status = status(verified, nextUpdate);
            String note = note(status, verified, nextUpdate);
            return new CrlStatusInfo(file.getFileName().toString(), issuerCn, thisUpdate,
                    nextUpdate, CrlService.prettySize(Files.size(file)), verified, status, note);
        } catch (Exception e) {
            log.debug("CRL status parse failed {}: {}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    private Status status(boolean verified, Instant nextUpdate) {
        if (!verified) return Status.UNTRUSTED;
        if (nextUpdate == null) return Status.OK;
        Instant now = Instant.now();
        if (!nextUpdate.isAfter(now)) return Status.EXPIRED;
        if (nextUpdate.isBefore(now.plus(Duration.ofDays(EXPIRING_DAYS)))) {
            return Status.EXPIRING;
        }
        return Status.OK;
    }

    private static String note(Status status, boolean verified, Instant nextUpdate) {
        return switch (status) {
            case OK -> "действует до " + nextUpdate;
            case EXPIRING -> "истекает " + nextUpdate;
            case EXPIRED -> "истёк " + nextUpdate;
            case UNTRUSTED -> verified ? "нет данных"
                    : "подпись не сходится с сертификатом издателя";
        };
    }

    private boolean verifySignature(X509CRL crl, List<X509Certificate> issuers) {
        boolean gost = crl.getSigAlgOID() != null && crl.getSigAlgOID().startsWith("1.2.643");
        for (X509Certificate issuer : issuers) {
            try {
                if (gost) {
                    crl.verify(issuer.getPublicKey(), "JCSP");
                } else {
                    crl.verify(issuer.getPublicKey());
                }
                return true;
            } catch (Exception e) {
                log.debug("CRL signature not verified ({}): {}",
                        crl.getIssuerX500Principal(), e.getMessage());
            }
        }
        return false;
    }

    private Map<String, List<X509Certificate>> loadCerts() {
        Map<String, List<X509Certificate>> bySubject = new HashMap<>();
        if (!Files.isDirectory(certsDir)) return bySubject;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(certsDir)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase();
                if (!(n.endsWith(".cer") || n.endsWith(".crt") || n.endsWith(".pem"))) continue;
                try (InputStream in = Files.newInputStream(p)) {
                    for (java.security.cert.Certificate c : cf.generateCertificates(in)) {
                        X509Certificate x = (X509Certificate) c;
                        bySubject.computeIfAbsent(x.getSubjectX500Principal().getName(),
                                k -> new ArrayList<>()).add(x);
                    }
                } catch (Exception e) {
                    log.debug("cert parse failed {}: {}", p.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("CRL status: не удалось прочитать каталог сертификатов: {}", e.getMessage());
        }
        return bySubject;
    }

    private List<Path> listCrlFiles() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(crlsDir)) return files;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(crlsDir, "*.crl")) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) files.add(p);
            }
        } catch (Exception e) {
            log.warn("CRL status: не удалось прочитать каталог CRL: {}", e.getMessage());
        }
        return files;
    }

    private static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private static String cn(String dn) {
        try {
            LdapName name = new LdapName(dn);
            for (Rdn rdn : name.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return String.valueOf(rdn.getValue());
                }
            }
        } catch (Exception e) {
            // DN нестандартный — вернём как есть
        }
        return dn;
    }
}
