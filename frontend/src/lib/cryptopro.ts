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

let pluginPromise: Promise<any> | null = null;

/** Загружает API плагина (один раз). */
export function loadCryptoPro(): Promise<any> {
  if (pluginPromise) return pluginPromise;
  pluginPromise = new Promise((resolve, reject) => {
    const finish = () => {
      const api = window.cadesplugin;
      if (!api) {
        reject(new CryptoProError('Плагин КриптоПро не найден'));
        return;
      }
      const ready = api.cadesplugin;
      if (ready && typeof ready.then === 'function') {
        ready.then(() => resolve(api)).catch(() => reject(
          new CryptoProError('Плагин КриптоПро не отвечает. Проверьте установку CSP.')));
      } else {
        resolve(api);
      }
    };

    if (window.cadesplugin) {
      finish();
      return;
    }
    const script = document.createElement('script');
    script.src = PLUGIN_URL;
    script.async = true;
    script.onload = finish;
    script.onerror = () => reject(new CryptoProError(
      'Не удалось загрузить плагин КриптоПро. Проверьте доступ к cryptopro.ru.'));
    document.head.appendChild(script);
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
export async function listCertificates(): Promise<CertificateInfo[]> {
  const api = await loadCryptoPro();
  const store = await api.CreateObjectAsync('CAdESCOM.Store');
  try {
    await store.Open(
      api.CAPICOM_CURRENT_USER_STORE,
      'My',
      api.CAPICOM_STORE_OPEN_MAXIMUM_ALLOWED,
    );
    const certificates = await store.Certificates;
    const count: number = await certificates.Count;
    const result: CertificateInfo[] = [];
    for (let i = 1; i <= count; i += 1) {
      const cert = await certificates.Item(i);
      const subject: string = await cert.SubjectName;
      const issuer: string = await cert.IssuerName;
      const thumbprint: string = await cert.Thumbprint;
      const validFrom: Date = await cert.ValidFromDate;
      const validTo: Date = await cert.ValidToDate;
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
        validFrom: validFrom?.toISOString?.() ?? '',
        validTo: validTo?.toISOString?.() ?? '',
        hasPrivateKey,
      });
    }
    return result;
  } finally {
    await store.Close();
  }
}

/** Подписывает данные (base64) отсоединённой подписью CAdES-BES. */
export async function signDetached(
  base64Content: string,
  thumbprint: string,
): Promise<string> {
  const api = await loadCryptoPro();
  const store = await api.CreateObjectAsync('CAdESCOM.Store');
  let certificate: any;
  try {
    await store.Open(
      api.CAPICOM_CURRENT_USER_STORE,
      'My',
      api.CAPICOM_STORE_OPEN_MAXIMUM_ALLOWED,
    );
    const certificates = await store.Certificates;
    const found = await certificates.Find(
      api.CAPICOM_CERTIFICATE_FIND_SHA1_HASH, thumbprint,
    );
    const count: number = await found.Count;
    if (count < 1) {
      throw new CryptoProError('Сертификат не найден в хранилище «Личные»');
    }
    certificate = await found.Item(1);
  } finally {
    await store.Close();
  }

  const data = await api.CreateObjectAsync('CAdESCOM.CadesSignedData');
  await data.propset_ContentEncoding(api.CADESCOM_BASE64_TO_BINARY);
  await data.propset_Content(base64Content);
  return data.SignCades(certificate, api.CADESCOM_CADES_BES, true);
}

/** Base64 из UTF-8 строки. */
export function toBase64(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = '';
  bytes.forEach((b) => { binary += String.fromCharCode(b); });
  return btoa(binary);
}
