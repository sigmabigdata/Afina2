package ru.afina.gosklyuch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.afina.gosklyuch.client.DisabledGosklyuchClient;
import ru.afina.gosklyuch.client.EpguGosklyuchClient;
import ru.afina.gosklyuch.client.FakeGosklyuchClient;
import ru.afina.gosklyuch.client.GosklyuchClient;

/**
 * Сборка компонентов интеграции с Госключом по конфигурации инсталляции.
 *
 * <p>По умолчанию: провайдер {@code disabled}, подписант пакета не настроен,
 * интеграция выключена — приложение не выполняет внешних вызовов.</p>
 */
@Configuration
public class GosklyuchConfig {

    @Bean
    public GosklyuchRequestXmlBuilder gosklyuchRequestXmlBuilder() {
        return new GosklyuchRequestXmlBuilder();
    }

    @Bean
    public GosklyuchPackageBuilder gosklyuchPackageBuilder(GosklyuchSigner signer,
                                                           GosklyuchProperties properties) {
        return GosklyuchPackageBuilder.from(signer, properties);
    }

    /** Подписант пакета по умолчанию — явная заглушка с понятной ошибкой. */
    @Bean
    @ConditionalOnProperty(prefix = "app.gosklyuch", name = "signer",
            havingValue = "unavailable", matchIfMissing = true)
    public GosklyuchSigner gosklyuchSignerUnavailable() {
        return new GosklyuchSignerUnavailable();
    }

    /** Реальный подписант: ключ УКЭП организации из PKCS#12 (ГОСТ — через JCSP). */
    @Bean
    @ConditionalOnProperty(prefix = "app.gosklyuch", name = "signer", havingValue = "pkcs12")
    public GosklyuchSigner pkcs12GosklyuchSigner(GosklyuchProperties properties) {
        return new Pkcs12GosklyuchSigner(properties);
    }

    /**
     * Демо-подписант: только вместе с провайдером {@code fake} (демо-стенд).
     * С {@code provider=epgu} такой бин не создаётся — приложение не стартует,
     * чтобы в ЕПГУ не мог уйти архив с фиктивной подписью.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.gosklyuch", name = {"provider", "signer"},
            havingValue = "fake")
    public GosklyuchSigner fakeGosklyuchSigner() {
        return new FakeGosklyuchSigner();
    }

    /** Провайдер по умолчанию: интеграция не подключена. */
    @Bean
    @ConditionalOnProperty(prefix = "app.gosklyuch", name = "provider",
            havingValue = "disabled", matchIfMissing = true)
    public GosklyuchClient disabledGosklyuchClient() {
        return new DisabledGosklyuchClient();
    }

    /** Тестовый/демонстрационный провайдер без внешних вызовов. */
    @Bean
    @ConditionalOnProperty(prefix = "app.gosklyuch", name = "provider", havingValue = "fake")
    public GosklyuchClient fakeGosklyuchClient() {
        return new FakeGosklyuchClient();
    }

    /**
     * Источник маркера доступа нужен и диагностике (проверка «задан/не задан»),
     * поэтому создаётся всегда; сетевых вызовов не выполняет.
     */
    @Bean
    public GosklyuchTokenProvider configuredGosklyuchTokenProvider(GosklyuchProperties properties) {
        return new ConfiguredGosklyuchTokenProvider(properties);
    }

    /** Реальный клиент API ЕПГУ. */
    @Bean
    @ConditionalOnProperty(prefix = "app.gosklyuch", name = "provider", havingValue = "epgu")
    public GosklyuchClient epguGosklyuchClient(GosklyuchProperties properties,
                                                GosklyuchTokenProvider tokenProvider,
                                                ObjectMapper objectMapper) {
        return new EpguGosklyuchClient(properties, tokenProvider, objectMapper);
    }
}
