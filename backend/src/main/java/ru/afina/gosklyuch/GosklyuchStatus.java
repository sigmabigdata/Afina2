package ru.afina.gosklyuch;

/**
 * Жизненный цикл заявки на подписание в МП «Госключ».
 *
 * <p>Соответствие кодам статусов API ЕПГУ (спецификация v1.8,
 * раздел «Получение статусов заявлений»):</p>
 * <ul>
 *   <li>{@code 0} — черновик (заявка создана, пакет не загружен);</li>
 *   <li>{@code 17} — заявка в очереди на подписание (документы загружены);</li>
 *   <li>{@code 3} — подписано;</li>
 *   <li>{@code 4} — отказ от подписания / истёк срок подписания;</li>
 *   <li>{@code 5} — ошибка заявки (в т.ч. {@code SIGN_REJECT},
 *       {@code EXPIRED}, {@code REQUEST_ERROR}, {@code STATE_EDS_NO_ANSWER},
 *       {@code SNILS_NOT_FOUND}).</li>
 * </ul>
 */
public enum GosklyuchStatus {

    /** Заявка создана локально, пакет ещё не отправлен. */
    NEW,

    /** Заявка отправлена в ЕПГУ и ожидает действия подписанта. */
    SENT,

    /** Документ(ы) подписаны, подпись получена. */
    SIGNED,

    /** Подписант отказался от подписания. */
    REJECTED,

    /** Истёк срок подписания ({@code SignExpiration}). */
    EXPIRED,

    /** Ошибка обработки заявки на стороне ЕПГУ. */
    ERROR;

    /** Код статуса в терминальном состоянии (подпись получена). */
    public static final int CODE_SIGNED = 3;

    /** Код статуса «отказ от подписания / истёк срок». */
    public static final int CODE_REJECTED = 4;

    /** Код статуса «ошибка заявки». */
    public static final int CODE_ERROR = 5;

    /** Является ли статус терминальным (дальнейшее ожидание бессмысленно). */
    public boolean isTerminal() {
        return this == SIGNED || this == REJECTED || this == EXPIRED || this == ERROR;
    }

    /**
     * Преобразовать числовой код статуса API ЕПГУ в локальный статус.
     * Неизвестные коды трактуются как «в процессе» (ожидание), чтобы
     * не завершить заявку ошибочно.
     */
    public static GosklyuchStatus fromCode(Integer code) {
        if (code == null) return NEW;
        return switch (code) {
            case 0 -> NEW;
            case 17 -> SENT;
            case CODE_SIGNED -> SIGNED;
            case CODE_REJECTED -> REJECTED;
            case CODE_ERROR -> ERROR;
            default -> SENT;
        };
    }
}
