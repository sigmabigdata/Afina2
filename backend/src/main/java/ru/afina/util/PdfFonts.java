package ru.afina.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Поиск TTF-шрифта с кириллицей для генерации PDF.
 *
 * <p>Порядок: сначала шрифт из classpath ({@code /fonts/DejaVuSans.ttf} —
 * поставляется с приложением, лицензия Bitstream Vera/DejaVu), затем
 * системные шрифты Linux (Astra/Debian) и macOS для локальной разработки.</p>
 *
 * <p>Встроенный шрифт гарантирует одинаковый вид документов на любом сервере
 * и отсутствие зависимости от установленных в ОС пакетов.</p>
 */
public final class PdfFonts {

    private static final Logger log = LoggerFactory.getLogger(PdfFonts.class);

    /** Ресурс в classpath. */
    public static final String REGULAR_RESOURCE = "/fonts/DejaVuSans.ttf";
    public static final String BOLD_RESOURCE = "/fonts/DejaVuSans-Bold.ttf";

    private static final List<String> SYSTEM_REGULAR = List.of(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf",
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/Library/Fonts/Arial.ttf");

    private static final List<String> SYSTEM_BOLD = List.of(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf",
            "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
            "/Library/Fonts/Arial Bold.ttf");

    private PdfFonts() {
    }

    /** Поток обычного начертания (classpath → файловая система) или {@code null}. */
    public static InputStream openRegular() {
        InputStream fromClasspath = PdfFonts.class.getResourceAsStream(REGULAR_RESOURCE);
        if (fromClasspath != null) return fromClasspath;
        return openFirstExisting(SYSTEM_REGULAR);
    }

    /** Поток полужирного начертания или обычного, если жирного нет. */
    public static InputStream openBold() {
        InputStream fromClasspath = PdfFonts.class.getResourceAsStream(BOLD_RESOURCE);
        if (fromClasspath != null) return fromClasspath;
        InputStream system = openFirstExisting(SYSTEM_BOLD);
        return system != null ? system : openRegular();
    }

    /** Есть ли встроенный шрифт (используется в диагностике). */
    public static boolean hasBundledFont() {
        return PdfFonts.class.getResource(REGULAR_RESOURCE) != null;
    }

    /** Путь к системному шрифту (для API, которому нужен {@link Path}). */
    public static Path systemFontPath() {
        for (String p : SYSTEM_REGULAR) {
            Path path = Paths.get(p);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }

    private static InputStream openFirstExisting(List<String> candidates) {
        for (String p : candidates) {
            Path path = Paths.get(p);
            if (!Files.isRegularFile(path)) continue;
            try {
                return Files.newInputStream(path);
            } catch (IOException e) {
                log.debug("Не удалось открыть шрифт {}: {}", p, e.getMessage());
            }
        }
        log.warn("TTF с кириллицей не найден — PDF может отобразиться без кириллицы");
        return null;
    }
}
