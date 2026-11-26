package ru.afina.gosklyuch;

/**
 * Подписант пакета заявки не настроен (нет сертификата УКЭП организации,
 * ключевого носителя или модуля подписи в данной инсталляции).
 *
 * <p>Используется как реализация {@link GosklyuchSigner} по умолчанию,
 * чтобы отправка в Госключ не могла состояться без явной настройки.</p>
 */
public class GosklyuchUnavailableException extends RuntimeException {

    public GosklyuchUnavailableException(String message) {
        super(message);
    }

    public GosklyuchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
