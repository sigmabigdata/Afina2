package ru.afina.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.afina.api.dto.GosklyuchOrderDto;
import ru.afina.entity.GosklyuchOrder;
import ru.afina.entity.User;
import ru.afina.gosklyuch.GosklyuchDiagnostics;
import ru.afina.gosklyuch.GosklyuchProperties;
import ru.afina.gosklyuch.GosklyuchService;
import ru.afina.gosklyuch.GosklyuchStatus;
import ru.afina.gosklyuch.GosklyuchUnavailableException;
import ru.afina.util.FileNames;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Админский API интеграции с МП «Госключ»: состояние подключения,
 * заявки на подписание, ручное обновление статусов и применение подписей.
 *
 * <p>Все методы требуют роль {@code ROLE_ADMIN}. Пока интеграция выключена
 * ({@link GosklyuchService#isEnabled()} == {@code false}), список заявок
 * отдаётся пустым, а действия завершаются {@code 404}: в интерфейсе
 * соответствующие кнопки не отображаются.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/gosklyuch")
public class AdminGosklyuchApi {

    /** Верхняя граница выборки заявок за один запрос. */
    private static final int MAX_ORDERS = 1000;

    private final GosklyuchService gosklyuchService;
    private final GosklyuchProperties properties;
    private final GosklyuchDiagnostics diagnostics;
    private final CurrentUser currentUser;
    private final ApiMapper mapper;

    public AdminGosklyuchApi(GosklyuchService gosklyuchService,
                             GosklyuchProperties properties,
                             GosklyuchDiagnostics diagnostics,
                             CurrentUser currentUser,
                             ApiMapper mapper) {
        this.gosklyuchService = gosklyuchService;
        this.properties = properties;
        this.diagnostics = diagnostics;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    /**
     * Состояние интеграции и отчёт самопроверки конфигурации.
     *
     * <p>Сетевых запросов к ЕПГУ не выполняет: только локальные настройки.
     * Отчёт доступен и при выключенной интеграции — чтобы администратор
     * видел, чего не хватает для включения.</p>
     */
    @GetMapping("/status")
    public Map<String, Object> status(Authentication auth) {
        currentUser.requireAdmin(auth);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", gosklyuchService.isEnabled());
        body.put("provider", properties.getProvider());
        body.put("readyToSend", gosklyuchService.isReadyToSend());
        body.put("maxDocuments", gosklyuchService.maxDocuments());
        body.put("signer", properties.getSigner());
        body.put("diagnostics", diagnostics.check());
        return body;
    }

    /**
     * Заявки для админки, новые первыми.
     *
     * @param status необязательный фильтр по статусу; неизвестное значение
     *               игнорируется (как в эталонной реализации)
     * @param limit  максимум записей, по умолчанию 100, не более {@value #MAX_ORDERS}
     */
    @GetMapping("/orders")
    public List<GosklyuchOrderDto> orders(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "100") int limit,
                                          Authentication auth) {
        currentUser.requireAdmin(auth);
        if (!gosklyuchService.isEnabled()) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), MAX_ORDERS);
        return gosklyuchService.adminOrders(parseStatus(status), safeLimit).stream()
                .map(mapper::order)
                .toList();
    }

    /** Одна заявка с документом, владельцем и позициями пакета. */
    @GetMapping("/orders/{id}")
    public GosklyuchOrderDto order(@PathVariable Long id, Authentication auth) {
        currentUser.requireAdmin(auth);
        return mapper.order(requireOrder(id));
    }

    /** Обновить статус одной заявки по данным ЕПГУ и вернуть её актуальное состояние. */
    @PostMapping("/orders/{id}/refresh")
    public GosklyuchOrderDto refresh(@PathVariable Long id, Authentication auth) {
        currentUser.requireAdmin(auth);
        requireEnabled();
        try {
            gosklyuchService.refreshOrder(id);
        } catch (GosklyuchUnavailableException e) {
            throw new IllegalStateException("Госключ: " + e.getMessage());
        }
        return mapper.order(requireOrder(id));
    }

    /**
     * Применить сохранённую подпись заявки к документу(ам).
     *
     * <p>Если хотя бы одна подпись не прошла проверку, заявка остаётся
     * с ошибкой, а клиент получает {@code 409} с её причиной.</p>
     */
    @PostMapping("/orders/{id}/apply")
    public Map<String, Object> apply(@PathVariable Long id, Authentication auth) {
        User admin = currentUser.requireAdmin(auth);
        requireEnabled();
        boolean applied;
        try {
            applied = gosklyuchService.applyStoredSignature(id, admin);
        } catch (GosklyuchUnavailableException e) {
            throw new IllegalStateException("Госключ: " + e.getMessage());
        }
        if (!applied) {
            String reason = gosklyuchService.findOrder(id)
                    .map(GosklyuchOrder::getError)
                    .orElse(null);
            throw new IllegalStateException("Подпись не применена: "
                    + (reason == null || reason.isBlank() ? "ошибка проверки" : reason));
        }
        return Map.of("applied", true);
    }

    /** Опросить все незавершённые заявки; возвращает число обработанных. */
    @PostMapping("/refresh-all")
    public Map<String, Object> refreshAll(Authentication auth) {
        currentUser.requireAdmin(auth);
        requireEnabled();
        return Map.of("updated", gosklyuchService.refreshPending());
    }

    /**
     * Скачать полученную от Госключа подпись ({@code .sig}).
     *
     * <p>Файл называется как документ заявки с расширением {@code .sig};
     * если имя документа неизвестно — {@code gosklyuch_order_<id>.sig}.</p>
     */
    @GetMapping("/orders/{id}/signature")
    public ResponseEntity<byte[]> signature(@PathVariable Long id, Authentication auth) {
        currentUser.requireAdmin(auth);
        GosklyuchOrder order = requireOrder(id);
        String base64 = order.getSignatureBase64();
        if (base64 == null || base64.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Подпись ещё не получена");
        }
        byte[] bytes = Base64.getDecoder().decode(base64.replaceAll("\\s+", ""));
        String documentName = order.getDocument() == null ? null
                : order.getDocument().getOriginalName();
        String fileName = (documentName == null || documentName.isBlank()
                ? "gosklyuch_order_" + id : documentName) + ".sig";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, FileNames.attachment(fileName))
                .body(bytes);
    }

    // ─────────────────────────── внутреннее ───────────────────────────

    private GosklyuchOrder requireOrder(Long id) {
        return gosklyuchService.findOrder(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Заявка не найдена"));
    }

    /** Действия доступны только при включённой интеграции. */
    private void requireEnabled() {
        if (!gosklyuchService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Интеграция с Госключом выключена");
        }
    }

    /** Разбор статуса без ошибки: неизвестное значение — без фильтра. */
    private static GosklyuchStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return GosklyuchStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
