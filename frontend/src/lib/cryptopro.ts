/**
 * Работа с плагином КриптоПро ЭЦП Browser plug-in.
 *
 * Реализация портирована из AfinaZ: используется та же вендорная библиотека
 * (`vendor/crypto-pro-cadesplugin.js`), которая сама дожидается готовности
 * расширения и умеет читать сертификаты и подписывать. Это устраняет
 * «плавающие» ошибки инициализации, из-за которых первые вызовы плагина
 * падали с `Cannot read properties of undefined`.
 *
 * Плагин выполняет криптографию на рабочем месте: закрытый ключ не покидает
 * контейнер (Рутокен/реестр/Токен). Сервер получает только отсоединённую
 * подпись CAdES-BES и проверяет её библиотекой JCSP (ГОСТ 34.10-2012).
 */
import cadespluginOnload, { type VendorApi, type VendorCertificate }
  from './vendor/crypto-pro-cadesplugin';

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

let apiPromise: Promise<VendorApi> | null = null;

/** Загружает API плагина (один раз, с ожиданием готовности расширения). */
export function loadCryptoPro(): Promise<VendorApi> {
  if (!apiPromise) {
    apiPromise = cadespluginOnload().catch((e: unknown) => {
      apiPromise = null;
      const message = e instanceof Error ? e.message : String(e);
      throw new CryptoProError(
        /cadesplugin|undefined|Cannot read/i.test(message)
          ? 'Плагин КриптоПро не отвечает. Установите «КриптоПро ЭЦП Browser plug-in» '
            + 'и КриптоПро CSP, затем перезапустите браузер.'
          : `Плагин КриптоПро: ${message}`,
      );
    });
  }
  return apiPromise;
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

/** Обратная совместимость: библиотека сама загружает скрипт при импорте. */
export async function ensurePluginScript(): Promise<void> {
  await loadCryptoPro();
}

// ═══════════════════════ Разбор subject ═══════════════════════

/** Значение атрибута X.500 по ключу (CN, O, СНИЛС…). */
function extractValue(key: string, input: string): string | null {
  if (!input) return null;
  const safeKey = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = input.match(new RegExp(`${safeKey}=([^,]+)`, 'i'));
  return match && match[1] ? match[1].trim().replace(/^"|"$/g, '') : null;
}

/** Разбирает hex-строку ASN.1 OCTET STRING со СНИЛС: 12 0b <11 ASCII-цифр>. */
function parseHexSnils(hex: string): string {
  if (!hex) return '';
  const start = hex.substring(0, 2).toLowerCase() === '12' ? 4 : 0;
  let result = '';
  for (let i = start; i < hex.length; i += 2) {
    const code = parseInt(hex.substring(i, i + 2), 16);
    if (code >= 48 && code <= 57) result += String.fromCharCode(code);
  }
  return result.replace(/\D/g, '');
}

/**
 * Извлекает СНИЛС из subject во всех известных формах:
 * «СНИЛС=…», «SNILS=…» и OID 1.2.643.100.3 в hex-виде.
 */
export function extractSnils(subject: string | null | undefined): string {
  if (!subject) return '';
  const direct = extractValue('СНИЛС', subject) ?? extractValue('SNILS', subject);
  if (direct) return direct.replace(/\D/g, '');
  const oid = subject.match(/OID\.1\.2\.643\.100\.3=#([0-9a-fA-F]+)/i)
    ?? subject.match(/1\.2\.643\.100\.3=#([0-9a-fA-F]+)/i);
  return oid ? parseHexSnils(oid[1]) : '';
}

export function extractCn(subject: string | null | undefined): string {
  if (!subject) return '';
  const cn = extractValue('CN', subject);
  return cn ? cn.replace(/^"|"$/g, '').trim() : subject.trim();
}

function toIso(value: unknown): string {
  if (!value) return '';
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? '' : value.toISOString();
  if (typeof value === 'object' && value !== null) {
    const from = (value as { from?: Date | string }).from;
    if (from instanceof Date) return from.toISOString();
    if (typeof from === 'string') return from;
  }
  const date = new Date(String(value));
  return Number.isNaN(date.getTime()) ? '' : date.toISOString();
}

function mapCertificate(cert: VendorCertificate): CertificateInfo {
  const subject = cert.subjectInfo ?? '';
  const period = cert.validPeriod;
  const from = period instanceof Date ? period : (period as { from?: Date })?.from;
  const to = period instanceof Date ? period : (period as { to?: Date })?.to;
  return {
    thumbprint: cert.thumbprint,
    subject,
    cn: extractCn(subject),
    snils: extractSnils(subject),
    issuer: cert.issuerInfo ?? '',
    validFrom: toIso(from),
    validTo: toIso(to),
    hasPrivateKey: true,
  };
}

/** Сертификаты с закрытым ключом из хранилища «Личные». */
export async function listCertificates(): Promise<CertificateInfo[]> {
  const api = await loadCryptoPro();
  try {
    const certs = await api.getCertsList();
    return (certs ?? []).map(mapCertificate);
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    throw new CryptoProError(message);
  }
}

/**
 * Совместимость с прежним API: повторы не нужны — библиотека сама
 * дожидается готовности плагина.
 */
export async function listCertificatesWithRetry(
  _attempts = 1,
  onAttempt?: (attempt: number) => void,
): Promise<CertificateInfo[]> {
  onAttempt?.(1);
  return listCertificates();
}

/** Подписывает данные (base64) отсоединённой подписью CAdES-BES. */
export async function signDetached(
  base64Content: string,
  thumbprint: string,
): Promise<string> {
  const api = await loadCryptoPro();
  try {
    const signature = await api.signBase64(thumbprint, base64Content, true);
    return sanitizeBase64(signature);
  } catch (e) {
    throw new CryptoProError(e instanceof Error ? e.message : String(e));
  }
}

function sanitizeBase64(value: string): string {
  return String(value ?? '')
    .replace(/-----BEGIN[^-]*-----/g, '')
    .replace(/-----END[^-]*-----/g, '')
    .replace(/[^A-Za-z0-9+/=]/g, '');
}

// ═══════════════════════ Диагностика ═══════════════════════

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
  const scriptLoaded = Boolean((window as { cadesplugin?: unknown }).cadesplugin);
  const result: PluginDiagnostics = { scriptLoaded, apiPresent: false, responds: false };
  try {
    const api = await loadCryptoPro();
    result.apiPresent = true;
    try {
      const about = await api.about() as { Version?: string; version?: string };
      result.responds = true;
      result.version = String(about?.Version ?? about?.version ?? '');
    } catch (e) {
      result.error = e instanceof Error ? e.message : String(e);
      return result;
    }
    try {
      const certs = await api.getCertsList();
      result.storeOpened = true;
      result.certificatesCount = certs?.length ?? 0;
    } catch (e) {
      result.storeOpened = false;
      result.error = e instanceof Error ? e.message : String(e);
    }
  } catch (e) {
    result.error = e instanceof Error ? e.message : String(e);
  }
  return result;
}

/** Base64 из UTF-8 строки. */
export function toBase64(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = '';
  bytes.forEach((b) => { binary += String.fromCharCode(b); });
  return btoa(binary);
}
