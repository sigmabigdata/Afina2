package ru.afina.gosklyuch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки интеграции с API ЕПГУ для отправки документов на подписание
 * в МП «Госключ» (услуга {@code 10000000374}).
 *
 * <p>Все значения задаются конфигурацией/переменными окружения конкретной
 * инсталляции — в коде нет ни адресов, ни мнемоник, ни реквизитов организации.
 * По умолчанию интеграция выключена ({@code app.gosklyuch.enabled=false}),
 * поэтому до явного включения никакие внешние вызовы не выполняются.</p>
 *
 * <p>ЗНАЧЕНИЯ ПО УМОЛЧАНИЮ НЕ ЯВЛЯЮТСЯ ДОГОВОРОМ С ЕПГУ: мнемоника,
 * код услуги, регион, имя организации и адрес API выдаются/согласуются
 * при подключении к API ЕПГУ. Адрес API и заголовок авторизации
 * описаны в «Основной спецификации API ЕПГУ» и должны быть проверены
 * при подключении на тестовом контуре.</p>
 */
@ConfigurationProperties(prefix = "app.gosklyuch")
public class GosklyuchProperties {

    /** Признак включения интеграции (по умолчанию выключена). */
    private boolean enabled = false;

    /** Провайдер: {@code disabled} | {@code fake} | {@code epgu}. */
    private String provider = "disabled";

    /**
     * Реализация подписанта пакета УКЭП: {@code unavailable} (по умолчанию,
     * отправка запрещена) — до настройки модуля подписи конкретной инсталляции.
     */
    private String signer = "unavailable";

    /** Базовый адрес API ЕПГУ, например {@code https://svcdev-beta.test.gosuslugi.ru}. */
    private String baseUrl = "";

    /** Заголовок с маркером доступа (имя задаётся конфигурацией). */
    private String authHeader = "Authorization";

    /** Префикс значения заголовка авторизации (обычно {@code Bearer }). */
    private String authPrefix = "Bearer ";

    /** Маркер доступа; если пусто — читается из {@link #tokenFile}. */
    private String token = "";

    /** Путь к файлу с маркером доступа (позволяет ротацию без пересборки). */
    private String tokenFile = "";

    /** Регион в meta-запросе (код региона ЕПГУ). */
    private String region = "";

    /** Код услуги ЕПГУ для отправки документов в Госключ. */
    private String serviceCode = "";

    /** Целевой код услуги ЕПГУ. */
    private String targetCode = "";

    /** Мнемоника информационной системы (атрибут {@code mnemonics}). */
    private String mnemonic = "";

    /** Наименование организации-вендора для отображения подписанту. */
    private String orgName = "";

    /** Название сервиса для отображения подписанту. */
    private String serviceName = "Отправка документов на подпись в «Госключ»";

    /** Тип подписи: {@code UNEP} (УНЭП) | {@code UKEP} (УКЭП). */
    private String signatureType = "UNEP";

    /** Адрес возврата в ЛК ЕПГУ после подписания. */
    private String backlink = "https://lk.gosuslugi.ru/notifications";

    /** Срок подписания (не более 24 часов по спецификации). */
    private Duration signExpiration = Duration.ofHours(24);

    /** Максимум документов в одной заявке (не более 15 по спецификации). */
    private int maxDocuments = 15;

    /** Максимальный размер архива заявки в байтах (не более 100 МБ). */
    private long maxPackageBytes = 100L * 1024 * 1024;

    /** Применять ли полученную подпись к документу автоматически. */
    private boolean applySignature = false;

    /** Уведомлять клиента письмом о смене статуса заявки (по умолчанию выключено). */
    private boolean notifyEnabled = false;

    /** PKCS#12-файл с ключом и сертификатом УКЭП организации (для signer=pkcs12). */
    private String p12Path = "";

    /** Пароль PKCS#12 (лучше через секрет/файл, не в git). */
    private String p12Password = "";

    /** Alias ключа в PKCS#12; пусто — первый ключевой alias. */
    private String p12Alias = "";

    /** Явный JCA-провайдер для подписи (пусто — JCSP для ГОСТ, JDK для RSA/EC). */
    private String p12Provider = "";

    /** Период опроса статусов заявок, мс. */
    private long pollIntervalMs = 300_000L;

    /** Задержка первого опроса после старта приложения, мс. */
    private long pollInitialDelayMs = 120_000L;

    /** Таймаут соединения с API. */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** Таймаут чтения ответа API. */
    private Duration readTimeout = Duration.ofSeconds(60);

    /**
     * Размер части при загрузке архива через {@code /push/chunked}, байт.
     * {@code 0} — всегда отправлять архив одним запросом {@code /push}.
     */
    private long chunkSize = 0;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getSigner() { return signer; }
    public void setSigner(String signer) { this.signer = signer; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getAuthHeader() { return authHeader; }
    public void setAuthHeader(String authHeader) { this.authHeader = authHeader; }

    public String getAuthPrefix() { return authPrefix; }
    public void setAuthPrefix(String authPrefix) { this.authPrefix = authPrefix; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getTokenFile() { return tokenFile; }
    public void setTokenFile(String tokenFile) { this.tokenFile = tokenFile; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getServiceCode() { return serviceCode; }
    public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }

    public String getTargetCode() { return targetCode; }
    public void setTargetCode(String targetCode) { this.targetCode = targetCode; }

    public String getMnemonic() { return mnemonic; }
    public void setMnemonic(String mnemonic) { this.mnemonic = mnemonic; }

    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getSignatureType() { return signatureType; }
    public void setSignatureType(String signatureType) { this.signatureType = signatureType; }

    public String getBacklink() { return backlink; }
    public void setBacklink(String backlink) { this.backlink = backlink; }

    public Duration getSignExpiration() { return signExpiration; }
    public void setSignExpiration(Duration signExpiration) { this.signExpiration = signExpiration; }

    public int getMaxDocuments() { return maxDocuments; }
    public void setMaxDocuments(int maxDocuments) { this.maxDocuments = maxDocuments; }

    public long getMaxPackageBytes() { return maxPackageBytes; }
    public void setMaxPackageBytes(long maxPackageBytes) { this.maxPackageBytes = maxPackageBytes; }

    public boolean isApplySignature() { return applySignature; }
    public void setApplySignature(boolean applySignature) { this.applySignature = applySignature; }

    public boolean isNotifyEnabled() { return notifyEnabled; }
    public void setNotifyEnabled(boolean notifyEnabled) { this.notifyEnabled = notifyEnabled; }

    public String getP12Path() { return p12Path; }
    public void setP12Path(String p12Path) { this.p12Path = p12Path; }

    public String getP12Password() { return p12Password; }
    public void setP12Password(String p12Password) { this.p12Password = p12Password; }

    public String getP12Alias() { return p12Alias; }
    public void setP12Alias(String p12Alias) { this.p12Alias = p12Alias; }

    public String getP12Provider() { return p12Provider; }
    public void setP12Provider(String p12Provider) { this.p12Provider = p12Provider; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public long getPollInitialDelayMs() { return pollInitialDelayMs; }
    public void setPollInitialDelayMs(long pollInitialDelayMs) {
        this.pollInitialDelayMs = pollInitialDelayMs;
    }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

    public long getChunkSize() { return chunkSize; }
    public void setChunkSize(long chunkSize) { this.chunkSize = chunkSize; }
}
