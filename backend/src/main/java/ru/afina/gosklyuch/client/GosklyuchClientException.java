package ru.afina.gosklyuch.client;

/**
 * Ошибка взаимодействия с API ЕПГУ / Госключа.
 */
public class GosklyuchClientException extends RuntimeException {

    private final int httpStatus;

    public GosklyuchClientException(String message) {
        this(message, 0, null);
    }

    public GosklyuchClientException(String message, int httpStatus) {
        this(message, httpStatus, null);
    }

    public GosklyuchClientException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
