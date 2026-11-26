import type { AuditEvent, Client, Crl, DocumentItem, Folder, Order, Template, User } from './types';

export const user: User = {
  name: 'Анна Смирнова',
  email: 'a.smirnova@nbdbank.ru',
  role: 'admin',
};

export const folders: Folder[] = [
  { id: 1, name: 'Договоры', count: 6, signed: 4 },
  { id: 2, name: 'Акты', count: 4, signed: 2 },
  { id: 3, name: 'Согласия', count: 3, signed: 3 },
  { id: 4, name: 'Учредительные', count: 2, signed: 0 },
];

export const documents: DocumentItem[] = [
  {
    id: 1,
    name: 'Договор поставки № 148-2026.pdf',
    folder: 'Договоры',
    size: '2,4 МБ',
    uploadedAt: '25.11.2026 16:42',
    status: 'signed',
    type: 'pdf',
    signers: ['Иванов И. И.'],
  },
  {
    id: 2,
    name: 'Акт сверки за ноябрь.pdf',
    folder: 'Акты',
    size: '840 КБ',
    uploadedAt: '25.11.2026 15:10',
    status: 'unsigned',
    type: 'pdf',
    signers: [],
  },
  {
    id: 3,
    name: 'Согласие на обработку ПДн.pdf',
    folder: 'Согласия',
    size: '620 КБ',
    uploadedAt: '24.11.2026 12:05',
    status: 'signed',
    type: 'pdf',
    signers: ['Смирнова А. В.'],
  },
  {
    id: 4,
    name: 'Дополнительное соглашение № 3.pdf',
    folder: 'Договоры',
    size: '1,1 МБ',
    uploadedAt: '23.11.2026 18:30',
    status: 'pending',
    type: 'pdf',
    signers: [],
    gosklyuch: 'Ожидает подписания',
  },
  {
    id: 5,
    name: 'Устав организации.docx',
    folder: 'Учредительные',
    size: '3,7 МБ',
    uploadedAt: '22.11.2026 09:15',
    status: 'unsigned',
    type: 'docx',
    signers: [],
  },
  {
    id: 6,
    name: 'Договор аренды № 77.pdf',
    folder: 'Договоры',
    size: '5,2 МБ',
    uploadedAt: '21.11.2026 14:20',
    status: 'signed',
    type: 'pdf',
    signers: ['Петров П. П.', 'Иванов И. И.'],
  },
];

export const clients: Client[] = [
  {
    id: 1,
    name: 'ООО «Вектор»',
    email: 'client@vector.ru',
    phone: '+7 (900) 123-45-67',
    snils: '112-233-445 95',
    docs: 12,
    signed: 9,
    status: 'active',
    createdAt: '12.09.2026',
  },
  {
    id: 2,
    name: 'ИП Орлов А. С.',
    email: 'orlov@example.ru',
    phone: '+7 (921) 555-12-12',
    snils: '—',
    docs: 4,
    signed: 2,
    status: 'active',
    createdAt: '03.10.2026',
  },
  {
    id: 3,
    name: 'АО «Северный мост»',
    email: 'office@northbridge.ru',
    phone: '+7 (812) 340-22-10',
    snils: '—',
    docs: 7,
    signed: 7,
    status: 'active',
    createdAt: '18.08.2026',
  },
  {
    id: 4,
    name: 'ООО «Гранит»',
    email: 'info@granit.ru',
    phone: '+7 (495) 777-01-02',
    snils: '—',
    docs: 2,
    signed: 0,
    status: 'blocked',
    createdAt: '21.11.2026',
  },
];

export const orders: Order[] = [
  {
    id: 1,
    client: 'ООО «Вектор»',
    document: 'Дополнительное соглашение № 3.pdf',
    status: 'sent',
    externalId: 10000531,
    updatedAt: '25.11.2026 16:55',
  },
  {
    id: 2,
    client: 'АО «Северный мост»',
    document: 'Договор аренды № 77.pdf',
    status: 'signed',
    externalId: 10000498,
    updatedAt: '24.11.2026 11:20',
  },
  {
    id: 3,
    client: 'ИП Орлов А. С.',
    document: 'Акт сверки.pdf',
    status: 'expired',
    externalId: 10000471,
    updatedAt: '22.11.2026 09:40',
  },
];

export const auditEvents: AuditEvent[] = [
  { id: 1, time: '25.11.2026 17:02', actor: 'a.smirnova', action: 'SIGN_SUCCESS', target: 'Договор поставки № 148-2026.pdf', result: 'success' },
  { id: 2, time: '25.11.2026 16:42', actor: 'client@vector.ru', action: 'DOC_UPLOAD', target: 'Акт сверки за ноябрь.pdf', result: 'success' },
  { id: 3, time: '25.11.2026 15:10', actor: 'a.smirnova', action: 'CRL_DOWNLOAD', target: 'СКБ Контур', result: 'warn' },
  { id: 4, time: '25.11.2026 14:05', actor: 'system', action: 'CRL_CHECK', target: 'Минцифры России', result: 'success' },
  { id: 5, time: '24.11.2026 12:20', actor: 'admin@nbdbank.ru', action: 'SETTINGS_UPDATE', target: 'revocation.mode', result: 'success' },
  { id: 6, time: '24.11.2026 09:00', actor: 'orlov@example.ru', action: 'LOGIN_FAIL', target: 'magic-link', result: 'fail' },
];

export const crls: Crl[] = [
  { id: 1, issuer: 'АО «ПФ «СКБ Контур»', fileName: 'auto-522e1b5b.crl', validUntil: '27.11.2026 20:10', status: 'ok' },
  { id: 2, issuer: 'Минцифры России', fileName: 'auto-f01e3b8f.crl', validUntil: '10.12.2026 12:02', status: 'ok' },
  { id: 3, issuer: 'АО «ПФ «СКБ Контур» (2026)', fileName: 'kontur-q-2026.crl', validUntil: '28.09.2026 02:10', status: 'expired' },
];

export const templates: Template[] = [
  { id: 1, name: 'Согласие на обработку ПДн', description: 'Формируется автоматически при первом входе клиента', kind: 'consent', fields: 6 },
  { id: 2, name: 'Договор поставки', description: 'Базовый шаблон с реквизитами сторон', kind: 'document', fields: 12 },
  { id: 3, name: 'Акт сверки', description: 'Период, сумма, подписанты', kind: 'document', fields: 5 },
];

export const chartData = [
  { day: '20.11', signed: 4, uploaded: 6 },
  { day: '21.11', signed: 7, uploaded: 9 },
  { day: '22.11', signed: 5, uploaded: 7 },
  { day: '23.11', signed: 9, uploaded: 11 },
  { day: '24.11', signed: 12, uploaded: 14 },
  { day: '25.11', signed: 10, uploaded: 13 },
  { day: '26.11', signed: 15, uploaded: 17 },
];
