package ru.afina.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.afina.service.AuditService;
import ru.afina.service.BrandingService;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Белый лейбл: настройки внешнего вида (название, подпись, логотип).
 *
 * <p>Публичная часть отдаётся без авторизации — она нужна на странице входа
 * и в шапке кабинета. Изменения доступны только администратору.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class BrandingApi {

    private static final Logger log = LoggerFactory.getLogger(BrandingApi.class);

    private final BrandingService branding;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public BrandingApi(BrandingService branding, CurrentUser currentUser, AuditService audit) {
        this.branding = branding;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ═══════════════════════════ Публичное ═══════════════════════════

    /** Название, подпись и ссылка на логотип (если загружен). */
    @GetMapping("/public/branding")
    public Map<String, Object> branding() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", branding.name());
        body.put("tagline", branding.tagline());
        body.put("hasLogo", branding.hasCustomLogo());
        body.put("logoUrl", branding.hasCustomLogo()
                ? "/api/v1/public/branding/logo?v=" + branding.logoVersion()
                : null);
        return body;
    }

    /** Файл логотипа заказчика. */
    @GetMapping("/public/branding/logo")
    public ResponseEntity<ByteArrayResource> logo() throws IOException {
        var path = branding.logoPath();
        if (path.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        byte[] data = Files.readAllBytes(path.get());
        MediaType type;
        try {
            type = MediaType.parseMediaType(branding.logoContentType());
        } catch (Exception e) {
            type = MediaType.IMAGE_PNG;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(new ByteArrayResource(data));
    }

    // ═══════════════════════════ Админка ═══════════════════════════

    /** Текущие настройки брендинга для админки. */
    @GetMapping("/admin/branding")
    public Map<String, Object> adminBranding(Authentication auth) {
        currentUser.requireAdmin(auth);
        Map<String, Object> body = new LinkedHashMap<>(branding());
        body.put("defaultName", BrandingService.DEFAULT_NAME);
        body.put("defaultTagline", BrandingService.DEFAULT_TAGLINE);
        body.put("maxLogoBytes", BrandingService.MAX_LOGO_BYTES);
        return body;
    }

    /** Название и подпись. */
    @PutMapping("/admin/branding")
    public Map<String, Object> updateBranding(@RequestBody BrandingRequest request,
                                              Authentication auth) {
        var admin = currentUser.requireAdmin(auth);
        branding.updateTexts(request.name(), request.tagline(), admin.getEmail());
        audit.event("BRANDING_UPDATE", "SUCCESS", admin.getEmail(), "SETTINGS", "brand",
                request.name(), "Обновлены название и подпись");
        return branding();
    }

    /** Загрузка логотипа (PNG/JPEG/WebP/SVG, до 512 КБ). */
    @PostMapping(value = "/admin/branding/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadLogo(@RequestPart("file") MultipartFile file,
                                          Authentication auth) throws IOException {
        var admin = currentUser.requireAdmin(auth);
        String version = branding.saveLogo(file.getOriginalFilename(), file.getContentType(),
                file.getBytes(), admin.getEmail());
        audit.event("BRANDING_LOGO_UPLOAD", "SUCCESS", admin.getEmail(), "SETTINGS", "brand",
                file.getOriginalFilename(), "Логотип заказчика обновлён (v" + version + ")");
        Map<String, Object> body = new LinkedHashMap<>(branding());
        body.put("version", version);
        return body;
    }

    @DeleteMapping("/admin/branding/logo")
    public Map<String, Object> deleteLogo(Authentication auth) throws IOException {
        var admin = currentUser.requireAdmin(auth);
        branding.deleteLogo(admin.getEmail());
        audit.event("BRANDING_LOGO_DELETE", "SUCCESS", admin.getEmail(), "SETTINGS", "brand",
                null, "Логотип заказчика удалён");
        return branding();
    }

    /** Полный сброс брендинга к стандартному оформлению. */
    @DeleteMapping("/admin/branding")
    public Map<String, Object> resetBranding(Authentication auth) throws IOException {
        var admin = currentUser.requireAdmin(auth);
        branding.reset(admin.getEmail());
        audit.event("BRANDING_RESET", "SUCCESS", admin.getEmail(), "SETTINGS", "brand",
                null, "Брендинг сброшен к стандартному");
        log.info("Брендинг сброшен администратором {}", admin.getEmail());
        return branding();
    }

    /** Тело запроса названия и подписи. */
    public record BrandingRequest(String name, String tagline) {
    }
}
