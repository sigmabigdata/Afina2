import { Card, Progress, Space, Tag } from 'antd';
import { CheckCircleFilled, DatabaseOutlined, HddOutlined, MailOutlined, SafetyCertificateOutlined, ThunderboltOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';

const checks = [
  { name: 'База данных', status: 'ok', detail: 'PostgreSQL 15 · 4 мс', icon: <DatabaseOutlined /> },
  { name: 'SMTP', status: 'ok', detail: 'mail.afinasystems.ru · 12 мс', icon: <MailOutlined /> },
  { name: 'КриптоПро JCSP', status: 'ok', detail: '5.0.49800 · лицензия активна', icon: <SafetyCertificateOutlined /> },
  { name: 'CRL', status: 'warn', detail: '1 CRL истекает через 3 дня', icon: <ThunderboltOutlined /> },
  { name: 'Диск', status: 'ok', detail: 'занято 22%', icon: <HddOutlined /> },
];

export default function MonitoringPage() {
  return (
    <>
      <PageHeader title="Мониторинг" subtitle="Состояние компонентов и сервисов" />
      <div className="grid-3" style={{ marginBottom: 16 }}>
        <Card><div className="stat-label">Компоненты</div><div className="stat-value">{checks.length}</div></Card>
        <Card><div className="stat-label">В норме</div><div className="stat-value" style={{ color: '#10b981' }}>{checks.filter((c) => c.status === 'ok').length}</div></Card>
        <Card><div className="stat-label">Требуют внимания</div><div className="stat-value" style={{ color: '#f59e0b' }}>{checks.filter((c) => c.status !== 'ok').length}</div></Card>
      </div>
      <div className="grid-2">
        <Card title="Проверки">
          {checks.map((c) => (
            <div key={c.name} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 0', borderBottom: '1px solid var(--border)' }}>
              <div style={{ width: 38, height: 38, borderRadius: 11, background: 'var(--surface-2)', display: 'grid', placeItems: 'center' }}>{c.icon}</div>
              <div style={{ flex: 1 }}><div style={{ fontWeight: 700 }}>{c.name}</div><div style={{ color: 'var(--muted)', fontSize: 12 }}>{c.detail}</div></div>
              {c.status === 'ok' ? <Tag icon={<CheckCircleFilled />} color="success" style={{ borderRadius: 999 }}>OK</Tag> : <Tag color="warning" style={{ borderRadius: 999 }}>Внимание</Tag>}
            </div>
          ))}
        </Card>
        <Card title="Ресурсы">
          <Space direction="vertical" size={20} style={{ width: '100%' }}>
            <div><div style={{ display: 'flex', justifyContent: 'space-between' }}><span>RAM</span><span>1,4 / 3,9 ГБ</span></div><Progress percent={36} showInfo={false} strokeColor="#4f46e5" /></div>
            <div><div style={{ display: 'flex', justifyContent: 'space-between' }}><span>Диск</span><span>11 / 50 ГБ</span></div><Progress percent={22} showInfo={false} strokeColor="#10b981" /></div>
            <div><div style={{ display: 'flex', justifyContent: 'space-between' }}><span>CPU</span><span>0,1 / 2 vCPU</span></div><Progress percent={8} showInfo={false} strokeColor="#8b5cf6" /></div>
          </Space>
        </Card>
      </div>
    </>
  );
}
