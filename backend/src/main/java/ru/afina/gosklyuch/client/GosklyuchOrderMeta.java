package ru.afina.gosklyuch.client;

/**
 * Параметр {@code meta} запросов создания/загрузки заявки в Госключ:
 * реквизиты формы услуги ЕПГУ. Берутся из конфигурации инсталляции.
 */
public record GosklyuchOrderMeta(String region, String serviceCode, String targetCode) {
}
