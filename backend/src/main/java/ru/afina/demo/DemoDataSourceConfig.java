package ru.afina.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * Демо использует маршрутизирующий DataSource вместо авто-настроенного Hikari:
 * соединение выбирается по текущему демо-тенанту (сессии).
 */
@Configuration
@Profile("demo")
public class DemoDataSourceConfig {

    @Bean
    @Primary
    public DataSource demoDataSource(DemoRoutingDataSource routing) {
        return routing;
    }
}
