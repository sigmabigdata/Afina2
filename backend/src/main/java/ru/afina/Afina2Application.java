package ru.afina;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Точка входа Afina2.
 *
 * <p>{@code @EnableScheduling} включает планировщики: обновление статусов
 * заявок Госключа, проверку CRL, мониторинг, очистку аудита и бэкапы.</p>
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan("ru.afina")
public class Afina2Application {

    public static void main(String[] args) {
        SpringApplication.run(Afina2Application.class, args);
    }
}
