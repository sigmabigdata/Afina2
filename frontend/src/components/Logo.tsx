/**
 * Логотип Afina2.
 *
 * Типографический знак: монограмма «А» в градиентном скруглённом квадрате
 * и словесный знак «Афина». Векторный (без растровых картинок), поэтому
 * остаётся резким на любом экране и корректно работает в тёмной теме.
 */
export default function Logo({
  size = 38,
  collapsed = false,
  tagline,
}: {
  /** Высота монограммы в пикселях */
  size?: number;
  /** Только знак, без надписи (свёрнутое меню) */
  collapsed?: boolean;
  /** Подпись под названием */
  tagline?: string;
}) {
  const radius = Math.round(size * 0.3);
  const letter = Math.round(size * 0.52);

  return (
    <span className="logo">
      <span
        className="logo-mark"
        style={{
          width: size,
          height: size,
          borderRadius: radius,
          fontSize: letter,
        }}
        aria-hidden="true"
      >
        А
      </span>
      {!collapsed && (
        <span className="logo-text">
          <span className="logo-word">Афина</span>
          {tagline && <span className="logo-tagline">{tagline}</span>}
        </span>
      )}
    </span>
  );
}
