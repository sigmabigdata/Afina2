package ru.afina.security;

import java.util.regex.Pattern;

/**
 * Маскирование чувствительных данных для логов.
 *
 * <p>В лог не должны попадать email целиком, ФИО и magic-link токены:
 * логи уезжают в docker/journald и доступны вне контура приложения.
 */
public final class PiiMask {

    private static final Pattern TOKEN_IN_URL =
            Pattern.compile("(token=)([A-Za-z0-9._~-]{6,})");

    private PiiMask() {
    }

    /** {@code ivan.petrov@bank.ru} → {@code i***@bank.ru}. */
    public static String email(String email) {
        if (email == null || email.isBlank()) return "?";
        String v = email.trim();
        int at = v.indexOf('@');
        if (at <= 0) return "***";
        String local = v.substring(0, at);
        String domain = v.substring(at);
        String head = local.length() <= 2 ? local.charAt(0) + "***"
                : local.substring(0, 2) + "***";
        return head + domain;
    }

    /** {@code Дырул Анатолий Юрьевич} → {@code Д*** А*** Ю***}. */
    public static String fio(String fullName) {
        if (fullName == null || fullName.isBlank()) return "?";
        StringBuilder sb = new StringBuilder();
        for (String part : fullName.trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.charAt(0)).append("***");
        }
        return sb.toString();
    }

    /** Вырезает значения {@code token=...} из текста письма/URL перед логированием. */
    public static String tokens(String text) {
        if (text == null) return null;
        return TOKEN_IN_URL.matcher(text).replaceAll("$1***");
    }
}
