package ru.afina.error;

/**
 * Запрошенный объект не найден. Обработчик API превращает это
 * исключение в HTTP 404 (в отличие от 400 «некорректный запрос»).
 */
public class NotFoundException extends IllegalArgumentException {

    public NotFoundException(String message) {
        super(message);
    }
}
