package ru.afina.gosklyuch;

/**
 * СНИЛС: нормализация, проверка контрольного числа и формат для заявки.
 *
 * <p>В заявке Госключа СНИЛС передаётся в виде {@code XXX-XXX-XXX YY}
 * (12 цифр с разделителями, не длиннее 14 символов).</p>
 */
public final class GosklyuchSnils {

    private GosklyuchSnils() {
    }

    /** Только цифры; {@code null}, если значение пустое. */
    public static String digits(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.replaceAll("\\D", "");
    }

    /** Контрольное число СНИЛС (правило ПФР). */
    public static boolean isValid(String raw) {
        String d = digits(raw);
        if (d == null || d.length() != 11) return false;
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (d.charAt(i) - '0') * (9 - i);
        }
        if (sum > 101) sum %= 101;
        if (sum == 100 || sum == 101) sum = 0;
        int expected = Integer.parseInt(d.substring(9, 11));
        return sum == expected;
    }

    /**
     * Формат для {@code req.xml}: {@code 000-729-729 38}.
     *
     * @throws IllegalArgumentException если значение не является 11-значным СНИЛС
     */
    public static String formatted(String raw) {
        String d = digits(raw);
        if (d == null || d.length() != 11) {
            throw new IllegalArgumentException("СНИЛС должен содержать 11 цифр");
        }
        return d.substring(0, 3) + "-" + d.substring(3, 6) + "-" + d.substring(6, 9)
                + " " + d.substring(9, 11);
    }
}
