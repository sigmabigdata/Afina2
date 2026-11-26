/**
 * HTTP-клиент Afina2.
 *
 * Один вход для всех запросов к `/api/v1`: cookie-сессия, CSRF-заголовок,
 * единый разбор ошибок бэкенда (`{error, message}`) и типобезопасные вызовы.
 */

const BASE = '/api/v1';

export class ApiError extends Error {
  status: number;
  code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
    this.name = 'ApiError';
  }
}

/** CSRF-токен хранится в памяти после первого запроса. */
let csrfToken: string | null = null;
let csrfPromise: Promise<string | null> | null = null;

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

async function ensureCsrf(): Promise<string | null> {
  if (csrfToken) return csrfToken;
  if (!csrfPromise) {
    csrfPromise = fetch(`${BASE}/auth/csrf`, { credentials: 'include' })
      .then(async (res) => {
        if (!res.ok) return null;
        const data = await res.json().catch(() => null);
        csrfToken = data?.token ?? readCookie('XSRF-TOKEN');
        return csrfToken;
      })
      .catch(() => null)
      .finally(() => {
        csrfPromise = null;
      });
  }
  return csrfPromise;
}

/** Сброс токена (после логина/логаута сессия пересоздаётся). */
export function resetCsrf(): void {
  csrfToken = null;
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  form?: FormData;
  signal?: AbortSignal;
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, form, signal } = options;
  const headers: Record<string, string> = { Accept: 'application/json' };

  if (method !== 'GET') {
    const token = await ensureCsrf();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  if (body !== undefined && !form) {
    headers['Content-Type'] = 'application/json';
  }

  const response = await fetch(`${BASE}${path}`, {
    method,
    credentials: 'include',
    headers,
    body: form ?? (body !== undefined ? JSON.stringify(body) : undefined),
    signal,
  });

  if (response.status === 204) return undefined as T;

  const contentType = response.headers.get('content-type') ?? '';
  const isJson = contentType.includes('application/json');

  if (!response.ok) {
    let code = 'error';
    let message = `Ошибка ${response.status}`;
    if (isJson) {
      const data = await response.json().catch(() => null);
      if (data) {
        code = data.error ?? code;
        message = data.message ?? message;
      }
    }
    if (response.status === 401) {
      resetCsrf();
    }
    throw new ApiError(response.status, code, message);
  }

  if (isJson) return (await response.json()) as T;
  return (await response.text()) as unknown as T;
}

/** Имя файла из заголовка Content-Disposition. */
function fileNameFrom(disposition: string): string | null {
  const utfMatch = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (utfMatch) return decodeURIComponent(utfMatch[1]);
  const plainMatch = /filename="?([^";]+)"?/i.exec(disposition);
  return plainMatch ? plainMatch[1] : null;
}

/** Параметры запроса, возвращающего бинарный ответ. */
export interface BlobRequestOptions {
  method?: 'GET' | 'POST' | 'PUT';
  body?: unknown;
}

/**
 * Запрос бинарного ответа (PDF, ZIP, CSV): возвращает блоб и имя файла.
 *
 * Нужен там, где сервер отдаёт файл только по POST (превью шаблона,
 * выгрузка ключей под паролем) — обычный `download()` работает лишь с GET.
 */
export async function fetchBlob(
  path: string,
  options: BlobRequestOptions = {},
): Promise<{ blob: Blob; fileName: string | null }> {
  const { method = 'GET', body } = options;
  const headers: Record<string, string> = {};

  if (method !== 'GET') {
    const token = await ensureCsrf();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const response = await fetch(`${BASE}${path}`, {
    method,
    credentials: 'include',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!response.ok) {
    let message = `Ошибка ${response.status}`;
    if ((response.headers.get('content-type') ?? '').includes('application/json')) {
      const data = await response.json().catch(() => null) as { message?: string } | null;
      if (data?.message) message = data.message;
    }
    if (response.status === 401) resetCsrf();
    throw new ApiError(response.status, 'download_failed', message);
  }

  return {
    blob: await response.blob(),
    fileName: fileNameFrom(response.headers.get('content-disposition') ?? ''),
  };
}

/** Сохранить блоб на диск под заданным именем. */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/** Скачивание файла с учётом имени из Content-Disposition. */
export async function download(path: string, fallbackName = 'file'): Promise<void> {
  const { blob, fileName } = await fetchBlob(path);
  saveBlob(blob, fileName ?? fallbackName);
}

/** URL для открытия ресурса в новой вкладке (PDF, визуализация). */
export function resourceUrl(path: string): string {
  return `${BASE}${path}`;
}
