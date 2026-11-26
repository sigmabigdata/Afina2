/**
 * Логотип Afina2.
 *
 * Два режима:
 *  • стандартный — векторный знак «А» (две штриховые стойки + росчерк)
 *    и словесный знак; масштабируется без потерь, работает в тёмной теме;
 *  • логотип заказчика (белый лейбл) — картинка, загруженная в админке;
 *    показывается вместо знака и названия.
 */
export default function Logo({
  size = 38,
  collapsed = false,
  tagline,
  name = 'Афина',
  src,
}: {
  /** Высота знака в пикселях */
  size?: number;
  /** Только знак, без надписи (свёрнутое меню) */
  collapsed?: boolean;
  /** Подпись под названием */
  tagline?: string;
  /** Название из брендинга */
  name?: string;
  /** Логотип заказчика (если загружен) */
  src?: string | null;
}) {
  if (src) {
    return (
      <span className="logo logo--custom">
        <img
          src={src}
          alt={name}
          className="logo-img"
          style={{ height: Math.round(size * 0.92) }}
        />
      </span>
    );
  }

  const width = Math.round(size * 0.92);
  const height = Math.round(size * 1.06);

  return (
    <span className="logo">
      <svg
        className="logo-mark-svg"
        width={width}
        height={height}
        viewBox="0 0 60 66"
        role="img"
        aria-label={name}
      >
        <defs>
          <linearGradient id="afina-grad" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="#4338ca" />
            <stop offset="60%" stopColor="#6d4aff" />
            <stop offset="100%" stopColor="#a855f7" />
          </linearGradient>
        </defs>
        {/* Геометрическая «А»: чистый знак, узнаваемый в любом размере */}
        <path
          fill="url(#afina-grad)"
          fillRule="evenodd"
          d="M30 4 L56 58 H44 L39 46 H21 L16 58 H4 Z M30 22 L35 36 H25 Z"
        />
      </svg>

      {!collapsed && (
        <span className="logo-text">
          <span className="logo-word">{name}</span>
          {tagline && <span className="logo-tagline">{tagline}</span>}
        </span>
      )}
    </span>
  );
}
