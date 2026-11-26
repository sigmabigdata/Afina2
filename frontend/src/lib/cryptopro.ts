/**
 * Работа с браузерным плагином КриптоПро ЭЦП Browser plug-in.
 *
 * Плагин выполняет криптографию на рабочем месте: закрытый ключ не покидает
 * контейнер (Рутокен/реестр/Токен). Сервер получает только отсоединённую
 * подпись CAdES-BES и проверяет её библиотекой JCSP (ГОСТ 34.10-2012).
 *
 * Поддерживаются только настольные браузеры (Chrome/Edge/Firefox/Yandex)
 * с установленным плагином и КриптоПро CSP.
 */

const PLUGIN_URL = 'https://www.cryptopro.ru/sites/default/files/products/cades/cadesplugin_api.js';

/* eslint-disable @typescript-eslint/no-explicit-any */
declare global {
  interface Window {
    cadesplugin?: any;
  }
}

export interface CertificateInfo {
  thumbprint: string;
  subject: string;
  cn: string;
  snils: string;
  issuer: string;
  validFrom: string;
  validTo: string;
  hasPrivateKey: boolean;
}

export class CryptoProError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CryptoProError';
  }
}

/**
 * API плагина. Методы появляются не сразу после загрузки скрипта,
 * поэтому объект ищется терпеливо (см. waitForApi).
 */
export interface CryptoApi {
  CreateObjectAsync: (name: string) => Promise<any>;
  // Константы CAPICOM/CADESCOM
  [key: string]: any;
}

const API_TIMEOUT_MS = 12_000;

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

let pluginPromise: Promise<CryptoApi> | null = null;

/** Ищет объект с CreateObjectAsync среди вариантов, которые отдаёт плагин. */
function resolveApi(): CryptoApi | null {
  const global: any = window.cadesplugin;
  if (!global) return null;
  const candidates = [global, global.cadesplugin, global.plugin, global.CAdESCOM];
  for (const candidate of candidates) {
    if (candidate && typeof candidate.CreateObjectAsync === 'function') {
      return candidate as CryptoApi;
    }
  }
  return null;
}

/** Проверка работоспособности: реальный вызов к плагину. */
async function probe(api: CryptoApi): Promise<boolean> {
  // Старые версии плагина не знают CAdESCOM.About, поэтому пробуем два объекта
  for (const name of ['CAdESCOM.About', 'CAdESCOM.Store']) {
    try {
      const object = await api.CreateObjectAsync(name);
      if (object) return true;
    } catch {
      // пробуем следующий вариант
    }
  }
  return false;
}

/**
 * Ждёт, пока плагин инициализируется и начнёт отвечать.
 *
 * Важно: `window.cadesplugin` появляется сразу после загрузки скрипта, но его
 * методы работают только после рукопожатия с расширением. Если расширения нет,
 * вызов падает с TypeError «Cannot read properties of undefined» — поэтому
 * готовность проверяется настоящим вызовом CreateObjectAsync.
 */
async function waitForApi(timeoutMs = API_TIMEOUT_MS): Promise<CryptoApi> {
  const started = Date.now();

  // Некоторые версии API — thenable: даём шанс рукопожатию
  const global: any = window.cadesplugin;
  if (global && typeof global.then === 'function') {
    await Promise.race([
      Promise.resolve(global).catch(() => undefined),
      sleep(Math.min(5000, timeoutMs)),
    ]);
  }

  while (Date.now() - started < timeoutMs) {
    const api = resolveApi();
    if (api && await probe(api)) return api;
    await sleep(200);
  }

  if (!global) {
    throw new CryptoProError(
      'Плагин КриптоПро не установлен. Установите «КриптоПро ЭЦП Browser plug-in» '
      + 'с сайта cryptopro.ru, затем перезапустите браузер.',
    );
  }
  throw new CryptoProError(
    'Расширение КриптоПро не отвечает. Проверьте, что «КриптоПро ЭЦП Browser plug-in» '
    + 'установлено и включено в браузере, а КриптоПро CSP запущен. '
    + 'После установки расширения перезапустите браузер.',
  );
}

/** Ошибка «плагин недоступен» в разных формулировках браузера. */
function isPluginMissingError(e: unknown): boolean {
  const message = e instanceof Error ? e.message : String(e);
  return message.includes('CreateObjectAsync')
      || message.includes('CreateObject')
      || message.includes('cadesplugin');
}

/** Обёртка вызова плагина с понятной ошибкой вместо TypeError. */
export async function createObject(api: CryptoApi, name: string): Promise<any> {
  try {
    return await api.CreateObjectAsync(name);
  } catch (e) {
    if (isPluginMissingError(e)) {
      throw new CryptoProError(
        'Расширение КриптоПро не отвечает. Установите «КриптоПро ЭЦП Browser plug-in» '
        + 'и перезапустите браузер.',
      );
    }
    throw e;
  }
}

/** Загружает API плагина (один раз). */
export function loadCryptoPro(): Promise<CryptoApi> {
  if (pluginPromise) return pluginPromise;

  pluginPromise = (async () => {
    if (resolveApi()) return resolveApi() as CryptoApi;

    if (!window.cadesplugin) {
      await new Promise<void>((resolve, reject) => {
        const script = document.createElement('script');
        script.src = PLUGIN_URL;
        script.async = true;
        const timer = setTimeout(() => resolve(), 12_000);
        script.onload = () => { clearTimeout(timer); resolve(); };
        script.onerror = () => {
          clearTimeout(timer);
          reject(new CryptoProError(
            'Не удалось загрузить API плагина КриптоПро. '
            + 'Проверьте доступ к www.cryptopro.ru из браузера.'));
        };
        document.head.appendChild(script);
      });
    }

    return waitForApi();
  })().catch((e) => {
    // даём шанс повторной попытки при следующем открытии окна
    pluginPromise = null;
    throw e;
  });

  return pluginPromise;
}

/** Плагин доступен в этом браузере? */
export async function isCryptoProAvailable(): Promise<boolean> {
  try {
    await loadCryptoPro();
    return true;
  } catch {
    return false;
  }
}

function extractCn(subject: string): string {
  const match = /CN=([^,]+)/i.exec(subject);
  return match ? match[1].replace(/"/g, '').trim() : subject;
}

function extractSnils(subject: string): string {
  const match = /(?:СНИЛС|SNILS)\s*=\s*([0-9\-\s]{11,20})/i.exec(subject);
  if (!match) return '';
  return match[1].replace(/\D/g, '');
}

/** Читает сертификаты из хранилища «Личные» текущего пользователя. */
/** Стандартные значения констант CAPICOM/CADESCOM.
 *  Берём из плагина, а если их там нет — используем известные значения. */
function constants(api: CryptoApi) {
  // Константы живут на глобальном объекте плагина; если resolveApi вернул
  // внутренний объект — берём их всё равно из window.cadesplugin.
  const global: any = window.cadesplugin ?? {};
  const pick = (name: string, fallback: number) =>
    api[name] ?? global[name] ?? fallback;
  return {
    currentUserStore: pick('CAPICOM_CURRENT_USER_STORE', 2),
    openMaxAllowed: pick('CAPICOM_STORE_OPEN_MAXIMUM_ALLOWED', 2),
    findSha1: pick('CAPICOM_CERTIFICATE_FIND_SHA1_HASH', 0),
    cadesBes: pick('CADESCOM_CADES_BES', 1),
    base64ToBinary: pick('CADESCOM_BASE64_TO_BINARY', 1),
  };
}

/** Открывает хранилище «Личные» с несколькими попытками (плагин иногда
 *  «просыпается» не мгновенно после рукопожатия). */
async function openStore(api: CryptoApi): Promise<any> {
  const c = constants(api);
  let lastError: unknown;
  for (let attempt = 1; attempt <= 5; attempt += 1) {
    try {
      const store = await createObject(api, 'CAdESCOM.Store');
      await store.Open(c.currentUserStore, 'My', c.openMaxAllowed);
      return store;
    } catch (e) {
      lastError = e;
      await sleep(400 * attempt);
    }
  }
  const message = lastError instanceof Error ? lastError.message : String(lastError);
  throw new CryptoProError(
    `Не удалось открыть хранилище сертификатов «Личные»: ${message}. `
    + 'Проверьте, что КриптоПро CSP запущен и контейнер с ключом подключён.',
  );
}

/** Читает сертификаты из хранилища «Личные» текущего пользователя. */
export async function listCertificates(): Promise<CertificateInfo[]> {
  const api = await loadCryptoPro();
  const store = await openStore(api);
  try {
    const certificates = await store.Certificates;
    const count: number = await certificates.Count;
    const result: CertificateInfo[] = [];
    for (let i = 1; i <= count; i += 1) {
      try {
        const cert = await certificates.Item(i);
        const subject: string = await cert.SubjectName;
        const issuer: string = await cert.IssuerName;
        const thumbprint: string = await cert.Thumbprint;
        let validFrom = '';
        let validTo = '';
        try {
          validFrom = (await cert.ValidFromDate)?.toISOString?.() ?? '';
          validTo = (await cert.ValidToDate)?.toISOString?.() ?? '';
        } catch {
          // даты есть не во всех версиях плагина
        }
        let hasPrivateKey = true;
        try {
          hasPrivateKey = await cert.HasPrivateKey();
        } catch {
          hasPrivateKey = true;
        }
        result.push({
          thumbprint,
          subject,
          cn: extractCn(subject),
          snils: extractSnils(subject),
          issuer,
          validFrom,
          validTo,
          hasPrivateKey,
        });
      } catch (e) {
        // Один проблемный сертификат не должен ломать весь список
        console.warn('CryptoPro: пропущен сертификат #' + i, e);
      }
    }
    return result;
  } finally {
    try {
      await store.Close();
    } catch {
      // хранилище могло закрыться само
    }
  }
}

/** Подписывает данные (base64) отсоединённой подписью CAdES-BES. */
export async function signDetached(
  base64Content: string,
  thumbprint: string,
): Promise<string> {
  const api = await loadCryptoPro();
  const c = constants(api);
  const store = await createObject(api, 'CAdESCOM.Store');
  let certificate: any;
  try {
    await store.Open(c.currentUserStore, 'My', c.openMaxAllowed);
    const certificates = await store.Certificates;
    const found = await certificates.Find(c.findSha1, thumbprint);
    const count: number = await found.Count;
    if (count < 1) {
      throw new CryptoProError('Сертификат не найден в хранилище «Личные»');
    }
    certificate = await found.Item(1);
  } finally {
    await store.Close();
  }

  const data = await createObject(api, 'CAdESCOM.CadesSignedData');
  await data.propset_ContentEncoding(c.base64ToBinary);
  await data.propset_Content(base64Content);
  return data.SignCades(certificate, c.cadesBes, true);
}

/** Base64 из UTF-8 строки. */
export function toBase64(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = '';
  bytes.forEach((b) => { binary += String.fromCharCode(b); });
  return btoa(binary);
}

/** Диагностика плагина для экрана входа: что именно не работает. */
export interface PluginDiagnostics {
  scriptLoaded: boolean;
  apiPresent: boolean;
  responds: boolean;
  version?: string;
  storeOpened?: boolean;
  certificatesCount?: number;
  error?: string;
}

export async function diagnosePlugin(): Promise<PluginDiagnostics> {
  const scriptLoaded = Boolean(window.cadesplugin);
  const api = resolveApi();
  const result: PluginDiagnostics = {
    scriptLoaded,
    apiPresent: Boolean(api),
    responds: false,
  };
  if (!api) {
    result.error = scriptLoaded
      ? 'Скрипт плагина загружен, но API не инициализировался'
      : 'Скрипт плагина не загружен';
    return result;
  }

  // 1) Плагин отвечает?
  try {
    const about = await api.CreateObjectAsync('CAdESCOM.About');
    result.responds = true;
    try {
      result.version = String(await about.Version ?? '');
    } catch {
      // версия не критична
    }
  } catch (e) {
    result.error = e instanceof Error ? e.message : String(e);
    return result;
  }

  // 2) Открывается ли хранилище и видны ли сертификаты?
  try {
    const store = await openStore(api);
    try {
      const certificates = await store.Certificates;
      result.certificatesCount = Number(await certificates.Count) || 0;
      result.storeOpened = true;
    } finally {
      try { await store.Close(); } catch { /* уже закрыто */ }
    }
  } catch (e) {
    result.storeOpened = false;
    result.error = e instanceof Error ? e.message : String(e);
  }
  return result;
}
