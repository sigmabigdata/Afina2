import { Avatar, Card, Descriptions, Tag, Typography } from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import PageHeader from '../components/PageHeader';
import { useApp } from '../lib/store';

export default function ProfilePage() {
  const { user } = useApp();
  return (
    <>
      <PageHeader title="Профиль" subtitle="Данные учётной записи и безопасность" />
      <div className="grid-2">
        <Card>
          <div style={{ display: 'flex', gap: 18, alignItems: 'center' }}>
            <Avatar size={72} style={{ background: 'linear-gradient(135deg,#4f46e5,#8b5cf6)', fontSize: 26 }}>
              {user.name.split(' ').map((p) => p[0]).slice(0, 2).join('')}
            </Avatar>
            <div>
              <Typography.Title level={4} style={{ margin: 0 }}>{user.name}</Typography.Title>
              <div style={{ color: 'var(--muted)' }}>{user.email}</div>
              <Tag color="purple" style={{ marginTop: 8 }}>{user.role === 'admin' ? 'Администратор' : 'Клиент'}</Tag>
            </div>
          </div>
          <Descriptions column={1} style={{ marginTop: 24 }} items={[
            { key: 'role', label: 'Роль', children: user.role },
            { key: 'auth', label: 'Способ входа', children: 'Сертификат / magic-link' },
            { key: '2fa', label: 'Двухфакторная защита', children: 'УКЭП + PIN' },
          ]} />
        </Card>
        <Card title="Безопасность">
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <SafetyCertificateOutlined style={{ fontSize: 26, color: '#10b981' }} />
            <div>
              <div style={{ fontWeight: 700 }}>Сертификат активен</div>
              <div style={{ color: 'var(--muted)' }}>ГОСТ 34.10-2012 · до 10.09.2027</div>
            </div>
          </div>
        </Card>
      </div>
    </>
  );
}
