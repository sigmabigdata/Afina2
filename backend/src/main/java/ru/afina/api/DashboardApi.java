package ru.afina.api;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.afina.api.dto.DashboardDto;
import ru.afina.entity.Document;
import ru.afina.entity.GosklyuchOrder;
import ru.afina.entity.User;
import ru.afina.gosklyuch.GosklyuchService;
import ru.afina.repository.DocumentRepository;
import ru.afina.service.FolderService;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Сводка для главного экрана кабинета. */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardApi {

    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final DocumentRepository documentRepository;
    private final FolderService folderService;
    private final GosklyuchService gosklyuchService;
    private final CurrentUser currentUser;
    private final ApiMapper mapper;

    public DashboardApi(DocumentRepository documentRepository,
                        FolderService folderService,
                        GosklyuchService gosklyuchService,
                        CurrentUser currentUser,
                        ApiMapper mapper) {
        this.documentRepository = documentRepository;
        this.folderService = folderService;
        this.gosklyuchService = gosklyuchService;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    @GetMapping
    public DashboardDto dashboard(Authentication auth,
                                  @RequestParam(defaultValue = "7") int days) {
        User user = currentUser.entity(auth);
        int window = Math.min(Math.max(days, 1), 90);

        List<Document> docs = documentRepository.findAllByOwnerOrderByUploadedAtDesc(user);
        long signed = docs.stream().filter(Document::isSigned).count();
        long pending = docs.stream()
                .filter(d -> gosklyuchService.hasPendingOrder(d.getId()))
                .count();

        Map<Long, GosklyuchOrder> orders = docs.isEmpty() ? Map.of()
                : gosklyuchService.latestByDocumentIds(
                        docs.stream().map(Document::getId).toList());

        List<DashboardDto.Point> activity = buildActivity(docs, window);

        List<Document> recent = docs.stream().limit(6).toList();

        List<ru.afina.api.dto.DocumentDto> recentDtos = recent.stream()
                .map(d -> mapper.document(d, orders.get(d.getId())))
                .toList();

        List<GosklyuchOrder> recentOrders = gosklyuchService
                .recentForOwner(user.getId(), 6);

        Instant todayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        long signaturesToday = docs.stream()
                .filter(d -> d.getSignedAt() != null && d.getSignedAt().isAfter(todayStart))
                .count();

        return new DashboardDto(
                docs.size(), signed, docs.size() - signed,
                folderService.listForOwner(user).size(),
                pending, signaturesToday,
                averageSigningTime(docs),
                activity, recentDtos,
                recentOrders.stream().map(mapper::order).toList());
    }

    /** Среднее время от загрузки до первой подписи. */
    static String averageSigningTime(List<Document> docs) {
        List<Long> minutes = docs.stream()
                .filter(d -> d.getSignedAt() != null && d.getUploadedAt() != null)
                .map(d -> Duration.between(d.getUploadedAt(), d.getSignedAt()).toMinutes())
                .filter(m -> m >= 0)
                .toList();
        if (minutes.isEmpty()) return "—";
        double avg = minutes.stream().mapToLong(Long::longValue).average().orElse(0);
        if (avg < 60) {
            return String.format(RU, "%.0f мин", avg);
        }
        double hours = avg / 60.0;
        if (hours < 24) {
            return String.format(RU, "%.1f ч", hours);
        }
        return String.format(RU, "%.1f дн", hours / 24.0);
    }

    /** Ряд «загружено / подписано» по дням. */
    static List<DashboardDto.Point> buildActivity(List<Document> docs, int days) {
        LocalDate today = LocalDate.now(ZONE);
        List<DashboardDto.Point> points = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            Instant from = day.atStartOfDay(ZONE).toInstant();
            Instant to = day.plusDays(1).atStartOfDay(ZONE).toInstant();
            long uploaded = docs.stream()
                    .filter(d -> d.getUploadedAt() != null
                            && !d.getUploadedAt().isBefore(from)
                            && d.getUploadedAt().isBefore(to))
                    .count();
            long signed = docs.stream()
                    .filter(d -> d.getSignedAt() != null
                            && !d.getSignedAt().isBefore(from)
                            && d.getSignedAt().isBefore(to))
                    .count();
            String label = day.getDayOfWeek().getDisplayName(TextStyle.SHORT, RU);
            points.add(new DashboardDto.Point(from, label, signed, uploaded));
        }
        return points;
    }

    /** Разбивка по типам файлов (для виджетов админки). */
    @GetMapping("/stats")
    public Map<String, Object> stats(Authentication auth) {
        User user = currentUser.entity(auth);
        List<Document> docs = documentRepository.findAllByOwnerOrderByUploadedAtDesc(user);
        Map<String, Long> byExtension = docs.stream()
                .collect(Collectors.groupingBy(
                        d -> ApiMapper.extensionOf(d.getOriginalName()),
                        Collectors.counting()));
        // Считаем только подписи своих документов: глобальный count() раскрывал бы
        // агрегат по другим клиентам
        long signatures = docs.stream().mapToLong(d -> d.getSignatures().size()).sum();
        return Map.of(
                "documents", docs.size(),
                "byExtension", byExtension,
                "signatures", signatures);
    }
}
