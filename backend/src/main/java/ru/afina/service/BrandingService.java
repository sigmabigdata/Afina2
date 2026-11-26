package ru.afina.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Белый лейбл: название, подпись и логотип заказчика.
 *
 * <p>Логотип хранится файлом в каталоге брендинга (рядом с документами),
 * а его версия — в настройках ({@code brand.logo_version}). Версия нужна,
 * чтобы браузер не показывал старую картинку из кеша после замены.</p>
 *
 * <p>Файл проверяется как изображение (PNG/JPEG/SVG), ограничение — 512 КБ.
 * SVG принимается как есть (это вектор, его отдаёт браузер), но проверяется
 * на отсутствие скриптов.</p>
 */
@Service
public class BrandingService {

    private static final Logger log = LoggerFactory.getLogger(BrandingService.class);

    public static final String KEY_NAME = "brand.name";
    public static final String KEY_TAGLINE = "brand.tagline";
    public static final String KEY_LOGO_VERSION = "brand.logo_version";

    public static final String DEFAULT_NAME = "Афина";
    public static final String DEFAULT_TAGLINE = "Документы · подпись · Госключ";

    /** Ограничение размера файла логотипа. */
    public static final int MAX_LOGO_BYTES = 512 * 1024;

    private final SettingsService settings;
    private final Path brandingDir;

    public BrandingService(SettingsService settings,
                           @Value("${app.storage-path}") String storagePath) throws IOException {
        this.settings = settings;
        Path storage = Paths.get(storagePath).toAbsolutePath().normalize();
        this.brandingDir = storage.resolveSibling("branding");
        Files.createDirectories(brandingDir);
    }

    // ═══════════════════════════ Чтение ═══════════════════════════

    public String name() {
        String value = settings.getOrDefault(KEY_NAME, "");
        return value.isBlank() ? DEFAULT_NAME : value;
    }

    public String tagline() {
        String value = settings.getOrDefault(KEY_TAGLINE, "");
        return value.isBlank() ? DEFAULT_TAGLINE : value;
    }

    /** Версия логотипа: пусто, если логотип не загружен. */
    public String logoVersion() {
        return settings.getOrDefault(KEY_LOGO_VERSION, "");
    }

    public boolean hasCustomLogo() {
        return !logoVersion().isBlank() && logoPath().isPresent();
    }

    public Optional<Path> logoPath() {
        String version = logoVersion();
        if (version.isBlank()) return Optional.empty();
        try (var stream = Files.list(brandingDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("logo-" + version + "."))
                    .findFirst();
        } catch (IOException e) {
            log.warn("Не удалось прочитать каталог брендинга: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** MIME-тип логотипа для отдачи в браузер. */
    public String logoContentType() {
        return logoPath()
                .map(p -> contentTypeOf(p.getFileName().toString()))
                .orElse("image/png");
    }

    // ═══════════════════════════ Запись ═══════════════════════════

    public void updateTexts(String name, String tagline, String actor) {
        settings.set(KEY_NAME, name == null ? "" : name.trim(), actor);
        settings.set(KEY_TAGLINE, tagline == null ? "" : tagline.trim(), actor);
    }

    /**
     * Сохраняет логотип. Возвращает новую версию (для URL с кеш-бастингом).
     */
    public String saveLogo(String originalName, String contentType, byte[] data, String actor)
            throws IOException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Файл логотипа пустой");
        }
        if (data.length > MAX_LOGO_BYTES) {
            throw new IllegalArgumentException(
                    "Логотип больше 512 КБ — уменьшите изображение");
        }

        String ext = detectExtension(originalName, contentType, data);
        validateImage(data, ext);

        String version = Long.toString(System.currentTimeMillis(), 36);
        // Удаляем прежние файлы, чтобы не копить мусор
        deleteLogoFiles();
        Path target = brandingDir.resolve("logo-" + version + "." + ext);
        Files.write(target, data);

        settings.set(KEY_LOGO_VERSION, version, actor);
        log.info("Логотип заказчика обновлён: {} ({} байт, версия {})",
                originalName, data.length, version);
        return version;
    }

    public void deleteLogo(String actor) throws IOException {
        deleteLogoFiles();
        settings.set(KEY_LOGO_VERSION, "", actor);
        log.info("Логотип заказчика удалён — используется стандартный");
    }

    /** Сброс брендинга к значениям по умолчанию. */
    public void reset(String actor) throws IOException {
        deleteLogoFiles();
        settings.set(KEY_LOGO_VERSION, "", actor);
        settings.set(KEY_NAME, "", actor);
        settings.set(KEY_TAGLINE, "", actor);
    }

    // ═══════════════════════════ Внутреннее ═══════════════════════════

    private void deleteLogoFiles() throws IOException {
        if (!Files.isDirectory(brandingDir)) return;
        try (var stream = Files.list(brandingDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String contentTypeOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private static String detectExtension(String originalName, String contentType, byte[] data) {
        String lower = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".svg") || "image/svg+xml".equals(contentType)) return "svg";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || "image/jpeg".equals(contentType)) return "jpg";
        if (lower.endsWith(".webp") || "image/webp".equals(contentType)) return "webp";
        if (lower.endsWith(".png")) return "png";
        // по сигнатуре файла
        if (data.length > 8 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return "jpg";
        if (data.length > 4 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F'
                && data[3] == 'F') return "webp";
        return "png";
    }

    /** Проверяет, что файл действительно изображение (и SVG без скриптов). */
    static void validateImage(byte[] data, String ext) {
        if ("svg".equals(ext)) {
            String svg = new String(data, java.nio.charset.StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            if (!svg.contains("<svg")) {
                throw new IllegalArgumentException("Файл не похож на SVG");
            }
            if (svg.contains("<script") || svg.contains("onload=")
                    || svg.contains("javascript:") || svg.contains("<foreignobject")) {
                throw new IllegalArgumentException(
                        "SVG содержит активное содержимое — загрузите обычную картинку");
            }
            return;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) {
                throw new IllegalArgumentException(
                        "Не удалось распознать изображение (поддерживаются PNG, JPEG, WebP, SVG)");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Не удалось прочитать изображение: "
                    + e.getMessage(), e);
        }
    }

    /** Копирует файл в каталог брендинга (для тестов и миграции). */
    public Path copyFrom(Path source) throws IOException {
        Path target = brandingDir.resolve("logo-" + UUID.randomUUID() + ".png");
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
