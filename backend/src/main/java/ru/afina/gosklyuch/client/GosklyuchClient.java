package ru.afina.gosklyuch.client;

/**
 * Транспортный клиент API ЕПГУ для услуги «Отправка документов на подпись
 * в Госключ» (методы разделов 2.2.1–2.2.4 спецификации v1.8).
 *
 * <p>Базовый адрес, заголовок авторизации и маркер доступа задаются
 * конфигурацией инсталляции, а не кодом.</p>
 */
public interface GosklyuchClient {

    /**
     * Создать заявку: {@code POST /api/gusmev/order}.
     *
     * @return номер заявки ({@code orderId})
     */
    long createOrder(GosklyuchOrderMeta meta);

    /**
     * Загрузить архив заявки: {@code POST /api/gusmev/push}
     * (при превышении размера одного запроса — {@code /push/chunked}).
     */
    void pushPackage(long orderId, byte[] zip, GosklyuchOrderMeta meta);

    /**
     * Получить детали заявки:
     * {@code POST /api/gusmev/order/{orderId}?embed=STATUS_HISTORY}.
     */
    GosklyuchOrderInfo getOrder(long orderId);

    /**
     * Скачать файл результата:
     * {@code GET /api/storage/v2/files/{orderId}/{version}/download?mnemonic=…}.
     */
    byte[] downloadFile(long orderId, GosklyuchFile file);
}
