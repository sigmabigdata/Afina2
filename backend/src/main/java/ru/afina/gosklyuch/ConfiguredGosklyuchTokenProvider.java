package ru.afina.gosklyuch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Маркер доступа из конфигурации или из файла-секрета.
 *
 * <p>Файл читается при каждом запросе, чтобы маркер можно было
 * заменить без перезапуска приложения.</p>
 */
public class ConfiguredGosklyuchTokenProvider implements GosklyuchTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredGosklyuchTokenProvider.class);

    private final GosklyuchProperties properties;

    public ConfiguredGosklyuchTokenProvider(GosklyuchProperties properties) {
        this.properties = properties;
    }

    @Override
    public String token() {
        String file = properties.getTokenFile();
        if (file != null && !file.isBlank()) {
            try {
                String value = Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
                if (!value.isEmpty()) return value;
            } catch (IOException e) {
                log.warn("Не удалось прочитать маркер доступа Госключа из {}: {}",
                        file, e.getMessage());
            }
        }
        String inline = properties.getToken();
        if (inline == null || inline.isBlank()) {
            throw new GosklyuchUnavailableException(
                    "Маркер доступа API ЕПГУ не настроен (app.gosklyuch.token / token-file)");
        }
        return inline.trim();
    }
}
