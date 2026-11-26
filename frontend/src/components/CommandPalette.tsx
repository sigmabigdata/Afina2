import { useMemo, useState } from 'react';
import { Empty, Input, Modal } from 'antd';
import { FileTextOutlined, SearchOutlined, TeamOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useApp } from '../lib/store';

export default function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { documents, clients } = useApp();
  const navigate = useNavigate();
  const [q, setQ] = useState('');

  const items = useMemo(() => {
    const query = q.trim().toLowerCase();
    const pages = [
      { key: '/dashboard', label: 'Обзор', icon: <ThunderboltOutlined />, sub: 'Дашборд' },
      { key: '/documents', label: 'Документы', icon: <FileTextOutlined />, sub: 'Все документы' },
      { key: '/gosklyuch', label: 'Госключ', icon: <ThunderboltOutlined />, sub: 'Заявки' },
      { key: '/admin/clients', label: 'Клиенты', icon: <TeamOutlined />, sub: 'Администрирование' },
    ];
    const docs = documents.map((d) => ({
      key: `/documents/${d.id}`, label: d.name, icon: <FileTextOutlined />, sub: `Документ · ${d.folder}`,
    }));
    const clientItems = clients.map((c) => ({
      key: `/admin/clients/${c.id}`, label: c.name, icon: <TeamOutlined />, sub: 'Клиент',
    }));
    return [...pages, ...docs, ...clientItems]
      .filter((i) => !query || i.label.toLowerCase().includes(query) || i.sub.toLowerCase().includes(query))
      .slice(0, 12);
  }, [q, documents, clients]);

  const go = (key: string) => {
    navigate(key);
    onClose();
    setQ('');
  };

  return (
    <Modal open={open} onCancel={onClose} footer={null} width={560} style={{ top: 90 }} destroyOnClose>
      <Input
        autoFocus
        size="large"
        prefix={<SearchOutlined />}
        placeholder="Поиск документов, клиентов, разделов…"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        onPressEnter={() => items[0] && go(items[0].key)}
        style={{ marginBottom: 12 }}
      />
      <div className="cmdk-list">
        {items.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Ничего не найдено" /> : items.map((item, idx) => (
          <div
            key={item.key}
            className={`cmdk-item ${idx === 0 ? 'active' : ''}`}
            onClick={() => go(item.key)}
            role="button"
            tabIndex={0}
            onKeyDown={(e) => e.key === 'Enter' && go(item.key)}
          >
            <div className="cmdk-icon">{item.icon}</div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{item.label}</div>
              <div style={{ color: 'var(--muted)', fontSize: 12 }}>{item.sub}</div>
            </div>
          </div>
        ))}
      </div>
    </Modal>
  );
}
