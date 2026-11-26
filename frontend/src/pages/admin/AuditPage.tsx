import { useState } from 'react';
import { Card, Input, Select, Space, Table, Tag } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import { useApp } from '../../lib/store';
import type { AuditEvent } from '../../lib/types';

export default function AuditPage() {
  const { auditEvents } = useApp();
  const [q, setQ] = useState('');
  const [result, setResult] = useState('all');
  const filtered = auditEvents.filter((e) => {
    if (result !== 'all' && e.result !== result) return false;
    if (q && !`${e.actor} ${e.action} ${e.target}`.toLowerCase().includes(q.toLowerCase())) return false;
    return true;
  });

  return (
    <>
      <PageHeader title="Аудит" subtitle="Все действия пользователей и системы" />
      <Card>
        <Space wrap style={{ marginBottom: 16 }}>
          <Input allowClear prefix={<SearchOutlined />} placeholder="Кто, действие, объект" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: 300 }} />
          <Select value={result} onChange={setResult} style={{ width: 160 }} options={[
            { value: 'all', label: 'Все результаты' },
            { value: 'success', label: 'Успех' },
            { value: 'warn', label: 'Предупреждение' },
            { value: 'fail', label: 'Ошибка' },
          ]} />
        </Space>
        <Table rowKey="id" dataSource={filtered} pagination={{ pageSize: 10 }} columns={[
          { title: 'Время', dataIndex: 'time', width: 170 },
          { title: 'Пользователь', dataIndex: 'actor' },
          { title: 'Действие', dataIndex: 'action', render: (a: string) => <code style={{ fontSize: 12 }}>{a}</code> },
          { title: 'Объект', dataIndex: 'target', responsive: ['md' as const] },
          { title: 'Результат', dataIndex: 'result', render: (r: AuditEvent['result']) => (
            <Tag color={r === 'success' ? 'success' : r === 'warn' ? 'warning' : 'error'} style={{ borderRadius: 999 }}>
              {r === 'success' ? 'Успех' : r === 'warn' ? 'Внимание' : 'Ошибка'}
            </Tag>
          ) },
        ]} />
      </Card>
    </>
  );
}
