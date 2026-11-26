import { useState } from 'react';
import { Button, Card, Input, Space, Table, Tag } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import PageHeader from '../../components/PageHeader';
import { useApp } from '../../lib/store';
import type { ClientRow } from '../../lib/store';

export default function ClientsPage() {
  const { clients } = useApp();
  const [q, setQ] = useState('');
  const navigate = useNavigate();
  const filtered = clients.filter((c) => !q || c.name.toLowerCase().includes(q.toLowerCase()) || c.email.toLowerCase().includes(q.toLowerCase()));

  const columns = [
    { title: 'Клиент', dataIndex: 'name', render: (name: string, row: ClientRow) => (
      <div><Link to={`/admin/clients/${row.id}`} style={{ fontWeight: 700 }}>{name}</Link><div style={{ color: 'var(--muted)', fontSize: 12 }}>{row.email}</div></div>
    ) },
    { title: 'Телефон', dataIndex: 'phone', responsive: ['lg' as const] },
    { title: 'Документы', dataIndex: 'docs', render: (n: number, row: ClientRow) => <span>{n} · <span style={{ color: '#10b981' }}>{row.signed} подп.</span></span> },
    { title: 'Статус', dataIndex: 'status', render: (s: string) => <Tag color={s === 'active' ? 'success' : 'default'} style={{ borderRadius: 999 }}>{s === 'active' ? 'Активен' : 'Заблокирован'}</Tag> },
    { title: '', key: 'a', render: (_: unknown, row: ClientRow) => <Button type="link" onClick={() => navigate(`/admin/clients/${row.id}`)}>Карточка</Button> },
  ];

  return (
    <>
      <PageHeader title="Клиенты" subtitle={`${clients.length} организаций и ИП`} extra={<Button type="primary" icon={<PlusOutlined />}>Добавить клиента</Button>} />
      <Card>
        <Space style={{ marginBottom: 16 }}>
          <Input allowClear prefix={<SearchOutlined />} placeholder="ФИО, email, телефон" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: 300 }} />
        </Space>
        <Table rowKey="id" dataSource={filtered} columns={columns} pagination={{ pageSize: 10 }} />
      </Card>
    </>
  );
}
