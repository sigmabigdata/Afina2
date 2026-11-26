/**
 * Отправка диагностики с рабочего места на сервер.
 *
 * Используется только для разбора проблем с плагином КриптоПро:
 * передаются версия сборки, браузер и тексты ошибок — без персональных данных.
 */
export async function sendClientLog(kind: string, message: string, details?: unknown): Promise<void> {
  try {
    await fetch('/api/v1/public/client-log', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        kind,
        message: String(message).slice(0, 500),
        details: details ? JSON.stringify(details).slice(0, 1500) : '',
        build: typeof __BUILD_STAMP__ !== 'undefined' ? __BUILD_STAMP__ : 'unknown',
        ua: navigator.userAgent.slice(0, 300),
        origin: window.location.origin,
        at: new Date().toISOString(),
      }),
    });
  } catch {
    // диагностика не должна мешать работе
  }
}
