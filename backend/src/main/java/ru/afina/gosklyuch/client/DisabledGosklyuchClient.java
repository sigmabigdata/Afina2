package ru.afina.gosklyuch.client;

import ru.afina.gosklyuch.GosklyuchUnavailableException;

/**
 * Клиент по умолчанию: интеграция с ЕПГУ не подключена.
 *
 * <p>Любой вызов завершается {@link GosklyuchUnavailableException} —
 * при выключенной интеграции внешние запросы не выполняются вовсе.</p>
 */
public class DisabledGosklyuchClient implements GosklyuchClient {

    public static final String MESSAGE =
            "Интеграция с Госключом не подключена "
                    + "(app.gosklyuch.provider=disabled)";

    @Override
    public long createOrder(GosklyuchOrderMeta meta) {
        throw new GosklyuchUnavailableException(MESSAGE);
    }

    @Override
    public void pushPackage(long orderId, byte[] zip, GosklyuchOrderMeta meta) {
        throw new GosklyuchUnavailableException(MESSAGE);
    }

    @Override
    public GosklyuchOrderInfo getOrder(long orderId) {
        throw new GosklyuchUnavailableException(MESSAGE);
    }

    @Override
    public byte[] downloadFile(long orderId, GosklyuchFile file) {
        throw new GosklyuchUnavailableException(MESSAGE);
    }
}
