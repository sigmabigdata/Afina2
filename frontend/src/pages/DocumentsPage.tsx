import { useMemo, useState } from 'react';
import { Button, Card, Empty, Input, Segmented, Select, Space, Table, Tag } from 'antd';
import { AppstoreOutlined, FileTextOutlined, FilterOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import PageHeader from '../components/PageHeader';
import { DocStatus } from '../components/StatusTag';
import UploadModal from '../components/UploadModal';
import { useApp } from '../lib/store';
import type { DocumentItem } from '../lib/types';

export default function DocumentsPage() {
  const { documents, folders } = useApp();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [folder, setFolder] = useState<string>('all');
  const [status, setStatus] = useState<string>('all');
  const [uploadOpen, setUploadOpen] = useState(false);

  const filtered = useMemo(() => documents.filter((d) => {
    if (folder !== 'all' && d.folder !== folder) return false;
    if (status !== 'all' && d.status !== status) return false;
    if (query && !d.name.toLowerCase().includes(query.toLowerCase())) return false;
    return true;
  }), [documents, folder, status, query]);

  const columns = [
    {
      title: 'Документ', dataIndex: 'name',
      render: (name: string, row: DocumentItem) => (
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <div style={{ width: 38, height: 38, borderRadius: 11, display: 'grid', placeItems: 'center', background: 'rgba(79,70,229,.1)', color: '#4f46e5' }}>
            <FileTextOutlined />
          </div>
          <div style={{ minWidth: 0 }}>
            <Link to={`/documents/${row.id}`} className="doc-link">{name}</Link>
            <div style={{ color: 'var(--muted)', fontSize: 12 }}>{row.size} · {row.folder}</div>
          </div>
        </div>
      ),
    },
    { title: 'Загружен', dataIndex: 'uploadedAt', responsive: ['lg' as const] },
    { title: 'Подписи', dataIndex: 'signers', responsive: ['xl' as const],
      render: (signers: string[]) => signers.length ? <Tag bordered={false}>{signers.length} подп.</Tag> : <span style={{ color: 'var(--muted)' }}>—</span> },
    { title: 'Статус', dataIndex: 'status', render: (s: DocumentItem['status'], row: DocumentItem) => (
      <Space direction="vertical" size={4}>
        <DocStatus status={s} />
        {row.gosklyuch && <span style={{ color: 'var(--muted)', fontSize: 11 }}>{row.gosklyuch}</span>}
      </Space>
    ) },
    {
      title: '', key: 'actions', width: 90,
      render: (_: unknown, row: DocumentItem) => <Button type="link" onClick={() => navigate(`/documents/${row.id}`)}>Открыть</Button>,
    },
  ];

  return (
    <>
      <PageHeader
        title="Документы"
        subtitle={`${documents.length} документов в ${folders.length} разделах`}
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setUploadOpen(true)}>Загрузить</Button>}
      />

      <Card styles={{ body: { paddingBottom: 8 } }}>
        <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
          <Space wrap>
            <Input
              allowClear prefix={<SearchOutlined />} placeholder="Поиск по названию"
              value={query} onChange={(e) => setQuery(e.target.value)} style={{ width: 260 }}
            />
            <Select
              value={folder} onChange={setFolder} style={{ width: 190 }}
              options={[{ value: 'all', label: 'Все разделы' }, ...folders.map((f) => ({ value: f.name, label: f.name }))]}
              suffixIcon={<AppstoreOutlined />}
            />
            <Segmented
              value={status} onChange={(v) => setStatus(String(v))}
              options={[
                { value: 'all', label: 'Все' },
                { value: 'unsigned', label: 'Без подписи' },
                { value: 'signed', label: 'Подписаны' },
                { value: 'pending', label: 'Госключ' },
              ]}
            />
          </Space>
          <Tag icon={<FilterOutlined />} bordered={false}>{filtered.length} найдено</Tag>
        </Space>
      </Card>

      <Card style={{ marginTop: 16 }} styles={{ body: { padding: 0 } }}>
        {filtered.length === 0 ? <Empty style={{ padding: 48 }} description="Ничего не найдено" /> : (
          <Table
            rowKey="id" dataSource={filtered} columns={columns}
            pagination={{ pageSize: 10, showSizeChanger: false }}
            onRow={(row) => ({ onClick: () => navigate(`/documents/${row.id}`), style: { cursor: 'pointer' } })}
          />
        )}
      </Card>

      <UploadModal open={uploadOpen} onClose={() => setUploadOpen(false)} />
    </>
  );
}
