package ru.afina.demo;

import java.util.UUID;

/**
 * Текущий демо-тенант (одна сессия = одна изолированная H2-база).
 * Устанавливается {@link DemoTenantFilter} на время HTTP-запроса.
 */
public final class DemoTenantContext {

    /** Тенант для фоновых потоков/стартовой инициализации (служит шаблоном схемы). */
    public static final String TEMPLATE_TENANT = "template";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private DemoTenantContext() {
    }

    public static String get() {
        String t = CURRENT.get();
        return t == null ? TEMPLATE_TENANT : t;
    }

    public static void set(String tenant) {
        CURRENT.set(tenant);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String newTenantId() {
        return "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
