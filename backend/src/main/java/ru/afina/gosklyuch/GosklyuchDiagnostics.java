package ru.afina.gosklyuch;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Самопроверка конфигурации Госключа без обращения к ЕПГУ.
 *
 * <p>Проверяет только локальные настройки: включённость интеграции, выбранный
 * провайдер, заполненность адреса API, маркера доступа, реквизитов услуги
 * и готовность подписанта пакета. Сетевых запросов не выполняет, секреты
 * в отчёт не попадают (маркер показывается только как «задан/не задан»).</p>
 */
@Component
public class GosklyuchDiagnostics {

    /** Статус отдельной проверки. */
    public enum Status { OK, WARN, ERROR }

    /** Результат одной проверки. */
    public record Check(String name, Status status, String message) {
    }

    /** Полный отчёт для страницы диагностики. */
    public record Report(boolean enabled, String provider, String signer, boolean readyToSend,
                         List<Check> checks, Map<String, String> settings) {
    }

    private final GosklyuchProperties properties;
    private final GosklyuchService service;
    private final GosklyuchSigner signer;
    private final GosklyuchTokenProvider tokenProvider;

    public GosklyuchDiagnostics(GosklyuchProperties properties,
                                GosklyuchService service,
                                GosklyuchSigner signer,
                                GosklyuchTokenProvider tokenProvider) {
        this.properties = properties;
        this.service = service;
        this.signer = signer;
        this.tokenProvider = tokenProvider;
    }

    /** Отчёт по конфигурации (без сети). */
    public Report check() {
        List<Check> checks = new ArrayList<>();
        checks.add(checkEnabled());
        checks.add(checkProvider());
        if (isEpgu()) {
            checks.add(required("Адрес API ЕПГУ", properties.getBaseUrl(), "app.gosklyuch.base-url"));
            checks.add(checkToken());
        }
        checks.add(requiredIfEpgu("Регион (meta)", properties.getRegion(),
                "app.gosklyuch.region"));
        checks.add(requiredIfEpgu("Код услуги (meta)", properties.getServiceCode(),
                "app.gosklyuch.service-code"));
        checks.add(requiredIfEpgu("Целевой код услуги (meta)", properties.getTargetCode(),
                "app.gosklyuch.target-code"));
        checks.add(requiredIfEpgu("Мнемоника ИС (meta)", properties.getMnemonic(),
                "app.gosklyuch.mnemonic"));
        checks.add(requiredIfEpgu("Наименование организации", properties.getOrgName(),
                "app.gosklyuch.org-name"));
        checks.add(checkSigner());
        return new Report(service.isEnabled(), properties.getProvider(),
                properties.getSigner(), service.isReadyToSend(), checks, settings());
    }

    /** Проверка подписанта пакета: пробная подпись небольшого блока данных. */
    public Check checkSigner() {
        try {
            byte[] sig = signer.signDetached("diagnostics.txt",
                    "AfinaZ Gosklyuch diagnostics".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (sig == null || sig.length == 0) {
                return new Check("Подписант пакета (УКЭП)", Status.ERROR,
                        "Подписант вернул пустую подпись");
            }
            if (signer instanceof FakeGosklyuchSigner) {
                return new Check("Подписант пакета (УКЭП)", Status.WARN,
                        "Демо-подписант: подпись фиктивная, отправлять в ЕПГУ нельзя");
            }
            return new Check("Подписант пакета (УКЭП)", Status.OK,
                    "Подписант работает, подпись сформирована");
        } catch (GosklyuchUnavailableException e) {
            return new Check("Подписант пакета (УКЭП)", Status.WARN, e.getMessage());
        } catch (RuntimeException e) {
            return new Check("Подписант пакета (УКЭП)", Status.ERROR,
                    "Ошибка подписанта: " + e.getMessage());
        }
    }

    private Check checkEnabled() {
        return properties.isEnabled()
                ? new Check("Интеграция включена", Status.OK, "Кнопки и отправка доступны")
                : new Check("Интеграция включена", Status.WARN,
                        "Выключена: внешних вызовов нет, кнопки скрыты");
    }

    private Check checkProvider() {
        String provider = properties.getProvider();
        if (provider == null || provider.isBlank() || "disabled".equalsIgnoreCase(provider)) {
            return new Check("Провайдер API", Status.WARN,
                    "disabled — интеграция не подключена к ЕПГУ");
        }
        if ("fake".equalsIgnoreCase(provider)) {
            return new Check("Провайдер API", Status.WARN,
                    "fake — демо-клиент без внешних вызовов");
        }
        if ("epgu".equalsIgnoreCase(provider)) {
            return new Check("Провайдер API", Status.OK, "epgu — реальный API ЕПГУ");
        }
        return new Check("Провайдер API", Status.ERROR,
                "Неизвестный провайдер: " + provider);
    }

    private Check checkToken() {
        try {
            String token = tokenProvider.token();
            if (token == null || token.isBlank()) {
                return new Check("Маркер доступа", Status.ERROR, "Не задан");
            }
            return new Check("Маркер доступа", Status.OK,
                    "задан (" + token.length() + " символов)");
        } catch (GosklyuchUnavailableException e) {
            return new Check("Маркер доступа", Status.ERROR, e.getMessage());
        }
    }

    private Check required(String name, String value, String property) {
        if (value == null || value.isBlank()) {
            return new Check(name, Status.ERROR,
                    "Не задано: " + property);
        }
        return new Check(name, Status.OK, "задано");
    }

    private Check requiredIfEpgu(String name, String value, String property) {
        if (!isEpgu()) {
            return new Check(name, Status.OK, "не требуется для текущего провайдера");
        }
        return required(name, value, property);
    }

    private boolean isEpgu() {
        return "epgu".equalsIgnoreCase(properties.getProvider());
    }

    private Map<String, String> settings() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("provider", nullToDash(properties.getProvider()));
        map.put("signer", nullToDash(properties.getSigner()));
        map.put("baseUrl", nullToDash(properties.getBaseUrl()));
        map.put("authHeader", nullToDash(properties.getAuthHeader()));
        map.put("token", tokenState());
        map.put("region", nullToDash(properties.getRegion()));
        map.put("serviceCode", nullToDash(properties.getServiceCode()));
        map.put("targetCode", nullToDash(properties.getTargetCode()));
        map.put("mnemonic", nullToDash(properties.getMnemonic()));
        map.put("orgName", nullToDash(properties.getOrgName()));
        map.put("signatureType", nullToDash(properties.getSignatureType()));
        map.put("backlink", nullToDash(properties.getBacklink()));
        map.put("signExpiration", String.valueOf(properties.getSignExpiration()));
        map.put("maxDocuments", String.valueOf(properties.getMaxDocuments()));
        map.put("maxPackageMb", String.valueOf(properties.getMaxPackageBytes() / (1024 * 1024)));
        map.put("applySignature", String.valueOf(properties.isApplySignature()));
        map.put("pollIntervalMs", String.valueOf(properties.getPollIntervalMs()));
        map.put("notifyEnabled", String.valueOf(properties.isNotifyEnabled()));
        map.put("p12Path", nullToDash(properties.getP12Path()));
        map.put("p12Password", properties.getP12Password() != null
                && !properties.getP12Password().isBlank() ? "задан" : "не задан");
        map.put("p12Alias", nullToDash(properties.getP12Alias()));
        return map;
    }

    private String tokenState() {
        if (properties.getTokenFile() != null && !properties.getTokenFile().isBlank()) {
            return "из файла: " + properties.getTokenFile();
        }
        return properties.getToken() != null && !properties.getToken().isBlank()
                ? "задан" : "не задан";
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
