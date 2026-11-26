import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import { auditEvents, clients, crls, documents as seedDocuments, folders, orders, templates, user } from './mock';
import type { AuditEvent, Client, Crl, DocumentItem, Folder, Order, Template, User } from './types';

interface AppState {
  user: User;
  documents: DocumentItem[];
  folders: Folder[];
  clients: Client[];
  orders: Order[];
  auditEvents: AuditEvent[];
  crls: Crl[];
  templates: Template[];
  dark: boolean;
  toggleTheme: () => void;
  addDocument: (doc: DocumentItem) => void;
  signDocument: (id: number, signer: string) => void;
  sendToGosklyuch: (id: number) => void;
  deleteDocument: (id: number) => void;
}

const AppContext = createContext<AppState | null>(null);

export function AppProvider({ children }: { children: ReactNode }) {
  const [documents, setDocuments] = useState<DocumentItem[]>(seedDocuments);
  const [dark, setDark] = useState<boolean>(() => localStorage.getItem('afina2-theme') === 'dark');

  const value = useMemo<AppState>(() => ({
    user,
    documents,
    folders: folders.map((f) => {
      const items = documents.filter((d) => d.folder === f.name);
      return { ...f, count: items.length, signed: items.filter((d) => d.status === 'signed').length };
    }),
    clients,
    orders,
    auditEvents,
    crls,
    templates,
    dark,
    toggleTheme: () => {
      setDark((prev) => {
        localStorage.setItem('afina2-theme', prev ? 'light' : 'dark');
        return !prev;
      });
    },
    addDocument: (doc) => setDocuments((prev) => [doc, ...prev]),
    signDocument: (id, signer) => setDocuments((prev) => prev.map((d) => d.id === id
      ? { ...d, status: 'signed', signers: Array.from(new Set([...d.signers, signer])), gosklyuch: undefined }
      : d)),
    sendToGosklyuch: (id) => setDocuments((prev) => prev.map((d) => d.id === id
      ? { ...d, status: 'pending', gosklyuch: 'Ожидает подписания в «Госключе»' }
      : d)),
    deleteDocument: (id) => setDocuments((prev) => prev.filter((d) => d.id !== id)),
  }), [documents, dark]);

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppState {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('AppProvider is missing');
  return ctx;
}
