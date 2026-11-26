/** Типы данных Afina2 — зеркало REST DTO бэкенда (`ru.afina.api.dto`). */

export type Role = 'ROLE_USER' | 'ROLE_EMPLOYEE' | 'ROLE_ADMIN';

export interface UserDto {
  id: number;
  email: string;
  fullName: string;
  phone?: string;
  snils?: string;
  role: Role;
  enabled: boolean;
  profileComplete: boolean;
  consentSigned: boolean;
  consentSignedAt?: string;
  lastLoginAt?: string;
  createdAt: string;
  documentsTotal: number;
  documentsSigned: number;
}

export interface SignatureDto {
  id: number;
  signerSubject: string;
  signerSerial?: string;
  signedAt: string;
  applyPoa: boolean;
  signerUserId?: number;
  signerName?: string;
}

export interface VisualizationDto {
  version: number;
  size: number;
  createdAt: string;
  updatedAt: string;
}

export interface GosklyuchItemDto {
  id: number;
  documentId: number;
  fileName: string;
  signed: boolean;
  applied: boolean;
}

export type GosklyuchStatus =
  | 'NEW' | 'SENT' | 'SIGNED' | 'REJECTED' | 'EXPIRED' | 'ERROR';

export interface GosklyuchOrderDto {
  id: number;
  documentId: number;
  documentName: string;
  ownerId: number;
  ownerName: string;
  signerSnils?: string;
  externalOrderId?: number;
  status: GosklyuchStatus;
  statusName?: string;
  statusCode?: number;
  error?: string;
  createdAt: string;
  updatedAt?: string;
  completedAt?: string;
  items: GosklyuchItemDto[];
}

export interface DocumentDto {
  id: number;
  name: string;
  contentType?: string;
  size: number;
  sizePretty: string;
  extension: string;
  fileSha256?: string;
  signed: boolean;
  signedAt?: string;
  signerSubject?: string;
  uploadedAt: string;
  applyPoa: boolean;
  publicUuid: string;
  folderId?: number;
  folderName?: string;
  ownerId?: number;
  ownerName?: string;
  signaturesCount: number;
  signatures: SignatureDto[];
  visualization?: VisualizationDto;
  gosklyuchOrder?: GosklyuchOrderDto;
}

export interface FolderDto {
  id: number;
  name: string;
  documentsCount: number;
  signedCount: number;
  createdAt: string;
}

export interface TemplateDto {
  id: number;
  kind: 'DOCUMENT' | 'CONSENT';
  name: string;
  description?: string;
  htmlContent?: string;
  fieldsJson?: string;
  active: boolean;
  sortOrder: number;
  createdBy?: string;
  createdAt: string;
  updatedAt?: string;
  fieldsCount: number;
}

export interface TemplateField {
  key: string;
  label: string;
  type: string;
  required: boolean;
  defaultValue?: string;
}

export interface AuditEventDto {
  id: number;
  eventTime: string;
  eventType: string;
  result: string;
  actorEmail?: string;
  actorRole?: string;
  actorIp?: string;
  targetType?: string;
  targetId?: string;
  targetInfo?: string;
  details?: string;
}

export interface CrlDto {
  fileName: string;
  sizeBytes: number;
  sizePretty: string;
  issuer?: string;
  lastUpdate?: string;
  nextUpdate?: string;
  downloadedAt?: string;
  daysToExpiry: number;
  manual: boolean;
  status: 'ok' | 'expiring' | 'expired' | 'untrusted';
}

export interface SettingDto {
  key: string;
  value: string;
  description?: string;
  updatedAt?: string;
  updatedBy?: string;
  secret?: boolean;
}

export interface PoaDto {
  id: number;
  fio: string;
  snils?: string;
  inn?: string;
  orgName?: string;
  orgInn?: string;
  docNumber?: string;
  stampNumber?: string;
  docDate?: string;
  validFrom?: string;
  validUntil?: string;
  notaryName?: string;
  active: boolean;
  effective: boolean;
  createdBy?: string;
  createdAt: string;
}

export interface PageDto<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
  pages: number;
}

export interface DashboardPoint {
  date: string;
  label: string;
  signed: number;
  uploaded: number;
}

export interface DashboardDto {
  documentsTotal: number;
  documentsSigned: number;
  documentsUnsigned: number;
  foldersTotal: number;
  gosklyuchPending: number;
  signaturesToday: number;
  averageSigningTime: string;
  activity: DashboardPoint[];
  recentDocuments: DocumentDto[];
  recentOrders: GosklyuchOrderDto[];
}

// ─────────────────────────── админка ───────────────────────────

/** Карточка клиента: профиль, разделы, документы и заявки Госключа. */
export interface ClientCardDto {
  client: UserDto;
  folders: FolderDto[];
  documents: DocumentDto[];
  gosklyuchOrders: GosklyuchOrderDto[];
  consentConfigured: boolean;
}

/** Сводка журнала аудита (`GET /admin/audit/stats`). */
export interface AuditStatsDto {
  total: number;
  today: number;
  failures: number;
  byType: Record<string, number>;
}

/** Сводка по каталогу CRL (`GET /admin/crl/summary`). */
export interface CrlSummaryDto {
  count: number;
  totalSize: number;
  totalSizePretty: string;
  expiring: number;
  expired: number;
}

/** Элемент отчёта о доверии CRL (`GET /admin/crl/status`). */
export interface CrlStatusItemDto {
  fileName: string;
  issuerCn?: string;
  thisUpdate?: string;
  nextUpdate?: string;
  sizePretty?: string;
  signatureVerified?: boolean;
  status: string;
  note?: string;
}

/** Отчёт о доверии CRL. */
export interface CrlStatusDto {
  items: CrlStatusItemDto[];
  summary: {
    total: number;
    ok: number;
    expiring: number;
    expired: number;
    untrusted: number;
  };
}

/** Живая проверка компонента (`GET /admin/monitor/checks`). */
export interface MonitorCheckDto {
  component: string;
  title: string;
  healthy: boolean;
  message?: string;
  latencyMs?: number;
}

/** Событие истории мониторинга (`GET /admin/monitor/history`). */
export interface MonitorHistoryDto {
  id: number;
  checkedAt: string;
  status: string;
  component: string;
  reason?: string;
  alertSent: boolean;
}

/** Последние строки журнала приложения (`GET /admin/monitor/logs`). */
export interface MonitorLogsDto {
  lines: string[];
  count?: number;
  requested?: number;
}

/** Метрики сервера (`GET /admin/system`). */
export interface SystemInfoDto {
  uptimeSeconds: number;
  uptimePretty: string;
  startedAt: string;
  memory: { total: number; used: number; available: number; free: number; percent: number };
  cpu: { cores: number; loadAvg: number[]; percent: number };
  disk: { total: number; used: number; free: number; percent: number };
  storage: { bytes: number; pretty: string; files: number };
  database: {
    sizeBytes?: number;
    sizePretty?: string;
    connections?: number;
    usersCount?: number;
    docsCount?: number;
    sigsCount?: number;
    tables?: { name: string; rows: number; size: string }[];
    migrations?: { version: string; description: string; success: boolean }[];
  };
  runtime: Record<string, string>;
  diagnostics: { name: string; value: string; ok: boolean }[];
}

/** Проверка самодиагностики Госключа. */
export interface GosklyuchCheckDto {
  name: string;
  status: 'OK' | 'WARN' | 'ERROR';
  message?: string;
}

/** Отчёт самопроверки конфигурации Госключа. */
export interface GosklyuchDiagnosticsDto {
  enabled: boolean;
  provider: string;
  signer: string;
  readyToSend: boolean;
  checks: GosklyuchCheckDto[];
  settings: Record<string, string>;
}

/** Состояние интеграции с Госключом (`GET /admin/gosklyuch/status`). */
export interface GosklyuchStatusDto {
  enabled: boolean;
  provider: string;
  readyToSend: boolean;
  maxDocuments: number;
  signer: string;
  diagnostics: GosklyuchDiagnosticsDto;
}

/** Файл резервной копии БД (`GET /admin/backups`). */
export interface BackupDto {
  name: string;
  sizeBytes: number;
  sizePretty: string;
  createdAt: string;
  encrypted: boolean;
}

/** Файл ключа шифрования (`GET /admin/keys`). */
export interface KeyInfoDto {
  name: string;
  description: string;
  path: string;
  exists: boolean;
  sizeBytes: number;
  modifiedAt: string;
  permissions: string;
  owner: string;
  problem?: string;
}

/** Состояние ключей шифрования (`GET /admin/keys`). */
export interface KeysStatusDto {
  keys: KeyInfoDto[];
  lastBackupAt?: string;
  downloadCount: number;
  daysSinceLastBackup?: number;
  piiVersion: number;
  fileVersion: number;
  minPasswordLength: number;
}

/** Состояние TLS-сертификата (`GET /admin/ssl`). */
export interface SslStatusDto {
  hasCertificate: boolean;
  acmeAvailable: boolean;
  acmeActive: boolean;
}

/** Статус документа для интерфейса. */
export type DocumentStatus = 'signed' | 'unsigned' | 'pending';

/** Производная модель строки списка документов. */
export interface DocumentItem {
  id: number;
  name: string;
  folder: string;
  folderId?: number;
  size: string;
  uploadedAt: string;
  status: DocumentStatus;
  type: string;
  signers: string[];
  gosklyuch?: string;
  raw: DocumentDto;
}
