package ru.afina.demo;

import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Регистрация слушателя сессий демо (нужен для удаления персональной БД). */
@Configuration
@Profile("demo")
public class DemoWebConfig {

    @Bean
    public ServletListenerRegistrationBean<DemoTenantService> demoTenantListener(DemoTenantService svc) {
        return new ServletListenerRegistrationBean<>(svc);
    }
}
