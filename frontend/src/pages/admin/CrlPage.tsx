import { App, Button, Card, Space, Table, Tag } from 'antd';
import { ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import { useApp } from '../../lib/store';
import type { CrlRow } from '../../lib/store';

export default function CrlPage() {
  const { crls } = useApp();
  const { message } = App.useApp();
  const color = (s: CrlRow['status']) => s === 'ok' ? 'success' : s === 'expiring' ? 'warning' : s === 'expired' ? 'error' : 'default';
  const label = (s: CrlRow['status']) => ({ ok: 'OK', expiring: 'Истекает', expired: 'Истёк', untrusted: 'Не проверен' } as const)[s];

  return (
    <>
      <PageHeader title="CRL и доверие" subtitle="Списки отзыва и проверка сертификатов"
        extra={<Button icon={<ReloadOutlined />} onClick={() => message.success('CRL обновлены (демо)')}>Обновить CRL</Button>} />
      <div className="grid-3" style={{ marginBottom: 16 }}>
        <Card><div className="stat-label">CRL в хранилище</div><div className="stat-value">{crls.length}</div></Card>
        <Card><div className="stat-label">Проверены</div><div className="stat-value">{crls.filter((c) => c.status === 'ok').length}</div></Card>
        <Card><div className="stat-label">Требуют внимания</div><div className="stat-value">{crls.filter((c) => c.status !== 'ok').length}</div></Card>
      </div>
      <Card title={<Space><SafetyCertificateOutlined /> Состояние CRL</Space>}>
        <Table rowKey="id" dataSource={crls} pagination={false} columns={[
          { title: 'Издатель', dataIndex: 'issuer' },
          { title: 'Файл', dataIndex: 'fileName', render: (f: string) => <code style={{ fontSize: 12 }}>{f}</code> },
          { title: 'Действует до', dataIndex: 'validUntil', responsive: ['md' as const] },
          { title: 'Статус', dataIndex: 'status', render: (s: CrlRow['status']) => <Tag color={color(s)} style={{ borderRadius: 999 }}>{label(s)}</Tag> },
          { title: '', key: 'a', render: () => <Button type="link">Детали</Button> },
        ]} />
      </Card>
    </>
  );
}
