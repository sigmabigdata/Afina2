import {
  createContext, useCallback, useContext, useEffect, useMemo, useState,
  type ReactNode,
} from 'react';
import { api, download } from './api';
import type {
  AuditEventDto, CrlDto, DashboardDto, DocumentDto, DocumentItem, DocumentStatus,
  FolderDto, GosklyuchOrderDto, PageDto, TemplateDto, UserDto,
} from './types';

/** Роль в терминах интерфейса. */
export type UiRole = 'client' | 'employee' | 'admin';

export interface CurrentUser {
  id: number;
  name: string;
  email: string;
  role: UiRole;
  profileComplete: boolean;
  consentSigned: boolean;
  raw: UserDto;
}

export interface OrderRow {
  id: number;
  client: string;
  document: string;
  status: 'sent' | 'signed' | 'rejected' | 'expired' | 'error';
  externalId?: number;
  updatedAt: string;
  raw: GosklyuchOrderDto;
}

export interface ClientRow {
  id: number;
  name: string;
  email: string;
  phone: string;
  snils: string;
  docs: number;
  signed: number;
  status: 'active' | 'blocked';
  createdAt: string;
  raw: UserDto;
}

export interface AuditRow {
  id: number;
  time: string;
  actor: string;
  action: string;
  target: string;
  result: 'success' | 'fail' | 'warn';
  raw: AuditEventDto;
}

export interface CrlRow {
  id: number;
  issuer: string;
  fileName: string;
  validUntil: string;
  status: 'ok' | 'expiring' | 'expired' | 'untrusted';
  raw: CrlDto;
}

export interface TemplateRow {
  id: number;
  name: string;
  description: string;
  kind: 'document' | 'consent';
  fields: number;
  active: boolean;
  raw: TemplateDto;
}

interface AppState {
  user: CurrentUser | null;
  booting: boolean;
  dark: boolean;
  documents: DocumentItem[];
  rawDocuments: DocumentDto[];
  folders: FolderDto[];
  templates: TemplateRow[];
  orders: OrderRow[];
  clients: ClientRow[];
  auditEvents: AuditRow[];
  crls: CrlRow[];
  dashboard: DashboardDto | null;
  isAdmin: boolean;
  toggleTheme: () => void;
  login: (email: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshMe: () => Promise<void>;
  refreshDocuments: () => Promise<void>;
  refreshFolders: () => Promise<void>;
  refreshTemplates: () => Promise<void>;
  refreshOrders: () => Promise<void>;
  refreshClients: (query?: string) => Promise<void>;
  refreshAudit: () => Promise<void>;
  refreshCrls: () => Promise<void>;
  refreshDashboard: () => Promise<void>;
  uploadDocument: (file: File, folderId?: number) => Promise<DocumentDto>;
  deleteDocument: (id: number) => Promise<void>;
  signDocument: (id: number, signatureBase64: string) => Promise<Record<string, unknown>>;
  sendToGosklyuch: (id: number) => Promise<void>;
  generateFromTemplate: (templateId: number, values: Record<string, string>,
    folderId?: number) => Promise<DocumentDto>;
  downloadSignature: (id: number) => Promise<void>;
  downloadDocument: (id: number) => Promise<void>;
}

const AppContext = createContext<AppState | null>(null);

// ─────────────────────────── преобразования ───────────────────────────

const ROLE_MAP: Record<string, UiRole> = {
  ROLE_ADMIN: 'admin',
  ROLE_EMPLOYEE: 'employee',
  ROLE_USER: 'client',
};

export function toCurrentUser(dto: UserDto): CurrentUser {
  return {
    id: dto.id,
    name: dto.fullName || dto.email,
    email: dto.email,
    role: ROLE_MAP[dto.role] ?? 'client',
    profileComplete: dto.profileComplete,
    consentSigned: dto.consentSigned,
    raw: dto,
  };
}

const MONTHS = ['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];

export function formatDateTime(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()} `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function formatDate(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()}`;
}

export function formatShortDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return `${d.getDate()} ${MONTHS[d.getMonth()]}`;
}

export function documentStatus(d: DocumentDto): DocumentStatus {
  if (d.signed) return 'signed';
  if (d.gosklyuchOrder && !['SIGNED', 'REJECTED', 'EXPIRED', 'ERROR']
    .includes(d.gosklyuchOrder.status)) return 'pending';
  return 'unsigned';
}

export function toDocumentItem(d: DocumentDto): DocumentItem {
  return {
    id: d.id,
    name: d.name,
    folder: d.folderName ?? 'Без раздела',
    folderId: d.folderId,
    size: d.sizePretty,
    uploadedAt: formatDateTime(d.uploadedAt),
    status: documentStatus(d),
    type: d.extension || 'file',
    signers: d.signatures.map((s) => s.signerName || s.signerSubject),
    gosklyuch: d.gosklyuchOrder?.status === 'SENT'
      ? 'Ожидает подписания в «Госключе»'
      : d.gosklyuchOrder?.status === 'REJECTED' ? 'Отклонено подписантом' : undefined,
    raw: d,
  };
}

export function toOrderRow(o: GosklyuchOrderDto): OrderRow {
  const status: OrderRow['status'] = o.status === 'SIGNED' ? 'signed'
    : o.status === 'REJECTED' ? 'rejected'
      : o.status === 'EXPIRED' ? 'expired'
        : o.status === 'ERROR' ? 'error' : 'sent';
  return {
    id: o.id,
    client: o.ownerName,
    document: o.documentName,
    status,
    externalId: o.externalOrderId,
    updatedAt: formatDateTime(o.updatedAt ?? o.createdAt),
    raw: o,
  };
}

// ─────────────────────────── провайдер ───────────────────────────

export function AppProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [booting, setBooting] = useState(true);
  const [dark, setDark] = useState<boolean>(() => localStorage.getItem('afina2-theme') === 'dark');
  const [documents, setDocuments] = useState<DocumentItem[]>([]);
  const [rawDocuments, setRawDocuments] = useState<DocumentDto[]>([]);
  const [folders, setFolders] = useState<FolderDto[]>([]);
  const [templates, setTemplates] = useState<TemplateRow[]>([]);
  const [orders, setOrders] = useState<OrderRow[]>([]);
  const [clients, setClients] = useState<ClientRow[]>([]);
  const [auditEvents, setAuditEvents] = useState<AuditRow[]>([]);
  const [crls, setCrls] = useState<CrlRow[]>([]);
  const [dashboard, setDashboard] = useState<DashboardDto | null>(null);

  const isAdmin = user?.role === 'admin';

  // ── тема ──
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  }, [dark]);

  const toggleTheme = useCallback(() => {
    setDark((prev) => {
      localStorage.setItem('afina2-theme', prev ? 'light' : 'dark');
      return !prev;
    });
  }, []);

  // ── загрузчики ──
  const refreshMe = useCallback(async () => {
    const me = await api<UserDto>('/auth/me');
    setUser(toCurrentUser(me));
  }, []);

  const refreshDocuments = useCallback(async () => {
    const page = await api<PageDto<DocumentDto>>('/documents?size=200');
    setRawDocuments(page.items);
    setDocuments(page.items.map(toDocumentItem));
  }, []);

  const refreshFolders = useCallback(async () => {
    setFolders(await api<FolderDto[]>('/folders'));
  }, []);

  const refreshTemplates = useCallback(async () => {
    const list = await api<TemplateDto[]>('/templates');
    setTemplates(list.map((t) => ({
      id: t.id,
      name: t.name,
      description: t.description ?? '',
      kind: t.kind === 'CONSENT' ? 'consent' : 'document',
      fields: t.fieldsCount,
      active: t.active,
      raw: t,
    })));
  }, []);

  const refreshOrders = useCallback(async () => {
    const list = await api<GosklyuchOrderDto[]>('/documents/gosklyuch/orders?limit=100');
    setOrders(list.map(toOrderRow));
  }, []);

  const refreshClients = useCallback(async (query?: string) => {
    const qs = query ? `?q=${encodeURIComponent(query)}&size=200` : '?size=200';
    const page = await api<PageDto<UserDto>>(`/admin/clients${qs}`);
    setClients(page.items.map((u) => ({
      id: u.id,
      name: u.fullName,
      email: u.email,
      phone: u.phone ?? '—',
      snils: u.snils ?? '—',
      docs: u.documentsTotal,
      signed: u.documentsSigned,
      status: u.enabled ? 'active' : 'blocked',
      createdAt: formatDate(u.createdAt),
      raw: u,
    })));
  }, []);

  const refreshAudit = useCallback(async () => {
    const page = await api<PageDto<AuditEventDto>>('/admin/audit?size=200');
    setAuditEvents(page.items.map((e) => {
      const result: AuditRow['result'] = e.result === 'SUCCESS' ? 'success'
        : e.result === 'FAIL' ? 'fail' : 'warn';
      return {
        id: e.id,
        time: formatDateTime(e.eventTime),
        actor: e.actorEmail ?? 'система',
        action: e.eventType,
        target: e.targetInfo ?? e.targetType ?? '—',
        result,
        raw: e,
      };
    }));
  }, []);

  const refreshCrls = useCallback(async () => {
    const list = await api<CrlDto[]>('/admin/crl');
    setCrls(list.map((c, i) => ({
      id: i + 1,
      issuer: c.issuer ?? c.fileName,
      fileName: c.fileName,
      validUntil: formatDate(c.nextUpdate),
      status: c.status,
      raw: c,
    })));
  }, []);

  const refreshDashboard = useCallback(async () => {
    setDashboard(await api<DashboardDto>('/dashboard'));
  }, []);

  // ── де йствия ──
  const login = useCallback(async (email: string) => {
    await api('/auth/login-link', { method: 'POST', body: { email } });
  }, []);

  const logout = useCallback(async () => {
    await api('/auth/logout', { method: 'POST' }).catch(() => undefined);
    setUser(null);
    setDocuments([]);
    setRawDocuments([]);
    setFolders([]);
    setTemplates([]);
    setOrders([]);
    setClients([]);
    setAuditEvents([]);
    setCrls([]);
    setDashboard(null);
    window.location.assign('/login');
  }, []);

  const uploadDocument = useCallback(async (file: File, folderId?: number) => {
    const form = new FormData();
    form.append('file', file);
    if (folderId) form.append('folderId', String(folderId));
    const dto = await api<DocumentDto>('/documents', { method: 'POST', form });
    await refreshDocuments();
    await refreshFolders();
    return dto;
  }, [refreshDocuments, refreshFolders]);

  const deleteDocument = useCallback(async (id: number) => {
    await api(`/documents/${id}`, { method: 'DELETE' });
    await refreshDocuments();
    await refreshFolders();
  }, [refreshDocuments, refreshFolders]);

  const signDocument = useCallback(async (id: number, signatureBase64: string) => {
    const result = await api<Record<string, unknown>>(`/documents/${id}/sign`, {
      method: 'POST',
      body: { documentId: id, signatureBase64 },
    });
    await refreshDocuments();
    await refreshDashboard();
    return result;
  }, [refreshDocuments, refreshDashboard]);

  const sendToGosklyuch = useCallback(async (id: number) => {
    await api(`/documents/${id}/gosklyuch`, { method: 'POST' });
    await refreshDocuments();
    await refreshOrders();
  }, [refreshDocuments, refreshOrders]);

  const generateFromTemplate = useCallback(async (
    templateId: number, values: Record<string, string>, folderId?: number,
  ) => {
    const dto = await api<DocumentDto>(`/templates/${templateId}/generate`, {
      method: 'POST',
      body: { folderId, values },
    });
    await refreshDocuments();
    await refreshFolders();
    return dto;
  }, [refreshDocuments, refreshFolders]);

  const downloadSignature = useCallback((id: number) =>
    download(`/documents/${id}/signature`, 'signature.sig'), []);

  const downloadDocument = useCallback((id: number) =>
    download(`/documents/${id}/download`, 'document'), []);

  // ── инициализация ──
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const me = await api<UserDto>('/auth/me');
        if (cancelled) return;
        setUser(toCurrentUser(me));
        await Promise.allSettled([
          refreshDocuments(),
          refreshFolders(),
          refreshTemplates(),
          refreshOrders(),
          refreshDashboard(),
        ]);
        if (ROLE_MAP[me.role] === 'admin') {
          await Promise.allSettled([refreshClients(), refreshAudit(), refreshCrls()]);
        }
      } catch {
        if (!cancelled) setUser(null);
      } finally {
        if (!cancelled) setBooting(false);
      }
    })();
    return () => { cancelled = true; };
  }, [refreshDocuments, refreshFolders, refreshTemplates, refreshOrders, refreshDashboard,
    refreshClients, refreshAudit, refreshCrls]);

  const value = useMemo<AppState>(() => ({
    user, booting, dark, documents, rawDocuments, folders, templates, orders,
    clients, auditEvents, crls, dashboard, isAdmin, toggleTheme,
    login, logout, refreshMe, refreshDocuments, refreshFolders, refreshTemplates,
    refreshOrders, refreshClients, refreshAudit, refreshCrls, refreshDashboard,
    uploadDocument, deleteDocument, signDocument, sendToGosklyuch,
    generateFromTemplate, downloadSignature, downloadDocument,
  }), [user, booting, dark, documents, rawDocuments, folders, templates, orders,
    clients, auditEvents, crls, dashboard, isAdmin, toggleTheme, login, logout,
    refreshMe, refreshDocuments, refreshFolders, refreshTemplates, refreshOrders,
    refreshClients, refreshAudit, refreshCrls, refreshDashboard, uploadDocument,
    deleteDocument, signDocument, sendToGosklyuch, generateFromTemplate,
    downloadSignature, downloadDocument]);

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppState {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('AppProvider is missing');
  return ctx;
}
