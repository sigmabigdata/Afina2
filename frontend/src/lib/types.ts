export type DocumentStatus = 'signed' | 'unsigned' | 'pending';

export interface DocumentItem {
  id: number;
  name: string;
  folder: string;
  size: string;
  uploadedAt: string;
  status: DocumentStatus;
  type: 'pdf' | 'docx';
  signers: string[];
  gosklyuch?: string;
}

export interface Folder {
  id: number;
  name: string;
  count: number;
  signed: number;
}

export interface Client {
  id: number;
  name: string;
  email: string;
  phone: string;
  snils: string;
  docs: number;
  signed: number;
  status: 'active' | 'blocked';
  createdAt: string;
}

export interface Order {
  id: number;
  client: string;
  document: string;
  status: 'sent' | 'signed' | 'rejected' | 'expired' | 'error';
  externalId: number;
  updatedAt: string;
}

export interface AuditEvent {
  id: number;
  time: string;
  actor: string;
  action: string;
  target: string;
  result: 'success' | 'fail' | 'warn';
}

export interface Crl {
  id: number;
  issuer: string;
  fileName: string;
  validUntil: string;
  status: 'ok' | 'expiring' | 'expired' | 'untrusted';
}

export interface User {
  name: string;
  email: string;
  role: 'client' | 'employee' | 'admin';
}

export interface Template {
  id: number;
  name: string;
  description: string;
  kind: 'document' | 'consent';
  fields: number;
}
