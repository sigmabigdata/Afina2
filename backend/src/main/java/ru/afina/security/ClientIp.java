package ru.afina.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Определение реального IP клиента с защитой от спуфинга заголовков.
 *
 * <p>Заголовки {@code X-Real-IP} / {@code X-Forwarded-For} доверяем
 * <b>только если</b> запрос пришёл от доверенного прокси (Angie в docker-сети,
 * loopback или RFC1918-адрес). Иначе берём {@code getRemoteAddr()} — так прямой
 * запрос к приложению не может подделать IP и обойти whitelist админки.
 *
 * <p>Из {@code X-Forwarded-For} берётся <b>последний</b> адрес цепочки, потому что
 * его добавляет наш прокси ({@code $proxy_add_x_forwarded_for}), а левые значения
 * может прислать сам клиент.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String resolve(HttpServletRequest req) {
        if (req == null) return null;
        String remote = req.getRemoteAddr();
        if (!isTrustedProxy(remote)) {
            return remote;
        }

        // Angie ставит X-Real-IP = $remote_addr (перезаписывает, не дополняет).
        String realIp = trimHeader(req.getHeader("X-Real-IP"));
        if (realIp != null && isIpLiteral(realIp)) {
            return realIp;
        }

        String xff = trimHeader(req.getHeader("X-Forwarded-For"));
        if (xff != null) {
            String[] parts = xff.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = parts[i].trim();
                if (isIpLiteral(candidate)) {
                    return candidate;
                }
            }
        }
        return remote;
    }

    /** Доверенный прокси: loopback или приватная сеть (docker-сети 172.16/12 и т.п.). */
    public static boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank()) return false;
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return true;
        if (ip.startsWith("127.")) return true;
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            int dot = ip.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int second = Integer.parseInt(ip.substring(4, dot));
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    /** Грубая проверка, что строка похожа на IP-литерал (без hostname/инъекций). */
    public static boolean isIpLiteral(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty() || v.length() > 45) return false;
        int colon = v.indexOf(':');
        if (colon > 0) {
            // IPv6 (возможно в скобках [::1])
            if (v.startsWith("[") && v.endsWith("]")) v = v.substring(1, v.length() - 1);
            return v.matches("[0-9a-fA-F:.]+") && v.contains(":");
        }
        return v.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static String trimHeader(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
}
