import { Avatar, Button, Card, Descriptions, Tag, Typography } from 'antd';
import { LogoutOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import PageHeader from '../components/PageHeader';
import { formatDate, formatDateTime, useApp } from '../lib/store';

const ROLE_LABEL: Record<string, string> = {
  admin: 'Администратор',
  employee: 'Сотрудник',
  client: 'Клиент',
};

export default function ProfilePage() {
  const { user, logout } = useApp();
  if (!user) return null;

  const raw = user.raw;
  const initials = user.name.split(' ').map((p) => p[0]).slice(0, 2).join('');

  return (
    <>
      <PageHeader
        title="Профиль"
        subtitle="Данные учётной записи и безопасность"
        extra={<Button icon={<LogoutOutlined />} onClick={() => { void logout(); }}>Выйти</Button>}
      />
      <div className="grid-2">
        <Card>
          <div style={{ display: 'flex', gap: 18, alignItems: 'center' }}>
            <Avatar size={72} style={{
              background: 'linear-gradient(135deg,#4f46e5,#8b5cf6)', fontSize: 26,
            }}>
              {initials}
            </Avatar>
            <div>
              <Typography.Title level={4} style={{ margin: 0 }}>{user.name}</Typography.Title>
              <div style={{ color: 'var(--muted)' }}>{user.email}</div>
              <Tag color="purple" style={{ marginTop: 8 }}>{ROLE_LABEL[user.role]}</Tag>
            </div>
          </div>
          <Descriptions column={1} style={{ marginTop: 24 }} items={[
            { key: 'role', label: 'Роль', children: ROLE_LABEL[user.role] },
            { key: 'phone', label: 'Телефон', children: raw.phone ?? '—' },
            { key: 'snils', label: 'СНИЛС', children: raw.snils ?? '—' },
            { key: 'created', label: 'Учётная запись создана', children: formatDate(raw.createdAt) },
            { key: 'login', label: 'Последний вход', children: formatDateTime(raw.lastLoginAt) },
            { key: 'docs', label: 'Документов', children: `${raw.documentsTotal} (подписано ${raw.documentsSigned})` },
          ]} />
        </Card>

        <Card title="Безопасность">
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <SafetyCertificateOutlined style={{ fontSize: 26, color: '#10b981' }} />
            <div>
              <div style={{ fontWeight: 700 }}>
                {user.role === 'client' ? 'Вход по ссылке из письма' : 'Вход по сертификату УКЭП'}
              </div>
              <div style={{ color: 'var(--muted)' }}>
                ГОСТ 34.10-2012 · CAdES-BES · ключ не покидает рабочее место
              </div>
            </div>
          </div>

          <div style={{ marginTop: 20 }}>
            <div style={{ fontWeight: 700, marginBottom: 6 }}>Согласие на обработку ПДн</div>
            {raw.consentSigned ? (
              <Tag color="success">Подписано {formatDate(raw.consentSignedAt)}</Tag>
            ) : (
              <Tag color="warning">Не подписано</Tag>
            )}
          </div>

          <div style={{ marginTop: 20 }}>
            <div style={{ fontWeight: 700, marginBottom: 6 }}>Профиль</div>
            {raw.profileComplete ? (
              <Tag color="success">Заполнен</Tag>
            ) : (
              <Tag color="warning">Требует заполнения</Tag>
            )}
          </div>
        </Card>
      </div>
    </>
  );
}
