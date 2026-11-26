import { useParams, Link } from 'react-router-dom';
import { Card, Descriptions, Space, Table, Tag, Avatar, Button } from 'antd';
import { ArrowLeftOutlined, MailOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import { useApp } from '../../lib/store';
import { DocStatus } from '../../components/StatusTag';
import type { DocumentItem } from '../../lib/types';

export default function ClientCardPage() {
  const { id } = useParams();
  const { clients, documents } = useApp();
  const client = clients.find((c) => String(c.id) === id);
  if (!client) return <Card>Клиент не найден</Card>;

  return (
    <>
      <Space style={{ marginBottom: 16 }}><Link to="/admin/clients"><ArrowLeftOutlined /> Клиенты</Link></Space>
      <PageHeader title={client.name} subtitle={client.email}
        extra={<><Button icon={<MailOutlined />}>Написать</Button><Button type="primary" icon={<SafetyCertificateOutlined />}>Отправить в Госключ</Button></>} />
      <div className="grid-3" style={{ marginBottom: 16 }}>
        <Card><div className="stat-label">Документов</div><div className="stat-value">{client.docs}</div></Card>
        <Card><div className="stat-label">Подписано</div><div className="stat-value">{client.signed}</div></Card>
        <Card><div className="stat-label">Статус</div><Tag color={client.status === 'active' ? 'success' : 'default'} style={{ marginTop: 8 }}>{client.status === 'active' ? 'Активен' : 'Заблокирован'}</Tag></Card>
      </div>
      <div className="grid-2">
        <Card title="Персональные данные">
          <div style={{ display: 'flex', gap: 14, alignItems: 'center', marginBottom: 16 }}>
            <Avatar size={54} style={{ background: 'linear-gradient(135deg,#4f46e5,#8b5cf6)' }}>{client.name.slice(0, 1)}</Avatar>
            <div><div style={{ fontWeight: 700 }}>{client.name}</div><div style={{ color: 'var(--muted)' }}>Клиент с {client.createdAt}</div></div>
          </div>
          <Descriptions column={1} size="small" items={[
            { key: 'email', label: 'Email', children: client.email },
            { key: 'phone', label: 'Телефон', children: client.phone },
            { key: 'snils', label: 'СНИЛС', children: client.snils },
            { key: 'status', label: 'Статус', children: <Tag color="success">Активен</Tag> },
          ]} />
        </Card>
        <Card title="Последние документы">
          <Table rowKey="id" size="small" pagination={false} dataSource={documents.slice(0, 4)} columns={[
            { title: 'Документ', dataIndex: 'name' },
            { title: 'Статус', dataIndex: 'status', render: (s: DocumentItem['status']) => <DocStatus status={s} /> },
          ]} />
        </Card>
      </div>
    </>
  );
}
