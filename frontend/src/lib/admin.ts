/** Общие помощники админских экранов: тексты ошибок, роли, группы настроек. */

import { ApiError } from './api';
import type { Role } from './types';

/** Человеческий текст ошибки API (сервер уже присылает русское сообщение). */
export function errorText(e: unknown, fallback = 'Не удалось выполнить действие'): string {
  if (e instanceof ApiError) return e.message || fallback;
  if (e instanceof Error) return e.message || fallback;
  return fallback;
}

export const ROLE_LABELS: Record<Role, string> = {
  ROLE_ADMIN: 'Администратор',
  ROLE_EMPLOYEE: 'Сотрудник',
  ROLE_USER: 'Клиент',
};

/** Варианты роли для селектов. */
export const ROLE_OPTIONS: { value: Role; label: string }[] = (
  Object.keys(ROLE_LABELS) as Role[]
).map((role) => ({ value: role, label: ROLE_LABELS[role] }));

export function roleLabel(role?: string): string {
  if (!role) return '—';
  return ROLE_LABELS[role as Role] ?? role;
}

/** Заголовки групп настроек по префиксу ключа (`smtp.host` → «Почта»). */
const SETTING_GROUPS: Record<string, string> = {
  smtp: 'Почта (SMTP)',
  mail: 'Почта',
  auth: 'Аутентификация и сессии',
  admin: 'Административная панель',
  monitor: 'Мониторинг и алерты',
  keys: 'Ключи шифрования',
  crl: 'CRL и проверка отзыва',
  pki: 'Криптография',
  crypto: 'Криптография',
  consent: 'Согласия на обработку ПДн',
  gosklyuch: 'Интеграция «Госключ»',
  documents: 'Документы',
  document: 'Документы',
  storage: 'Хранилище',
  app: 'Приложение',
  audit: 'Аудит',
  ssl: 'TLS-сертификат',
  backup: 'Резервные копии',
  backups: 'Резервные копии',
};

/** Человекочитаемое название группы по префиксу ключа настройки. */
export function settingGroupTitle(prefix: string): string {
  return SETTING_GROUPS[prefix.toLowerCase()]
    ?? (prefix ? prefix.charAt(0).toUpperCase() + prefix.slice(1) : 'Прочее');
}
