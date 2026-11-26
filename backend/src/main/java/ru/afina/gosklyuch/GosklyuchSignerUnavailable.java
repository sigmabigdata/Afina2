package ru.afina.gosklyuch;

/**
 * Заглушка подписанта пакета: явно сообщает, что УКЭП организации
 * для формирования архива заявки в данной инсталляции не настроена.
 *
 * <p>Бин создаётся в {@link GosklyuchConfig} только при
 * {@code app.gosklyuch.signer=unavailable} (значение по умолчанию),
 * чтобы включение интеграции требовало явной настройки подписи.</p>
 */
public class GosklyuchSignerUnavailable implements GosklyuchSigner {

    public static final String MESSAGE =
            "Подписание пакета УКЭП организации не настроено "
                    + "(требуется сертификат и модуль подписи в инсталляции)";

    @Override
    public byte[] signDetached(String fileName, byte[] content) {
        throw new GosklyuchUnavailableException(MESSAGE);
    }
}
