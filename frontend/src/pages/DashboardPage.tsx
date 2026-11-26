import { Button, Card, Col, Row, Table, Tag } from 'antd';
import {
  ArrowRightOutlined, CheckCircleOutlined, FileAddOutlined, FileTextOutlined,
  RiseOutlined, SafetyCertificateOutlined, ThunderboltOutlined, UploadOutlined,
} from '@ant-design/icons';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Link, useNavigate } from 'react-router-dom';
import { useState } from 'react';
import StatCard from '../components/StatCard';
import PageHeader from '../components/PageHeader';
import { OrderStatus } from '../components/StatusTag';
import { useApp } from '../lib/store';
import { chartData } from '../lib/mock';
import UploadModal from '../components/UploadModal';
import type { DocumentItem } from '../lib/types';

export default function DashboardPage() {
  const { documents, folders, orders, user } = useApp();
  const navigate = useNavigate();
  const [uploadOpen, setUploadOpen] = useState(false);
  const signed = documents.filter((d) => d.status === 'signed').length;

  const columns = [
    {
      title: 'Документ',
      dataIndex: 'name',
      render: (name: string, row: DocumentItem) => (
        <Link to={`/documents/${row.id}`} className="doc-link">{name}</Link>
      ),
    },
    { title: 'Раздел', dataIndex: 'folder', responsive: ['md' as const] },
    { title: 'Загружен', dataIndex: 'uploadedAt', responsive: ['lg' as const] },
    { title: 'Статус', dataIndex: 'status', render: (s: string) => s === 'signed'
      ? <Tag color="success" style={{ borderRadius: 999 }}>Подписан</Tag>
      : s === 'pending' ? <Tag color="processing" style={{ borderRadius: 999 }}>В Госключе</Tag>
      : <Tag color="warning" style={{ borderRadius: 999 }}>Без подписи</Tag> },
  ];

  return (
    <>
      <PageHeader
        title={`Добрый день, ${user.name.split(' ')[0]}`}
        subtitle="Вот что происходит с документами сегодня"
        extra={<>
          <Button icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>Загрузить</Button>
          <Button type="primary" icon={<FileAddOutlined />} onClick={() => navigate('/templates')}>Из шаблона</Button>
        </>}
      />

      <div className="hero">
        <Tag color="rgba(255,255,255,.18)" style={{ color: '#fff', border: 'none', borderRadius: 999 }}>Afina2 · preview</Tag>
        <h2>Подписание без трения.<br />Документы, подпись и Госключ — в одном окне.</h2>
        <p>КриптоПро CAdES, проверка цепочки и CRL, прозрачный журнал действий. Всё, что нужно банку, — в современном интерфейсе.</p>
        <div className="hero-actions">
          <Button size="large" icon={<ArrowRightOutlined />} onClick={() => navigate('/documents')}
            style={{ background: '#fff', color: '#4f46e5', border: 'none', fontWeight: 700 }}>
            Перейти к документам
          </Button>
          <Button size="large" ghost icon={<ThunderboltOutlined />} onClick={() => navigate('/gosklyuch')}>
            Госключ
          </Button>
        </div>
      </div>

      <div className="stat-grid" style={{ marginTop: 18 }}>
        <StatCard icon={<FileTextOutlined />} value={documents.length} label="Документов всего" trend="+12% за неделю" />
        <StatCard icon={<CheckCircleOutlined />} value={signed} label="Подписано" glow="rgba(16,185,129,.16)" trend={`${Math.round(signed / Math.max(documents.length, 1) * 100)}% от всех`} />
        <StatCard icon={<SafetyCertificateOutlined />} value={orders.filter((o) => o.status === 'sent').length} label="Заявок в Госключе" glow="rgba(139,92,246,.16)" />
        <StatCard icon={<RiseOutlined />} value="2,4 ч" label="Среднее время подписания" glow="rgba(245,158,11,.16)" trend="−18% к прошлому месяцу" />
      </div>

      <Row gutter={[18, 18]} style={{ marginTop: 18 }}>
        <Col xs={24} xl={15}>
          <Card title="Активность подписания" extra={<Tag bordered={false}>7 дней</Tag>} styles={{ body: { paddingTop: 8 } }}>
            <div style={{ height: 260 }}>
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={chartData} margin={{ top: 10, right: 8, left: -18, bottom: 0 }}>
                  <defs>
                    <linearGradient id="signed" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#4f46e5" stopOpacity={0.35} />
                      <stop offset="95%" stopColor="#4f46e5" stopOpacity={0} />
                    </linearGradient>
                    <linearGradient id="uploaded" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#8b5cf6" stopOpacity={0.22} />
                      <stop offset="95%" stopColor="#8b5cf6" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="4 6" stroke="var(--border)" vertical={false} />
                  <XAxis dataKey="day" tickLine={false} axisLine={false} tick={{ fill: 'var(--muted)', fontSize: 12 }} />
                  <YAxis tickLine={false} axisLine={false} tick={{ fill: 'var(--muted)', fontSize: 12 }} />
                  <Tooltip contentStyle={{ borderRadius: 12, border: '1px solid var(--border)', background: 'var(--surface)' }} />
                  <Area type="monotone" dataKey="uploaded" name="Загружено" stroke="#8b5cf6" strokeWidth={2} fill="url(#uploaded)" />
                  <Area type="monotone" dataKey="signed" name="Подписано" stroke="#4f46e5" strokeWidth={2.5} fill="url(#signed)" />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </Card>
        </Col>
        <Col xs={24} xl={9}>
          <Card title="Госключ" extra={<Link to="/gosklyuch">Все заявки <ArrowRightOutlined /></Link>}>
            {orders.slice(0, 3).map((o) => (
              <div key={o.id} style={{ display: 'flex', justifyContent: 'space-between', gap: 12, padding: '12px 0', borderBottom: '1px solid var(--border)' }}>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{o.document}</div>
                  <div style={{ color: 'var(--muted)', fontSize: 12 }}>{o.client} · {o.updatedAt}</div>
                </div>
                <OrderStatus status={o.status} />
              </div>
            ))}
            <Button type="dashed" block style={{ marginTop: 14 }} icon={<ThunderboltOutlined />}
              onClick={() => navigate('/gosklyuch')}>Отправить пакет документов</Button>
          </Card>
        </Col>
      </Row>

      <div className="section-title">
        <h3>Разделы</h3>
        <Link to="/documents">Все документы <ArrowRightOutlined /></Link>
      </div>
      <div className="folder-grid">
        {folders.slice(0, 4).map((f) => (
          <div key={f.id} className="folder-card" onClick={() => navigate('/documents')} role="button" tabIndex={0}
            onKeyDown={(e) => e.key === 'Enter' && navigate('/documents')}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <div className="folder-icon">📁</div>
              <Tag color={f.signed === f.count ? 'success' : 'warning'} style={{ borderRadius: 999 }}>{f.signed}/{f.count}</Tag>
            </div>
            <div style={{ fontWeight: 700, marginTop: 12 }}>{f.name}</div>
            <div style={{ color: 'var(--muted)', fontSize: 12 }}>{f.count} документов</div>
          </div>
        ))}
      </div>

      <div className="section-title">
        <h3>Последние документы</h3>
        <Link to="/documents">Открыть все <ArrowRightOutlined /></Link>
      </div>
      <Card styles={{ body: { padding: 0 } }}>
        <Table rowKey="id" size="middle" pagination={false} dataSource={documents.slice(0, 5)} columns={columns} />
      </Card>

      <UploadModal open={uploadOpen} onClose={() => setUploadOpen(false)} />
    </>
  );
}
