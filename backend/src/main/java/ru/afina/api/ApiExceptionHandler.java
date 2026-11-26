package ru.afina.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Единый формат ошибок REST API Afina2.
 *
 * <pre>{"error":"bad_request","message":"…","path":"/api/v1/…","time":"…"}</pre>
 *
 * <p>Наружу не уходят stacktrace и текст исключений СУБД/криптографии —
 * только прикладные сообщения {@link IllegalArgumentException}/
 * {@link IllegalStateException}, которые формируются в сервисах осознанно.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e,
                                                            HttpServletRequest req) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String reason = e.getReason();
        return body(status, slug(status), reason == null ? status.getReasonPhrase() : reason, req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e,
                                                                  HttpServletRequest req) {
        return body(HttpStatus.FORBIDDEN, "forbidden", "Недостаточно прав", req);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthenticationException e,
                                                          HttpServletRequest req) {
        return body(HttpStatus.UNAUTHORIZED, "unauthorized", "Требуется вход", req);
    }

    @ExceptionHandler(ru.afina.error.NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            ru.afina.error.NotFoundException e, HttpServletRequest req) {
        return body(HttpStatus.NOT_FOUND, "not_found",
                safe(e.getMessage(), "Объект не найден"), req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e,
                                                                HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, "bad_request",
                safe(e.getMessage(), "Некорректный запрос"), req);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e,
                                                              HttpServletRequest req) {
        return body(HttpStatus.CONFLICT, "conflict",
                safe(e.getMessage(), "Операция невозможна в текущем состоянии"), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException e, HttpServletRequest req) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "validation_error",
                message.isBlank() ? "Проверьте заполнение полей" : message, req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException e,
                                                              HttpServletRequest req) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                "Файл превышает допустимый размер (50 МБ)", req);
    }

    /** Неизвестный маршрут — 404, а не 500 (иначе выключенные эндпоинты «падают»). */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception e,
                                                              HttpServletRequest req) {
        return body(HttpStatus.NOT_FOUND, "not_found", "Маршрут не найден", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e, HttpServletRequest req) {
        log.error("Необработанная ошибка API {} {}", req.getMethod(), req.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "Внутренняя ошибка сервера", req);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error,
                                                            String message,
                                                            HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error);
        m.put("message", message);
        m.put("path", req.getRequestURI());
        m.put("time", Instant.now().toString());
        return ResponseEntity.status(status).body(m);
    }

    private static String slug(HttpStatus status) {
        return status.name().toLowerCase();
    }

    private static String safe(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
