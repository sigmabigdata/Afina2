package ru.afina.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Нормализация персональных данных клиента (ФИО, телефон).
 *
 * <p>Вынесено отдельно от сервисов, чтобы одинаковые правила действовали
 * при создании клиента администратором, при самостоятельном онбординге
 * и при распознавании скана паспорта.
 */
public final class PersonData {

    private static final Pattern CN_IN_SUBJECT =
            Pattern.compile("CN=(?:\"([^\"]*)\"|([^,]*))", Pattern.CASE_INSENSITIVE);

    private PersonData() {
    }

    /**
     * ФИО: 2–4 слова, только буквы/дефис/пробелы; приводим к «Фамилия Имя Отчество».
     *
     * @return нормализованное ФИО или {@code null}, если значение некорректно
     */
    public static String normalizeFullName(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return null;
        if (!cleaned.matches("[А-Яа-яЁёA-Za-z\\- ]{3,120}")) return null;
        String[] parts = cleaned.split(" ");
        if (parts.length < 2) return null;
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            String lower = part.toLowerCase(Locale.ROOT);
            sb.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
        }
        return sb.toString();
    }

    /**
     * Телефон: 10 или 11 цифр, приводим к {@code +7XXXXXXXXXX}.
     *
     * @return нормализованный телефон или {@code null}, если значение некорректно
     */
    public static String normalizePhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() == 10) {
            digits = "7" + digits;
        } else if (digits.length() == 11 && digits.charAt(0) == '8') {
            digits = "7" + digits.substring(1);
        }
        if (digits.length() != 11 || digits.charAt(0) != '7') return null;
        return "+" + digits;
    }

    /**
     * CN из строки субъекта сертификата: {@code CN=Иванов Иван Иванович, O=...}.
     *
     * @return значение CN или {@code null}, если его нет
     */
    public static String extractCn(String subject) {
        if (subject == null || subject.isBlank()) return null;
        Matcher m = CN_IN_SUBJECT.matcher(subject);
        if (!m.find()) return null;
        String value = m.group(1) != null ? m.group(1) : m.group(2);
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Совпадают ли фамилия и имя в сертификате и в карточке клиента.
     *
     * <p>Сравниваются только фамилия и имя (отчество и регистр не важны);
     * если в CN только инициал имени — достаточно совпадения первой буквы.
     * Если сравнивать нечего — считаем, что совпадают (не мешаем подписывать).
     */
    public static boolean namesMatch(String cardFullName, String certCn) {
        List<String> card = nameTokens(cardFullName);
        List<String> cert = nameTokens(certCn);
        if (card.isEmpty() || cert.isEmpty()) return true;
        if (!card.get(0).equals(cert.get(0))) return false;
        if (cert.size() < 2 || card.size() < 2) return true;
        String cardName = card.get(1);
        String certName = cert.get(1);
        if (certName.length() == 1) return cardName.startsWith(certName);
        return cardName.equals(certName);
    }

    /** Текст предупреждения о несовпадении ФИО или {@code null}, если всё совпадает. */
    public static String nameMismatch(String cardFullName, String certSubject) {
        String cn = extractCn(certSubject);
        if (cn == null || namesMatch(cardFullName, cn)) return null;
        String card = cardFullName == null || cardFullName.isBlank()
                ? "не заполнено" : cardFullName.trim();
        return "Фамилия и имя в сертификате («" + cn + "») не совпадают с данными "
                + "в карточке («" + card + "»). Проверьте ФИО в карточке клиента — "
                + "если данные неверны, их нужно уточнить в банке.";
    }

    private static List<String> nameTokens(String value) {
        List<String> tokens = new ArrayList<>();
        if (value == null) return tokens;
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\- ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) return tokens;
        for (String token : normalized.split(" ")) {
            if (!token.isEmpty()) tokens.add(token);
        }
        return tokens;
    }
}
