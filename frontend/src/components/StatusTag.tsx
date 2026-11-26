import { Tag } from 'antd';
import { CheckCircleFilled, ClockCircleOutlined, CloseCircleFilled, ExclamationCircleFilled } from '@ant-design/icons';
import type { DocumentStatus } from '../lib/types';

const docMap: Record<DocumentStatus, { color: string; label: string; icon: React.ReactNode }> = {
  signed: { color: 'success', label: 'Подписан', icon: <CheckCircleFilled /> },
  unsigned: { color: 'warning', label: 'Без подписи', icon: <ClockCircleOutlined /> },
  pending: { color: 'processing', label: 'В Госключе', icon: <ClockCircleOutlined /> },
};

export function DocStatus({ status }: { status: DocumentStatus }) {
  const cfg = docMap[status];
  return <Tag color={cfg.color} icon={cfg.icon} style={{ borderRadius: 999, paddingInline: 10 }}>{cfg.label}</Tag>;
}

const orderMap: Record<string, { color: string; label: string; icon: React.ReactNode }> = {
  sent: { color: 'processing', label: 'Ожидает подписания', icon: <ClockCircleOutlined /> },
  signed: { color: 'success', label: 'Подписано', icon: <CheckCircleFilled /> },
  rejected: { color: 'error', label: 'Отказ', icon: <CloseCircleFilled /> },
  expired: { color: 'default', label: 'Истёк срок', icon: <ClockCircleOutlined /> },
  error: { color: 'error', label: 'Ошибка', icon: <ExclamationCircleFilled /> },
};

export function OrderStatus({ status }: { status: string }) {
  const cfg = orderMap[status] ?? orderMap.error;
  return <Tag color={cfg.color} icon={cfg.icon} style={{ borderRadius: 999, paddingInline: 10 }}>{cfg.label}</Tag>;
}
